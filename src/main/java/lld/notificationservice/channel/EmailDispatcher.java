package lld.notificationservice.channel;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.UserPreferences;

import java.util.Random;
import java.util.UUID;

/**
 * Simulated SendGrid / SES email dispatcher.
 *
 * <p>Realistic success rate: ~80 %.
 * Hard failures represent permanent bounces and unsubscribes.
 * Soft failures represent rate limits (429) and service outages (503).
 */
public final class EmailDispatcher implements ChannelDispatcher {

    private static final double SUCCESS_RATE   = 0.80;
    private static final double HARD_FAIL_RATE = 0.12; // bounce / unsubscribe

    private final Random rng;

    public EmailDispatcher() {
        this(new Random());
    }

    public EmailDispatcher(Random rng) {
        this.rng = rng;
    }

    @Override
    public DeliveryChannel getChannel() {
        return DeliveryChannel.EMAIL;
    }

    @Override
    public DispatchResult dispatch(Notification notification, UserPreferences prefs, RenderedContent content) {
        String email = prefs.getEmail();
        if (email == null || email.isBlank()) {
            return DispatchResult.hardFail("NO_EMAIL", "No email address registered for user");
        }

        double roll = rng.nextDouble();

        if (roll < SUCCESS_RATE) {
            String msgId = "sg-" + UUID.randomUUID().toString().substring(0, 8);
            System.out.printf("  [EMAIL] %-20s → SENT   to=%s subject='%s'%n",
                    notification.getRequest().getType(), email,
                    content.getSubject() != null ? content.getSubject() : "(no subject)");
            return DispatchResult.success(msgId);
        }

        if (roll < SUCCESS_RATE + HARD_FAIL_RATE) {
            System.out.printf("  [EMAIL] %-20s → HARD_FAIL (BouncePermanent)%n",
                    notification.getRequest().getType());
            return DispatchResult.hardFail("BouncePermanent",
                    "Email address does not exist or has unsubscribed");
        }

        System.out.printf("  [EMAIL] %-20s → SOFT_FAIL (RateLimit 429)%n",
                notification.getRequest().getType());
        return DispatchResult.softFail("TooManyRequests", "SendGrid rate limit exceeded; retry after backoff");
    }
}
