package lld.notificationservice;

import lld.notificationservice.channel.ChannelDispatcher;
import lld.notificationservice.channel.DispatchResult;
import lld.notificationservice.channel.FailureType;
import lld.notificationservice.channel.PushDispatcher;
import lld.notificationservice.channel.EmailDispatcher;
import lld.notificationservice.channel.SmsDispatcher;
import lld.notificationservice.channel.WebhookDispatcher;
import lld.notificationservice.delivery.InMemoryDeliveryStore;
import lld.notificationservice.dedup.InMemoryIdempotencyStore;
import lld.notificationservice.model.DeliveryAttempt;
import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.DeviceToken;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.NotificationPriority;
import lld.notificationservice.model.NotificationRequest;
import lld.notificationservice.model.NotificationStatus;
import lld.notificationservice.preference.InMemoryPreferenceStore;
import lld.notificationservice.preference.UserPreferences;
import lld.notificationservice.retry.ExponentialBackoffPolicy;
import lld.notificationservice.template.NotificationTemplate;
import lld.notificationservice.template.SimpleTemplateEngine;
import lld.notificationservice.template.TemplateRegistry;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * End-to-end demonstration of the Notification Service LLD.
 *
 * <p>Scenarios:
 * <ol>
 *   <li><b>Happy path</b> — OTP delivered via push on first attempt.</li>
 *   <li><b>Push hard-fail → email fallback</b> — dispatcher with forced stale-token error.</li>
 *   <li><b>Duplicate event_id</b> — idempotency absorbs a producer retry.</li>
 *   <li><b>DND hold</b> — marketing notification held inside a DND window.</li>
 *   <li><b>Frequency cap</b> — user's daily cap exceeded; notification dropped.</li>
 *   <li><b>Critical bypass</b> — OTP overrides DND and frequency cap.</li>
 *   <li><b>All channels exhausted</b> — DLQ escalation when push, email, and SMS all fail.</li>
 *   <li><b>Delivery audit</b> — inspect per-channel attempt history.</li>
 * </ol>
 */
