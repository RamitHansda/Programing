package lld.multiplayer.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a participant in the multiplayer system.
 *
 * <p>A Player is identified by an immutable {@code playerId} (UUID). Display name and
 * arbitrary metadata are mutable so the system can update them without replacing the object.
 * {@link PlayerState} transitions are tracked here so any component can observe a player's
 * current status without coupling to a specific session or lobby.
 *
 * <p>Thread-safety: {@code state} uses an {@link AtomicReference} so it can be read/written
 * from multiple threads without holding a lock. All other mutable fields ({@code lastSeenAt},
 * {@code metadata}) are guarded by {@code synchronized} accessors.
 */
public class Player {

    private final String playerId;
    private final String displayName;
    private final AtomicReference<PlayerState> state;
    private volatile Instant lastSeenAt;
    private final Map<String, String> metadata;

    public Player(String displayName) {
        this(UUID.randomUUID().toString(), displayName);
    }

    public Player(String playerId, String displayName) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.state = new AtomicReference<>(PlayerState.IDLE);
        this.lastSeenAt = Instant.now();
        this.metadata = new HashMap<>();
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PlayerState getState() {
        return state.get();
    }

    /**
     * Atomically transitions the player state from {@code expected} to {@code next}.
     *
     * @return {@code true} if the transition succeeded; {@code false} if the current state
     *         was not {@code expected} (another thread changed it first).
     */
    public boolean compareAndSetState(PlayerState expected, PlayerState next) {
        return state.compareAndSet(expected, next);
    }

    /** Unconditionally overwrites the current state. Prefer {@link #compareAndSetState} where
     *  concurrent modification is possible. */
    public void setState(PlayerState next) {
        state.set(next);
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void touchLastSeen() {
        this.lastSeenAt = Instant.now();
    }

    public synchronized void setMetadata(String key, String value) {
        metadata.put(key, value);
    }

    public synchronized String getMetadata(String key) {
        return metadata.get(key);
    }

    public synchronized Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Player other)) return false;
        return playerId.equals(other.playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }

    @Override
    public String toString() {
        return "Player{id='" + playerId + "', name='" + displayName + "', state=" + state.get() + "}";
    }
}
