package lld.notificationservice.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Internal enriched representation of a notification.
 * Created from a {@link NotificationRequest} after idempotency check and user-preference
 * enrichment. Mutable status is updated as delivery progresses.
 */
public final class Notification {

    private final String id;
    private final NotificationRequest request;
    private final Instant createdAt;
    private List<DeliveryChannel> channelOrder;
    private volatile NotificationStatus status;

    public Notification(NotificationRequest request) {
        this.id        = UUID.randomUUID().toString();
        this.request   = request;
        this.createdAt = Instant.now();
        this.status    = NotificationStatus.PENDING;
    }

    public String getId()                             { return id; }
    public NotificationRequest getRequest()           { return request; }
    public Instant getCreatedAt()                     { return createdAt; }
    public List<DeliveryChannel> getChannelOrder()    { return channelOrder; }
    public NotificationStatus getStatus()             { return status; }

    public void setChannelOrder(List<DeliveryChannel> order) { this.channelOrder = List.copyOf(order); }
    public void setStatus(NotificationStatus status)         { this.status = status; }

    @Override
    public String toString() {
        return "Notification{id=" + id.substring(0, 8) + "..., type='"
                + request.getType() + "', status=" + status + "}";
    }
}
