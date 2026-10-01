package lld.notificationservice.model;

import lld.notificationservice.channel.DispatchResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single delivery attempt for one channel.
 * Written to the {@link lld.notificationservice.delivery.DeliveryStore} after each dispatch call.
 */
public final class DeliveryAttempt {

    private final String id;
    private final String notificationId;
    private final String userId;
    private final DeliveryChannel channel;
    private final int attemptNumber;
    private final DispatchResult result;
    private final Instant attemptedAt;

    public DeliveryAttempt(String notificationId, String userId,
                           DeliveryChannel channel, int attemptNumber,
                           DispatchResult result) {
        this.id             = UUID.randomUUID().toString();
        this.notificationId = notificationId;
        this.userId         = userId;
        this.channel        = channel;
        this.attemptNumber  = attemptNumber;
        this.result         = result;
        this.attemptedAt    = Instant.now();
    }

    public String getId()               { return id; }
    public String getNotificationId()   { return notificationId; }
    public String getUserId()           { return userId; }
    public DeliveryChannel getChannel() { return channel; }
    public int getAttemptNumber()       { return attemptNumber; }
    public DispatchResult getResult()   { return result; }
    public Instant getAttemptedAt()     { return attemptedAt; }

    @Override
    public String toString() {
        return "DeliveryAttempt{notifId=" + notificationId.substring(0, 8)
                + "..., channel=" + channel
                + ", attempt=" + attemptNumber
                + ", result=" + result.getFailureType()
                + ", errorCode=" + result.getErrorCode() + "}";
    }
}
