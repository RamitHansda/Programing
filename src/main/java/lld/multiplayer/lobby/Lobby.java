package lld.multiplayer.lobby;

import lld.multiplayer.model.Player;
import lld.multiplayer.model.SessionConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A waiting room that holds {@link Player}s before a session is started.
 *
 * <p>A lobby is associated with exactly one {@link SessionConfig} that will be used to create
 * the session once enough players have gathered. When the lobby reaches its maximum capacity
 * it transitions automatically to {@link LobbyState#FULL}.
 *
 * <p>All mutating operations are guarded by a single {@link ReentrantLock}.
 */
public class Lobby {

    private final String lobbyId;
    private final String hostPlayerId;
    private final SessionConfig config;
    private final List<Player> waitingPlayers;
    private final Instant createdAt;

    private LobbyState state;

    private final ReentrantLock lock = new ReentrantLock();

    public Lobby(String hostPlayerId, SessionConfig config) {
        this.lobbyId = UUID.randomUUID().toString();
        this.hostPlayerId = Objects.requireNonNull(hostPlayerId, "hostPlayerId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.waitingPlayers = new ArrayList<>();
        this.state = LobbyState.OPEN;
        this.createdAt = Instant.now();
    }

    public String getLobbyId() { return lobbyId; }
    public String getHostPlayerId() { return hostPlayerId; }
    public SessionConfig getConfig() { return config; }
    public Instant getCreatedAt() { return createdAt; }

    public LobbyState getState() {
        lock.lock();
        try { return state; }
        finally { lock.unlock(); }
    }

    /**
     * Adds {@code player} to the lobby if it is OPEN and not yet at capacity.
     *
     * @return {@code true} if the player was added; {@code false} if the lobby is FULL/CLOSED
     *         or the player is already in it
     */
    public boolean addPlayer(Player player) {
        lock.lock();
        try {
            if (state != LobbyState.OPEN) return false;
            if (waitingPlayers.contains(player)) return false;
            waitingPlayers.add(player);
            if (waitingPlayers.size() >= config.getMaxPlayers()) {
                state = LobbyState.FULL;
            }
            return true;
        } finally { lock.unlock(); }
    }

    /**
     * Removes {@code player} from the lobby.
     *
     * @return {@code true} if the player was removed
     */
    public boolean removePlayer(Player player) {
        lock.lock();
        try {
            boolean removed = waitingPlayers.remove(player);
            if (removed && state == LobbyState.FULL) {
                state = LobbyState.OPEN;
            }
            return removed;
        } finally { lock.unlock(); }
    }

    /**
     * Returns an immutable snapshot of the players currently waiting in this lobby.
     */
    public List<Player> getWaitingPlayers() {
        lock.lock();
        try { return Collections.unmodifiableList(new ArrayList<>(waitingPlayers)); }
        finally { lock.unlock(); }
    }

    public int getPlayerCount() {
        lock.lock();
        try { return waitingPlayers.size(); }
        finally { lock.unlock(); }
    }

    public boolean hasMinimumPlayers() {
        lock.lock();
        try { return waitingPlayers.size() >= config.getMinPlayers(); }
        finally { lock.unlock(); }
    }

    /**
     * Closes the lobby. After this call no new players can join and the lobby is not eligible
     * for further matchmaking rounds.
     */
    public void close() {
        lock.lock();
        try { state = LobbyState.CLOSED; }
        finally { lock.unlock(); }
    }

    @Override
    public String toString() {
        return "Lobby{id='" + lobbyId + "', state=" + getState()
                + ", players=" + getPlayerCount() + "/" + config.getMaxPlayers() + "}";
    }
}
