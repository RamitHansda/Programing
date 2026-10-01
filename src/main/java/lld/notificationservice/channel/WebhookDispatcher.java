package lld.notificationservice.channel;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.UserPreferences;

import java.util.Random;
import java.util.UUID;

/**
 * Simulated HTTP Webhook dispatcher.
 *
 * <p>Realistic success rate: ~75–80 %, highly variable per endpoint.
 * Hard failures: 410 Gone (endpoint permanently removed), 400 Bad Request (schema mismatch).
 * Soft failures: 5xx, connection timeout, DNS failure.
 *
 * <p>In production each webhook endpoint would have its own circuit breaker; a half-open
 * endpoint would receive a single probe request before re-opening the circuit.
 */
public final class WebhookDispatcher implements ChannelDispatcher {

    private static final double SUCCESS_RATE   = 0.76;
    private static final double HARD_FAIL_RATE = 0.08; // 410 Gone / 400 Bad Request

    private final Random rng;

    public WebhookDispatcher() {
        this(new Random());
    }

    public WebhookDispatcher(Random rng) {
        this.rng = rng;
    }

    @Override
    public DeliveryChannel getChannel() {
        return DeliveryChannel.WEBHOOK;
    }

    @Override
    public DispatchResult dispatch(Notification notification, UserPreferences prefs, RenderedContent content) {
        String endpoint = prefs.getWebhookEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return DispatchResult.hardFail("NO_WEBHOOK_ENDPOINT", "No webhook URL registered for user");
        }

        double roll = rng.nextDouble();

        if (roll < SUCCESS_RATE) {
            String msgId = "wh-" + UUID.randomUUID().toString().substring(0, 8);
            System.out.printf("  [WBHK] %-20s → SENT   endpoint=%s%n",
                    notification.getRequest().getType(), endpoint);
            return DispatchResult.success(msgId);
        }

        if (roll < SUCCESS_RATE + HARD_FAIL_RATE) {
            System.out.printf("  [WBHK] %-20s → HARD_FAIL (410 Gone: endpoint removed)%n",
                    notification.getRequest().getType());
            return DispatchResult.hardFail("HTTP_410", "Webhook endpoint no longer exists");
        }

        System.out.printf("  [WBHK] %-20s → SOFT_FAIL (503 / connection timeout)%n",
                notification.getRequest().getType());
        return DispatchResult.softFail("HTTP_503", "Webhook endpoint returned 503 or connection timed out");
    }
}
