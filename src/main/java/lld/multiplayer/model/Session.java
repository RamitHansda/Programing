package lld.multiplayer.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The central entity of the multiplayer system.
 *
 * <p>A {@code Session} owns the authoritative list of participating {@link Player}s, the current
 * {@link SessionState}, timestamps, and a generic {@code gameState} slot where the game-specific
 * data model can be stored. All mutating operations are guarded by a
 * {@link ReentrantReadWriteLock} so multiple threads can safely read concurrently while writes
 * are serialised.
 *
 * <p>Lifecycle rules are enforced by {@link lld.multiplayer.session.SessionManagerImpl}; this
 * class is a pure value object plus concurrency primitives.
 */
public class Session {

    private final String sessionId;
    private final SessionConfig config;
    private final Instant createdAt;

    private SessionState state;
    private final List<Player> players;
    private final Set<Player> spectators;
    private final Set<String> processedActionIds;   // idempotency set

    private Instant startedAt;
    private Instant lastActivityAt;
    private Instant pausedAt;

    /** Game-logic-specific state; opaque to the session layer. */
    private Object gameState;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public Session(SessionConfig config) {
        this.sessionId = UUID.randomUUID().toString();
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.state = SessionState.WAITING_FOR_PLAYERS;
        this.players = new ArrayList<>();
        this.spectators = new HashSet<>();
        this.processedActionIds = new HashSet<>();
        this.createdAt = Instant.now();
        this.lastActivityAt = createdAt;
    }

    // -----------------------------------------------------------------------
    // Read-only accessors (no lock needed for primitive/immutable fields)
    // -----------------------------------------------------------------------

    public String getSessionId() { return sessionId; }
    public SessionConfig getConfig() { return config; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getPausedAt() { return pausedAt; }

    public SessionState getState() {
        readLock.lock();
        try { return state; }
        finally { readLock.unlock(); }
    }

    public List<Player> getPlayers() {
        readLock.lock();
        try { return Collections.unmodifiableList(new ArrayList<>(players)); }
        finally { readLock.unlock(); }
    }

    public Set<Player> getSpectators() {
        readLock.lock();
        try { return Collections.unmodifiableSet(new HashSet<>(spectators)); }
        finally { readLock.unlock(); }
    }

    public Optional<Player> findPlayer(String playerId) {
        readLock.lock();
        try {
            return players.stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst();
        } finally { readLock.unlock(); }
    }

    public int getPlayerCount() {
        readLock.lock();
        try { return players.size(); }
        finally { readLock.unlock(); }
    }

    public boolean isFull() {
        readLock.lock();
        try { return players.size() >= config.getMaxPlayers(); }
        finally { readLock.unlock(); }
    }

    public boolean hasMinimumPlayers() {
        readLock.lock();
        try { return players.size() >= config.getMinPlayers(); }
        finally { readLock.unlock(); }
    }

    public Object getGameState() {
        readLock.lock();
        try { return gameState; }
        finally { readLock.unlock(); }
    }

    // -----------------------------------------------------------------------
    // Write operations (all require write lock)
    // -----------------------------------------------------------------------

    /** Adds {@code player} to the session. Returns {@code false} if already present or full. */
    public boolean addPlayer(Player player) {
        writeLock.lock();
        try {
            if (players.size() >= config.getMaxPlayers()) return false;
            if (players.contains(player)) return false;
            players.add(player);
            touch();
            return true;
        } finally { writeLock.unlock(); }
    }

    /** Removes {@code player} from the session. Returns {@code false} if not found. */
    public boolean removePlayer(Player player) {
        writeLock.lock();
        try {
            boolean removed = players.remove(player);
            if (removed) touch();
            return removed;
        } finally { writeLock.unlock(); }
    }

    public boolean addSpectator(Player spectator) {
        writeLock.lock();
        try {
            if (!config.isSpectatorsAllowed()) return false;
            if (spectators.size() >= config.getMaxSpectators()) return false;
            boolean added = spectators.add(spectator);
            if (added) touch();
            return added;
        } finally { writeLock.unlock(); }
    }

    public void setState(SessionState next) {
        writeLock.lock();
        try {
            this.state = next;
            touch();
        } finally { writeLock.unlock(); }
    }

    public void setStartedAt(Instant ts) {
        writeLock.lock();
        try { this.startedAt = ts; }
        finally { writeLock.unlock(); }
    }

    public void setPausedAt(Instant ts) {
        writeLock.lock();
        try { this.pausedAt = ts; }
        finally { writeLock.unlock(); }
    }

    public void setGameState(Object gameState) {
        writeLock.lock();
        try { this.gameState = gameState; touch(); }
        finally { writeLock.unlock(); }
    }

    /**
     * Records an action ID for idempotency. Returns {@code true} if this is the first time
     * this ID has been seen (action should be processed); {@code false} if it is a duplicate.
     */
    public boolean recordActionIdIfAbsent(String actionId) {
        writeLock.lock();
        try { return processedActionIds.add(actionId); }
        finally { writeLock.unlock(); }
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Returns true if the session is in a terminal state and can be garbage-collected. */
    public boolean isTerminal() {
        SessionState s = getState();
        return s == SessionState.FINISHED || s == SessionState.ABANDONED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Session other)) return false;
        return sessionId.equals(other.sessionId);
    }

    @Override
    public int hashCode() { return sessionId.hashCode(); }

    @Override
    public String toString() {
        return "Session{id='" + sessionId + "', state=" + getState()
                + ", players=" + getPlayerCount() + "/" + config.getMaxPlayers() + "}";
    }
}
