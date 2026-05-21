package lld.notificationservice;

import lld.notificationservice.model.NotificationStatus;

import java.util.Objects;

/** Returned by {@link NotificationService#send} to the producer. */
public final class NotificationResponse {

    public enum Outcome { ACCEPTED, DUPLICATE, HELD, DROPPED }

    private final String notificationId;
    private final NotificationStatus status;
    private final Outcome outcome;
    private final String detail;

    private NotificationResponse(String notificationId, NotificationStatus status,
                                  Outcome outcome, String detail) {
        this.notificationId = Objects.requireNonNull(notificationId);
        this.status         = Objects.requireNonNull(status);
        this.outcome        = Objects.requireNonNull(outcome);
        this.detail         = detail;
    }

    public static NotificationResponse accepted(String notificationId, NotificationStatus status) {
        return new NotificationResponse(notificationId, status, Outcome.ACCEPTED, null);
    }

    public static NotificationResponse duplicate(String existingNotificationId) {
        return new NotificationResponse(existingNotificationId,
                NotificationStatus.DELIVERED, Outcome.DUPLICATE,
                "Duplicate event_id; returning existing notification id");
    }

    public static NotificationResponse held(String notificationId) {
        return new NotificationResponse(notificationId,
                NotificationStatus.HELD, Outcome.HELD,
                "Notification held: user is in DND window");
    }

    public static NotificationResponse dropped(String notificationId, String reason) {
        return new NotificationResponse(notificationId,
                NotificationStatus.DROPPED, Outcome.DROPPED, reason);
    }

    public String getNotificationId()  { return notificationId; }
    public NotificationStatus getStatus() { return status; }
    public Outcome getOutcome()        { return outcome; }
    public String getDetail()          { return detail; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NotificationResponse{id=")
                .append(notificationId, 0, Math.min(8, notificationId.length()))
                .append("..., outcome=").append(outcome)
                .append(", status=").append(status);
        if (detail != null) sb.append(", detail='").append(detail).append("'");
        return sb.append("}").toString();
    }
}
