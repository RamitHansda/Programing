package lld.notificationservice.channel;

import lld.notificationservice.model.DeliveryChannel;
import lld.notificationservice.model.DeviceToken;
import lld.notificationservice.model.Notification;
import lld.notificationservice.model.RenderedContent;
import lld.notificationservice.preference.UserPreferences;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Simulated FCM / APNs push dispatcher.
 *
 * <p>Realistic success rate: ~85 %.
 * Hard failures represent stale/invalid device tokens (15 % baseline).
 * Soft failures represent transient server-side throttle (rare on top of that).
 *
 * <p>In a production implementation this would batch up to 500 FCM tokens per multicast
 * request and classify error codes per-token (InvalidRegistration → HARD_FAIL,
 * Unavailable → SOFT_FAIL).
 */
public final class PushDispatcher implements ChannelDispatcher {

    private static final double SUCCESS_RATE   = 0.82;
    private static final double HARD_FAIL_RATE = 0.13; // stale token
    // remaining ~5 % → SOFT_FAIL (transient throttle)

    private final Random rng;

    public PushDispatcher() {
        this(new Random());
    }

    public PushDispatcher(Random rng) {
        this.rng = rng;
    }

    @Override
    public DeliveryChannel getChannel() {
        return DeliveryChannel.PUSH;
    }

    @Override
    public DispatchResult dispatch(Notification notification, UserPreferences prefs, RenderedContent content) {
        List<DeviceToken> tokens = prefs.getActiveDeviceTokens();
        if (tokens.isEmpty()) {
            return DispatchResult.hardFail("NO_DEVICE_TOKEN", "User has no registered device tokens");
        }

        DeviceToken token = tokens.get(0); // pick primary token; real impl would multicast all

        double roll = rng.nextDouble();

        if (roll < SUCCESS_RATE) {
            String msgId = "fcm-" + UUID.randomUUID().toString().substring(0, 8);
            System.out.printf("  [PUSH] %-20s → SENT   token=%s...%n",
                    notification.getRequest().getType(), token.getToken().substring(0, 8));
            return DispatchResult.success(msgId);
        }

        if (roll < SUCCESS_RATE + HARD_FAIL_RATE) {
            token.deactivate(); // mark token stale in the store
            System.out.printf("  [PUSH] %-20s → HARD_FAIL (InvalidRegistration, token deactivated)%n",
                    notification.getRequest().getType());
            return DispatchResult.hardFail("InvalidRegistration",
                    "Device token is no longer valid; token deactivated");
        }

        System.out.printf("  [PUSH] %-20s → SOFT_FAIL (Unavailable, transient throttle)%n",
                notification.getRequest().getType());
        return DispatchResult.softFail("Unavailable", "FCM service temporarily unavailable");
    }
}