public class NotificationServiceDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Notification Service LLD Demo ===\n");

        scenario1_happyPath();
        scenario2_pushHardFailEmailFallback();
        scenario3_duplicateIdempotency();
        scenario4_dndHold();
        scenario5_frequencyCap();
        scenario6_criticalBypassesGates();
        scenario7_allChannelsExhausted();
        scenario8_deliveryAudit();

        System.out.println("\n=== Demo complete ===");
    }

    // -------------------------------------------------------------------------
    // Scenario 1 — Happy path: OTP via push
    // -------------------------------------------------------------------------
    private static void scenario1_happyPath() {
        System.out.println("--- Scenario 1: Happy Path (OTP via Push) ---");

        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();
        prefStore.savePreferences(UserPreferences.builder("user-alice")
                .email("alice@example.com")
                .phoneNumber("+1-555-0101")
                .deviceToken(new DeviceToken("token-alice-ios-001", DeviceToken.Platform.IOS, "5.0"))
                .build());

        NotificationService svc = buildService(new Random(42), prefStore); // seed 42 → push succeeds

        NotificationRequest req = NotificationRequest
                .builder("auth-service", "evt-otp-001", "user-alice", "otp")
                .priority(NotificationPriority.CRITICAL)
                .payload(Map.of("otp", "847291", "expiryMinutes", "5"))
                .preferredChannels(List.of(DeliveryChannel.PUSH, DeliveryChannel.SMS))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);
        assert resp.getStatus() == NotificationStatus.DELIVERED
                || resp.getStatus() == NotificationStatus.UNDELIVERABLE
                : "Expected terminal status";
        System.out.println("--- Scenario 1 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 2 — Push hard-fails (stale token) → email fallback
    // -------------------------------------------------------------------------
    private static void scenario2_pushHardFailEmailFallback() {
        System.out.println("--- Scenario 2: Push Hard-Fail → Email Fallback ---");

        // Force push to always hard-fail; email to always succeed
        NotificationService svc = buildServiceWithCustomDispatchers(
                alwaysHardFail(DeliveryChannel.PUSH),
                alwaysSucceed(DeliveryChannel.EMAIL),
                new SmsDispatcher()
        );

        NotificationRequest req = NotificationRequest
                .builder("order-service", "evt-order-002", "user-bob", "order_confirmed")
                .priority(NotificationPriority.HIGH)
                .payload(Map.of("orderId", "ORD-9823", "amount", "$49.99"))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);
        assert resp.getStatus() == NotificationStatus.DELIVERED : "Expected DELIVERED via email fallback";
        System.out.println("--- Scenario 2 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 3 — Duplicate event_id (idempotency)
    // -------------------------------------------------------------------------
    private static void scenario3_duplicateIdempotency() {
        System.out.println("--- Scenario 3: Duplicate event_id Absorbed ---");

        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();
        prefStore.savePreferences(UserPreferences.builder("user-carol")
                .email("carol@example.com")
                .phoneNumber("+1-555-0202")
                .deviceToken(new DeviceToken("token-carol-android-001", DeviceToken.Platform.ANDROID, "3.5"))
                .build());
        NotificationService svc = buildService(new Random(1), prefStore);

        NotificationRequest req = NotificationRequest
                .builder("payment-service", "evt-payment-003", "user-carol", "payment_received")
                .payload(Map.of("amount", "$120.00", "from", "Dave"))
                .build();

        NotificationResponse first  = svc.send(req);
        NotificationResponse second = svc.send(req); // same event_id

        System.out.println("First  response : " + first);
        System.out.println("Second response : " + second);
        assert first.getNotificationId().equals(second.getNotificationId())
                : "Both responses must return the same notification id";
        assert second.getOutcome() == NotificationResponse.Outcome.DUPLICATE : "Expected DUPLICATE outcome";
        System.out.println("--- Scenario 3 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 4 — DND window holds the notification
    // -------------------------------------------------------------------------
    private static void scenario4_dndHold() {
        System.out.println("--- Scenario 4: DND Window Hold ---");

        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();

        // Put the user in DND for the entire day (00:00 – 23:59) to guarantee the check fires
        UserPreferences prefs = UserPreferences.builder("user-dave")
                .email("dave@example.com")
                .phoneNumber("+1-555-0200")
                .deviceToken(new DeviceToken("token-dave-001", DeviceToken.Platform.ANDROID, "3.0"))
                .dnd(LocalTime.of(0, 0), LocalTime.of(23, 59))
                .timezone(ZoneId.of("UTC"))
                .build();
        prefStore.savePreferences(prefs);

        NotificationService svc = buildService(new Random(10), prefStore);

        NotificationRequest req = NotificationRequest
                .builder("marketing-service", "evt-promo-004", "user-dave", "weekly_digest")
                .priority(NotificationPriority.LOW)
                .payload(Map.of("highlights", "5 new deals"))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);
        assert resp.getStatus() == NotificationStatus.HELD : "Expected HELD status for DND";
        System.out.println("--- Scenario 4 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 5 — Frequency cap exceeded
    // -------------------------------------------------------------------------
    private static void scenario5_frequencyCap() {
        System.out.println("--- Scenario 5: Frequency Cap Exceeded ---");

        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();

        UserPreferences prefs = UserPreferences.builder("user-eve")
                .email("eve@example.com")
                .deviceToken(new DeviceToken("token-eve-001", DeviceToken.Platform.IOS, "2.1"))
                .freqCapPerDay(2)
                .build();
        prefStore.savePreferences(prefs);

        NotificationService svc = buildService(new Random(99), prefStore);

        for (int i = 1; i <= 3; i++) {
            NotificationRequest req = NotificationRequest
                    .builder("promo-service", "evt-promo-00" + i, "user-eve", "flash_sale")
                    .priority(NotificationPriority.NORMAL)
                    .payload(Map.of("discount", i * 10 + "%"))
                    .build();
            NotificationResponse resp = svc.send(req);
            System.out.printf("  Send #%d: outcome=%-9s status=%s%n", i, resp.getOutcome(), resp.getStatus());
        }
        System.out.println("--- Scenario 5 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 6 — CRITICAL priority bypasses DND and frequency cap
    // -------------------------------------------------------------------------
    private static void scenario6_criticalBypassesGates() {
        System.out.println("--- Scenario 6: CRITICAL Bypasses DND and Frequency Cap ---");

        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();

        UserPreferences prefs = UserPreferences.builder("user-frank")
                .email("frank@example.com")
                .phoneNumber("+1-555-0300")
                .deviceToken(new DeviceToken("token-frank-001", DeviceToken.Platform.IOS, "4.0"))
                .dnd(LocalTime.of(0, 0), LocalTime.of(23, 59))
                .timezone(ZoneId.of("UTC"))
                .freqCapPerDay(1) // cap already hit
                .build();
        prefStore.savePreferences(prefs);
        prefStore.incrementAndGetDailyCount("user-frank"); // simulate cap already reached

        NotificationService svc = buildService(new Random(7), prefStore);

        NotificationRequest req = NotificationRequest
                .builder("security-service", "evt-security-006", "user-frank", "otp")
                .priority(NotificationPriority.CRITICAL)
                .payload(Map.of("otp", "193847", "expiryMinutes", "2"))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);
        assert resp.getStatus() != NotificationStatus.HELD   : "CRITICAL must not be held";
        assert resp.getStatus() != NotificationStatus.DROPPED : "CRITICAL must not be dropped";
        System.out.println("--- Scenario 6 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 7 — All channels fail → DLQ escalation
    // -------------------------------------------------------------------------
    private static void scenario7_allChannelsExhausted() {
        System.out.println("--- Scenario 7: All Channels Exhausted → DLQ ---");

        // All dispatchers hard-fail immediately
        NotificationService svc = buildServiceWithCustomDispatchers(
                alwaysHardFail(DeliveryChannel.PUSH),
                alwaysHardFail(DeliveryChannel.EMAIL),
                alwaysHardFail(DeliveryChannel.SMS)
        );

        NotificationRequest req = NotificationRequest
                .builder("alert-service", "evt-alert-007", "user-grace", "system_alert")
                .priority(NotificationPriority.HIGH)
                .payload(Map.of("message", "Your account has been locked"))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);
        assert resp.getStatus() == NotificationStatus.UNDELIVERABLE : "Expected UNDELIVERABLE";
        System.out.println("--- Scenario 7 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Scenario 8 — Delivery audit trail
    // -------------------------------------------------------------------------
    private static void scenario8_deliveryAudit() throws InterruptedException {
        System.out.println("--- Scenario 8: Delivery Audit Trail ---");

        // Push soft-fails twice then succeeds; use controlled dispatcher
        NotificationService svc = buildServiceWithCustomDispatchers(
                softFailThenSucceed(DeliveryChannel.PUSH, 2),
                alwaysSucceed(DeliveryChannel.EMAIL),
                new SmsDispatcher()
        );

        NotificationRequest req = NotificationRequest
                .builder("billing-service", "evt-bill-008", "user-henry", "invoice_ready")
                .payload(Map.of("invoiceId", "INV-20240501", "amount", "$299.00"))
                .build();

        NotificationResponse resp = svc.send(req);
        System.out.println("Response: " + resp);

        List<DeliveryAttempt> history = svc.getDeliveryHistory(resp.getNotificationId());
        System.out.println("Delivery history (" + history.size() + " attempt(s)):");
        for (DeliveryAttempt attempt : history) {
            System.out.println("  " + attempt);
        }
        System.out.println("--- Scenario 8 complete ---\n");
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    private static NotificationService buildService(Random rng) {
        return buildService(rng, new InMemoryPreferenceStore());
    }

    private static NotificationService buildService(Random rng, InMemoryPreferenceStore prefStore) {
        TemplateRegistry registry = buildDefaultRegistry();
        return new NotificationServiceImpl(
                prefStore,
                new SimpleTemplateEngine(),
                registry,
                new InMemoryDeliveryStore(),
                new InMemoryIdempotencyStore(),
                List.of(
                        new PushDispatcher(rng),
                        new EmailDispatcher(rng),
                        new SmsDispatcher(rng),
                        new WebhookDispatcher(rng)),
                new ExponentialBackoffPolicy(3, 50, 2.0, 500)); // fast backoff for demo
    }

    private static NotificationService buildServiceWithCustomDispatchers(ChannelDispatcher... dispatchers) {
        InMemoryPreferenceStore prefStore = new InMemoryPreferenceStore();
        // Register a user with all contact details so every channel can attempt
        prefStore.savePreferences(UserPreferences.builder("user-bob")
                .email("bob@example.com")
                .phoneNumber("+1-555-0100")
                .deviceToken(new DeviceToken("token-bob-android-001", DeviceToken.Platform.ANDROID, "4.1"))
                .build());
        prefStore.savePreferences(UserPreferences.builder("user-grace")
                .email("grace@example.com")
                .phoneNumber("+1-555-0400")
                .deviceToken(new DeviceToken("token-grace-ios-001", DeviceToken.Platform.IOS, "4.2"))
                .build());
        prefStore.savePreferences(UserPreferences.builder("user-henry")
                .email("henry@example.com")
                .phoneNumber("+1-555-0500")
                .deviceToken(new DeviceToken("token-henry-web-001", DeviceToken.Platform.WEB, "1.0"))
                .build());

        TemplateRegistry registry = buildDefaultRegistry();
        return new NotificationServiceImpl(
                prefStore,
                new SimpleTemplateEngine(),
                registry,
                new InMemoryDeliveryStore(),
                new InMemoryIdempotencyStore(),
                List.of(dispatchers),
                new ExponentialBackoffPolicy(3, 50, 2.0, 500));
    }

    private static TemplateRegistry buildDefaultRegistry() {
        TemplateRegistry registry = new TemplateRegistry();

        // OTP templates
        registry.register(new NotificationTemplate("otp", DeliveryChannel.PUSH,
                "Your OTP is {{otp}}. Expires in {{expiryMinutes}} minutes."));
        registry.register(new NotificationTemplate("otp", DeliveryChannel.SMS,
                "Your verification code is {{otp}}. Valid for {{expiryMinutes}} min. Do not share."));
        registry.register(new NotificationTemplate("otp", DeliveryChannel.EMAIL,
                "One-Time Password",
                "Hello,\n\nYour OTP is <b>{{otp}}</b>. It expires in {{expiryMinutes}} minutes."));

        // Order confirmation templates
        registry.register(new NotificationTemplate("order_confirmed", DeliveryChannel.PUSH,
                "Order {{orderId}} confirmed! Total: {{amount}}"));
        registry.register(new NotificationTemplate("order_confirmed", DeliveryChannel.EMAIL,
                "Order Confirmed — {{orderId}}",
                "Your order {{orderId}} for {{amount}} has been confirmed. Thank you!"));

        // Invoice templates
        registry.register(new NotificationTemplate("invoice_ready", DeliveryChannel.PUSH,
                "Invoice {{invoiceId}} ready — {{amount}}"));
        registry.register(new NotificationTemplate("invoice_ready", DeliveryChannel.EMAIL,
                "Invoice {{invoiceId}} is Ready",
                "Dear customer,\n\nYour invoice {{invoiceId}} for {{amount}} is now available."));

        // Generic alert
        registry.register(new NotificationTemplate("system_alert", DeliveryChannel.PUSH,
                "Alert: {{message}}"));
        registry.register(new NotificationTemplate("system_alert", DeliveryChannel.EMAIL,
                "System Alert", "{{message}}"));
        registry.register(new NotificationTemplate("system_alert", DeliveryChannel.SMS,
                "ALERT: {{message}}"));

        return registry;
    }

    // -------------------------------------------------------------------------
    // Stub dispatchers for controlled scenarios
    // -------------------------------------------------------------------------

    private static ChannelDispatcher alwaysHardFail(DeliveryChannel channel) {
        return new ChannelDispatcher() {
            @Override public DeliveryChannel getChannel() { return channel; }
            @Override public DispatchResult dispatch(
                    lld.notificationservice.model.Notification n,
                    lld.notificationservice.preference.UserPreferences p,
                    lld.notificationservice.model.RenderedContent c) {
                System.out.printf("  [%-5s] %-20s → HARD_FAIL (forced stub)%n",
                        channel, n.getRequest().getType());
                return DispatchResult.hardFail("STUB_HARD_FAIL", "Forced hard failure for demo");
            }
        };
    }

    private static ChannelDispatcher alwaysSucceed(DeliveryChannel channel) {
        return new ChannelDispatcher() {
            int seq = 0;
            @Override public DeliveryChannel getChannel() { return channel; }
            @Override public DispatchResult dispatch(
                    lld.notificationservice.model.Notification n,
                    lld.notificationservice.preference.UserPreferences p,
                    lld.notificationservice.model.RenderedContent c) {
                System.out.printf("  [%-5s] %-20s → SENT   (forced success stub)%n",
                        channel, n.getRequest().getType());
                return DispatchResult.success("stub-" + channel.name().toLowerCase() + "-" + (++seq));
            }
        };
    }

    /** Soft-fails for the first {@code failCount} calls, then succeeds. */
    private static ChannelDispatcher softFailThenSucceed(DeliveryChannel channel, int failCount) {
        return new ChannelDispatcher() {
            int calls = 0;
            @Override public DeliveryChannel getChannel() { return channel; }
            @Override public DispatchResult dispatch(
                    lld.notificationservice.model.Notification n,
                    lld.notificationservice.preference.UserPreferences p,
                    lld.notificationservice.model.RenderedContent c) {
                calls++;
                if (calls <= failCount) {
                    System.out.printf("  [%-5s] %-20s → SOFT_FAIL (call %d/%d, stub)%n",
                            channel, n.getRequest().getType(), calls, failCount);
                    return DispatchResult.softFail("TRANSIENT", "Simulated transient failure");
                }
                System.out.printf("  [%-5s] %-20s → SENT   (call %d, stub)%n",
                        channel, n.getRequest().getType(), calls);
                return DispatchResult.success("stub-" + channel.name().toLowerCase() + "-success");
            }
        };
    }
}
