package lld.notificationservice.channel;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.UserPreferences;

import java.util.Random;
import java.util.UUID;

/**
 * Simulated Twilio / AWS SNS SMS dispatcher.
 *
 * <p>Realistic success rate: ~94 %.
 * Hard failures represent opted-out numbers and unreachable handsets.
 * Soft failures represent carrier queue overflow.
 */
public final class SmsDispatcher implements ChannelDispatcher {

    private static final double SUCCESS_RATE   = 0.94;
    private static final double HARD_FAIL_RATE = 0.04; // opted-out / unreachable

    private final Random rng;

    public SmsDispatcher() {
        this(new Random());
    }

    public SmsDispatcher(Random rng) {
        this.rng = rng;
    }

    @Override
    public DeliveryChannel getChannel() {
        return DeliveryChannel.SMS;
    }

    @Override
    public DispatchResult dispatch(Notification notification, UserPreferences prefs, RenderedContent content) {
        String phone = prefs.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return DispatchResult.hardFail("NO_PHONE", "No phone number registered for user");
        }

        // Enforce 160-char SMS limit; truncate with notice rather than silently dropping chars.
        String body = content.getBody();
        if (body.length() > 160) {
            body = body.substring(0, 157) + "...";
        }

        double roll = rng.nextDouble();

        if (roll < SUCCESS_RATE) {
            String msgId = "twilio-" + UUID.randomUUID().toString().substring(0, 8);
            System.out.printf("  [SMS]  %-20s → SENT   to=%s%n",
                    notification.getRequest().getType(), phone);
            return DispatchResult.success(msgId);
        }

        if (roll < SUCCESS_RATE + HARD_FAIL_RATE) {
            System.out.printf("  [SMS]  %-20s → HARD_FAIL (21610: opted out)%n",
                    notification.getRequest().getType());
            return DispatchResult.hardFail("21610", "Recipient has opted out of SMS notifications");
        }

        System.out.printf("  [SMS]  %-20s → SOFT_FAIL (30001: carrier queue overflow)%n",
                notification.getRequest().getType());
        return DispatchResult.softFail("30001", "Carrier queue overflow; will retry");
    }
}
