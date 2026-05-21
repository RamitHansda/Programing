package lld.notificationservice;

import lld.notificationservice.channel.ChannelDispatcher;
import lld.notificationservice.channel.DispatchResult;
import lld.notificationservice.channel.FailureType;
import lld.notificationservice.delivery.DeliveryStore;
import lld.notificationservice.dedup.IdempotencyStore;
import lld.notificationservice.model.DeliveryAttempt;
import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.NotificationPriority;
import lld.notificationservice.model.NotificationRequest;
import lld.notificationservice.model.NotificationStatus;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.PreferenceStore;
import lld.notificationservice.preference.UserPreferences;
import lld.notificationservice.retry.RetryPolicy;
import lld.notificationservice.retry.ExponentialBackoffPolicy;
import lld.notificationservice.template.NotificationTemplate;
import lld.notificationservice.template.TemplateEngine;
import lld.notificationservice.template.TemplateRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core orchestrator implementing the full notification lifecycle:
 *
 * <ol>
 *   <li><b>Idempotency</b> — reject duplicate {@code (producerId, eventId)} pairs.</li>
 *   <li><b>Enrichment</b> — load user preferences; resolve channel order.</li>
 *   <li><b>Gate checks</b> — DND hold and frequency-cap drop (bypassed for CRITICAL/HIGH).</li>
 *   <li><b>Template rendering</b> — render channel-specific content from the payload.</li>
 *   <li><b>Dispatch with retry + fallback</b>
 *     <ul>
 *       <li>SOFT_FAIL → exponential-backoff retries up to {@code maxAttempts}.</li>
 *       <li>HARD_FAIL or retries exhausted → immediately try next channel.</li>
 *       <li>All channels exhausted → mark UNDELIVERABLE and add to DLQ.</li>
 *     </ul>
 *   </li>
 *   <li><b>Audit trail</b> — every attempt persisted to the delivery store.</li>
 * </ol>
 */
public final class NotificationServiceImpl implements NotificationService {

    private final PreferenceStore preferenceStore;
    private final TemplateEngine templateEngine;
    private final TemplateRegistry templateRegistry;
    private final DeliveryStore deliveryStore;
    private final IdempotencyStore idempotencyStore;
    private final Map<DeliveryChannel, ChannelDispatcher> dispatchers;
    private final RetryPolicy defaultRetryPolicy;

    // Tracks all in-flight and completed notifications by id.
    private final Map<String, Notification> notifications = new ConcurrentHashMap<>();

