package lld.multiplayer.event;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object carrying all information about a single session-level occurrence.
 *
 * <p>The {@code payload} map allows attaching arbitrary context (e.g. the player ID who triggered
 * the event, action details, disconnect reason) without bloating the type hierarchy.
 */
public final class SessionEvent {

    private final String sessionId;
    private final SessionEventType type;
    private final Map<String, Object> payload;
    private final Instant occurredAt;

    private SessionEvent(Builder b) {
        this.sessionId = Objects.requireNonNull(b.sessionId, "sessionId must not be null");
        this.type = Objects.requireNonNull(b.type, "type must not be null");
        this.payload = Collections.unmodifiableMap(new HashMap<>(b.payload));
        this.occurredAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public SessionEventType getType() { return type; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }

    @SuppressWarnings("unchecked")
    public <T> T getPayloadValue(String key) {
        return (T) payload.get(key);
    }

    @Override
    public String toString() {
        return "SessionEvent{session='" + sessionId + "', type=" + type
                + ", payload=" + payload + "}";
    }

    public static Builder builder(String sessionId, SessionEventType type) {
        return new Builder(sessionId, type);
    }

    public static final class Builder {
        private final String sessionId;
        private final SessionEventType type;
        private final Map<String, Object> payload = new HashMap<>();

        private Builder(String sessionId, SessionEventType type) {
            this.sessionId = sessionId;
            this.type = type;
        }

        public Builder payload(String key, Object value) { payload.put(key, value); return this; }

        public SessionEvent build() { return new SessionEvent(this); }
    }
}
