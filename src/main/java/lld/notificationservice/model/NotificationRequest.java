package lld.notificationservice.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inbound notification request submitted by a producer service.
 *
 * <p>Producers must supply a stable {@code eventId} per logical event so the service can
 * detect and absorb duplicate submissions (at-least-once producer semantics).
 */
public final class NotificationRequest {

    private final String producerId;
    private final String eventId;          // producer-scoped idempotency key
    private final String userId;
    private final String type;             // e.g. "order_confirmed", "otp", "price_alert"
    private final NotificationPriority priority;
    private final Map<String, Object> payload;   // template variable bag
    private final List<DeliveryChannel> preferredChannels; // optional override; null = use user prefs
    private final boolean forceDelivery;   // true: bypass DND + frequency cap

    private NotificationRequest(Builder b) {
        this.producerId       = Objects.requireNonNull(b.producerId,  "producerId");
        this.eventId          = Objects.requireNonNull(b.eventId,     "eventId");
        this.userId           = Objects.requireNonNull(b.userId,      "userId");
        this.type             = Objects.requireNonNull(b.type,        "type");
        this.priority         = b.priority != null ? b.priority : NotificationPriority.NORMAL;
        this.payload          = b.payload != null ? Map.copyOf(b.payload) : Map.of();
        this.preferredChannels = b.preferredChannels != null
                ? List.copyOf(b.preferredChannels) : null;
        this.forceDelivery    = b.forceDelivery;
    }

    public String getProducerId()               { return producerId; }
    public String getEventId()                  { return eventId; }
    public String getUserId()                   { return userId; }
    public String getType()                     { return type; }
    public NotificationPriority getPriority()   { return priority; }
    public Map<String, Object> getPayload()     { return payload; }
    public List<DeliveryChannel> getPreferredChannels() {
        return preferredChannels != null ? preferredChannels : Collections.emptyList();
    }
    public boolean hasPreferredChannels()       { return preferredChannels != null && !preferredChannels.isEmpty(); }
    public boolean isForceDelivery()            { return forceDelivery; }

    @Override
    public String toString() {
        return "NotificationRequest{producerId='" + producerId + "', eventId='" + eventId
                + "', userId='" + userId + "', type='" + type + "', priority=" + priority + "}";
    }

    public static Builder builder(String producerId, String eventId, String userId, String type) {
        return new Builder(producerId, eventId, userId, type);
    }

    public static final class Builder {
        private final String producerId;
        private final String eventId;
        private final String userId;
        private final String type;
        private NotificationPriority priority;
        private Map<String, Object> payload;
        private List<DeliveryChannel> preferredChannels;
        private boolean forceDelivery;

        private Builder(String producerId, String eventId, String userId, String type) {
            this.producerId = producerId;
            this.eventId    = eventId;
            this.userId     = userId;
            this.type       = type;
        }

        public Builder priority(NotificationPriority p)             { this.priority = p;  return this; }
        public Builder payload(Map<String, Object> p)               { this.payload = p;   return this; }
        public Builder preferredChannels(List<DeliveryChannel> c)   { this.preferredChannels = c; return this; }
        public Builder forceDelivery(boolean f)                     { this.forceDelivery = f; return this; }

        public NotificationRequest build() { return new NotificationRequest(this); }
    }
}
