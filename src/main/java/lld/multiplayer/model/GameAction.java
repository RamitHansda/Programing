package lld.multiplayer.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single, typed action submitted by a player during an active session.
 *
 * <p>Actions are intentionally schema-agnostic: the {@code actionType} string identifies the
 * kind of move (e.g. {@code "MOVE"}, {@code "CHAT"}, {@code "FORFEIT"}), and {@code payload} is
 * an open key→value map whose keys are defined by the game logic layer above this system.
 *
 * <p>Each action carries a client-supplied {@code idempotencyKey} so the session manager can
 * detect and discard duplicate submissions caused by network retries.
 */
public final class GameAction {

    private final String actionId;
    private final String playerId;
    private final String actionType;
    private final Map<String, Object> payload;
    private final String idempotencyKey;
    private final Instant submittedAt;

    private GameAction(Builder b) {
        this.actionId = UUID.randomUUID().toString();
        this.playerId = b.playerId;
        this.actionType = b.actionType;
        this.payload = Collections.unmodifiableMap(new HashMap<>(b.payload));
        this.idempotencyKey = b.idempotencyKey != null ? b.idempotencyKey : actionId;
        this.submittedAt = Instant.now();
    }

    public String getActionId() { return actionId; }
    public String getPlayerId() { return playerId; }
    public String getActionType() { return actionType; }
    public Map<String, Object> getPayload() { return payload; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getSubmittedAt() { return submittedAt; }

    @Override
    public String toString() {
        return "GameAction{id='" + actionId + "', player='" + playerId
                + "', type='" + actionType + "', payload=" + payload + "}";
    }

    public static Builder builder(String playerId, String actionType) {
        return new Builder(playerId, actionType);
    }

    public static final class Builder {
        private final String playerId;
        private final String actionType;
        private final Map<String, Object> payload = new HashMap<>();
        private String idempotencyKey;

        private Builder(String playerId, String actionType) {
            this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
            this.actionType = Objects.requireNonNull(actionType, "actionType must not be null");
        }

        public Builder payload(String key, Object value) { payload.put(key, value); return this; }
        public Builder idempotencyKey(String key) { this.idempotencyKey = key; return this; }

        public GameAction build() {
            return new GameAction(this);
        }
    }
}