    public NotificationServiceImpl(PreferenceStore preferenceStore,
                                   TemplateEngine templateEngine,
                                   TemplateRegistry templateRegistry,
                                   DeliveryStore deliveryStore,
                                   IdempotencyStore idempotencyStore,
                                   List<ChannelDispatcher> dispatchers,
                                   RetryPolicy defaultRetryPolicy) {
        this.preferenceStore   = preferenceStore;
        this.templateEngine    = templateEngine;
        this.templateRegistry  = templateRegistry;
        this.deliveryStore     = deliveryStore;
        this.idempotencyStore  = idempotencyStore;
        this.defaultRetryPolicy = defaultRetryPolicy;

        Map<DeliveryChannel, ChannelDispatcher> dispatcherMap = new ConcurrentHashMap<>();
        for (ChannelDispatcher d : dispatchers) {
            dispatcherMap.put(d.getChannel(), d);
        }
        this.dispatchers = Collections.unmodifiableMap(dispatcherMap);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public NotificationResponse send(NotificationRequest request) {

        // 1. Idempotency check
        var existing = idempotencyStore.get(request.getProducerId(), request.getEventId());
        if (existing.isPresent()) {
            System.out.println("  [DEDUP] Duplicate event_id=" + request.getEventId()
                    + " → returning existing notification id");
            return NotificationResponse.duplicate(existing.get());
        }

        // 2. Create and register the notification
        Notification notification = new Notification(request);
        notifications.put(notification.getId(), notification);
        idempotencyStore.putIfAbsent(request.getProducerId(), request.getEventId(), notification.getId());

        // 3. Load user preferences
        UserPreferences prefs = preferenceStore.getPreferences(request.getUserId());

        // 4. Resolve channel order
        List<DeliveryChannel> channelOrder = resolveChannelOrder(request, prefs);
        notification.setChannelOrder(channelOrder);

        // 5. Gate checks (skip for CRITICAL and HIGH)
        boolean forcedDelivery = request.isForceDelivery()
                || request.getPriority() == NotificationPriority.CRITICAL
                || request.getPriority() == NotificationPriority.HIGH;

        if (!forcedDelivery && prefs.isInDnd()) {
            System.out.println("  [GATE]  DND active for user " + request.getUserId() + " — notification held");
            notification.setStatus(NotificationStatus.HELD);
            return NotificationResponse.held(notification.getId());
        }

        if (!forcedDelivery && isFreqCapExceeded(prefs)) {
            System.out.printf("  [GATE]  Frequency cap exceeded for user %s (cap=%d/day)%n",
                    request.getUserId(), prefs.getFreqCapPerDay());
            notification.setStatus(NotificationStatus.DROPPED);
            return NotificationResponse.dropped(notification.getId(), "Daily frequency cap exceeded");
        }

        // Increment daily counter after gate checks pass
        preferenceStore.incrementAndGetDailyCount(request.getUserId());

        // 6. Choose retry policy (CRITICAL gets zero-delay policy)
        RetryPolicy retryPolicy = request.getPriority() == NotificationPriority.CRITICAL
                ? ExponentialBackoffPolicy.immediate(defaultRetryPolicy.getMaxAttempts())
                : defaultRetryPolicy;

        // 7. Dispatch with channel fallback
        boolean delivered = deliverWithFallback(notification, prefs, channelOrder, retryPolicy);

        if (delivered) {
            notification.setStatus(NotificationStatus.DELIVERED);
        } else {
            notification.setStatus(NotificationStatus.UNDELIVERABLE);
            System.out.println("  [DLQ]   Notification " + notification.getId().substring(0, 8)
                    + "... added to dead-letter queue");
        }

        return NotificationResponse.accepted(notification.getId(), notification.getStatus());
    }

    @Override
    public List<DeliveryAttempt> getDeliveryHistory(String notificationId) {
        return deliveryStore.getAttempts(notificationId);
    }

    @Override
    public NotificationStatus getStatus(String notificationId) {
        Notification n = notifications.get(notificationId);
        return n != null ? n.getStatus() : null;
    }

    // -------------------------------------------------------------------------
    // Fallback orchestration
    // -------------------------------------------------------------------------

    /**
     * Iterates through the channel order, attempting dispatch (with retries)
     * on each channel until one succeeds or all are exhausted.
     */
    private boolean deliverWithFallback(Notification notification, UserPreferences prefs,
                                        List<DeliveryChannel> channelOrder, RetryPolicy retryPolicy) {
        for (DeliveryChannel channel : channelOrder) {
            if (prefs.isOptedOut(channel)) {
                System.out.printf("  [SKIP]  channel=%s user has opted out%n", channel);
                continue;
            }

            ChannelDispatcher dispatcher = dispatchers.get(channel);
            if (dispatcher == null) {
                System.out.printf("  [SKIP]  channel=%s no dispatcher registered%n", channel);
                continue;
            }

            NotificationTemplate template = templateRegistry
                    .find(notification.getRequest().getType(), channel)
                    .orElseGet(() -> templateRegistry.fallback(notification.getRequest().getType(), channel));

            RenderedContent content = templateEngine.render(template, notification.getRequest().getPayload());

            boolean success = attemptWithRetry(notification, prefs, channel, dispatcher, content, retryPolicy);
            if (success) return true;

            System.out.printf("  [FALLBACK] channel=%s exhausted → trying next channel%n", channel);
        }
        return false;
    }

    /**
     * Dispatches on a single channel up to {@code retryPolicy.getMaxAttempts()} times.
     * Returns true on first success; returns false if HARD_FAIL or all attempts exhausted.
     */
    private boolean attemptWithRetry(Notification notification, UserPreferences prefs,
                                     DeliveryChannel channel, ChannelDispatcher dispatcher,
                                     RenderedContent content, RetryPolicy retryPolicy) {
        int maxAttempts = retryPolicy.getMaxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            DispatchResult result = dispatcher.dispatch(notification, prefs, content);
            DeliveryAttempt da = new DeliveryAttempt(
                    notification.getId(), notification.getRequest().getUserId(),
                    channel, attempt, result);
            deliveryStore.saveAttempt(da);

            if (result.getFailureType() == FailureType.SUCCESS) return true;

            if (result.getFailureType() == FailureType.HARD_FAIL) {
                System.out.printf("  [HARD_FAIL] channel=%s code=%s → skip retries%n",
                        channel, result.getErrorCode());
                return false;
            }

            // SOFT_FAIL: retry after backoff
            if (attempt < maxAttempts) {
                long delayMs = retryPolicy.getDelayMs(attempt);
                System.out.printf("  [RETRY] channel=%s attempt=%d/%d → backoff %d ms%n",
                        channel, attempt, maxAttempts, delayMs);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<DeliveryChannel> resolveChannelOrder(NotificationRequest request,
                                                      UserPreferences prefs) {
        if (request.hasPreferredChannels()) {
            return request.getPreferredChannels();
        }
        List<DeliveryChannel> order = new ArrayList<>(prefs.getChannelPriority());
        if (order.isEmpty()) {
            // Sensible default if user has no preferences recorded
            return List.of(DeliveryChannel.PUSH, DeliveryChannel.EMAIL, DeliveryChannel.SMS);
        }
        return order;
    }

    private boolean isFreqCapExceeded(UserPreferences prefs) {
        int cap = prefs.getFreqCapPerDay();
        if (cap <= 0) return false;
        return preferenceStore.getDailyCount(prefs.getUserId()) >= cap;
    }
}
