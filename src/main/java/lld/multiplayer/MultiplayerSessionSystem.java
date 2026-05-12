package lld.multiplayer;

import lld.multiplayer.event.SessionEventBus;
import lld.multiplayer.event.SessionEventListener;
import lld.multiplayer.event.SessionEventType;
import lld.multiplayer.lobby.FirstAvailableStrategy;
import lld.multiplayer.lobby.Lobby;
import lld.multiplayer.lobby.MatchmakingStrategy;
import lld.multiplayer.matchmaking.MatchResult;
import lld.multiplayer.matchmaking.Matchmaker;
import lld.multiplayer.model.GameAction;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.session.SessionManager;
import lld.multiplayer.session.SessionManagerImpl;
import lld.multiplayer.store.InMemorySessionStore;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Façade that wires together all subsystems of the multiplayer session layer.
 *
 * <h2>Architecture overview</h2>
 * <pre>
 *  ┌─────────────────────────────────────────────────────────────────────────┐
 *  │                    MultiplayerSessionSystem (façade)                    │
 *  │                                                                         │
 *  │  ┌─────────────────────┐    ┌─────────────────────┐                    │
 *  │  │     Matchmaker      │    │      Lobby (adhoc)  │                    │
 *  │  │  (background tick)  │    │   host-created room │                    │
 *  │  └────────┬────────────┘    └──────────┬──────────┘                    │
 *  │           │  on match found             │  start()                     │
 *  │           ▼                             ▼                               │
 *  │  ┌────────────────────────────────────────────────────────────────┐    │
 *  │  │                     SessionManager                             │    │
 *  │  │  createSession / joinSession / leaveSession / submitAction     │    │
 *  │  │  notifyDisconnect / notifyReconnect / endSession               │    │
 *  │  └──────────────────────────┬─────────────────────────────────────┘    │
 *  │                             │ publishes events                         │
 *  │                             ▼                                          │
 *  │  ┌─────────────────┐   ┌─────────────────────┐                        │
 *  │  │ InMemorySession │   │  SessionEventBus     │──► listeners           │
 *  │  │     Store       │   │  (virtual threads)   │                        │
 *  │  │  (TTL eviction) │   └─────────────────────┘                        │
 *  │  └─────────────────┘                                                   │
 *  └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var system = new MultiplayerSessionSystem();
 *
 * // --- Path A: matchmaking ---
 * system.enqueueForMatchmaking(player, config);   // fires when ≥ minPlayers waiting
 *
 * // --- Path B: private lobby ---
 * Lobby lobby = system.createLobby(hostPlayer, config);
 * system.joinLobby(lobby.getLobbyId(), guestPlayer);
 * MatchResult result = system.startLobbySession(lobby.getLobbyId());
 *
 * // --- In-session ---
 * system.submitAction(sessionId, action);
 * system.notifyDisconnect(sessionId, playerId);
 * system.notifyReconnect(sessionId, playerId);
 * system.endSession(sessionId);
 *
 * // --- Cleanup ---
 * system.shutdown();
 * }</pre>
 */
public class MultiplayerSessionSystem {

    private final SessionEventBus eventBus;
    private final InMemorySessionStore store;
    private final SessionManager sessionManager;
    private final Matchmaker matchmaker;

    // Lobby registry (in a real system this would live in a separate LobbyManager)
    private final java.util.concurrent.ConcurrentHashMap<String, Lobby> lobbies =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MultiplayerSessionSystem() {
        this(new FirstAvailableStrategy(), null, 500);
    }

    public MultiplayerSessionSystem(MatchmakingStrategy strategy,
                                    Consumer<MatchResult> onMatch,
                                    long matchTickMs) {
        this.eventBus = new SessionEventBus();
        this.store = new InMemorySessionStore(
                session -> eventBus.publish(
                        lld.multiplayer.event.SessionEvent.builder(
                                session.getSessionId(),
                                SessionEventType.SESSION_ABANDONED
                        ).payload("reason", "TTL_EVICTION").build()),
                60);
        this.sessionManager = new SessionManagerImpl(store, eventBus);
        this.matchmaker = new Matchmaker(sessionManager, strategy, onMatch, matchTickMs);
    }

    // -----------------------------------------------------------------------
    // Matchmaking path
    // -----------------------------------------------------------------------

    /** Enqueues {@code player} for automatic matchmaking with the given configuration. */
    public void enqueueForMatchmaking(Player player, SessionConfig config) {
        matchmaker.enqueue(player, config);
    }

    /** Removes {@code player} from the matchmaking queue. */
    public void cancelMatchmaking(Player player) {
        matchmaker.dequeue(player);
    }

    /** Returns the number of players currently waiting for the given game type. */
    public int getMatchmakingQueueSize(String gameType) {
        return matchmaker.getQueueSize(gameType);
    }

    // -----------------------------------------------------------------------
    // Private lobby path
    // -----------------------------------------------------------------------

    /**
     * Creates a named lobby that other players can join by ID (e.g. via a shared invite link).
     *
     * @param host   the player who created the lobby; they are added automatically
     * @param config the session configuration to use when the lobby converts to a session
     * @return the newly created lobby
     */
    public Lobby createLobby(Player host, SessionConfig config) {
        Lobby lobby = new Lobby(host.getPlayerId(), config);
        lobby.addPlayer(host);
        lobbies.put(lobby.getLobbyId(), lobby);
        return lobby;
    }

    /**
     * Adds {@code player} to the lobby with the given ID.
     *
     * @return {@code true} if the player was admitted; {@code false} if the lobby is full/closed
     * @throws IllegalArgumentException if no lobby exists with {@code lobbyId}
     */
    public boolean joinLobby(String lobbyId, Player player) {
        Lobby lobby = requireLobby(lobbyId);
        return lobby.addPlayer(player);
    }

    /**
     * Converts the lobby into an active session and starts it.
     *
     * @return a {@link MatchResult} containing the session and the grouped players
     * @throws IllegalStateException if the lobby does not have enough players
     */
    public MatchResult startLobbySession(String lobbyId) {
        Lobby lobby = requireLobby(lobbyId);
        if (!lobby.hasMinimumPlayers()) {
            throw new IllegalStateException(
                    "Lobby " + lobbyId + " needs at least "
                    + lobby.getConfig().getMinPlayers() + " players");
        }

        java.util.List<Player> players = lobby.getWaitingPlayers();
        Player host = players.get(0);
        host.setState(lld.multiplayer.model.PlayerState.IDLE);
        Session session = sessionManager.createSession(lobby.getConfig(), host);

        for (int i = 1; i < players.size(); i++) {
            Player p = players.get(i);
            p.setState(lld.multiplayer.model.PlayerState.IDLE);
            sessionManager.joinSession(session.getSessionId(), p);
        }
        sessionManager.startSession(session.getSessionId());
        lobby.close();
        lobbies.remove(lobbyId);

        return new MatchResult(session, players);
    }

    /**
     * Retrieves a lobby by its ID.
     */
    public Optional<Lobby> getLobby(String lobbyId) {
        return Optional.ofNullable(lobbies.get(lobbyId));
    }

    // -----------------------------------------------------------------------
    // Session operations (pass-through to SessionManager)
    // -----------------------------------------------------------------------

    public Optional<Session> getSession(String sessionId) {
        return sessionManager.getSession(sessionId);
    }

    public Collection<Session> getAllSessions() {
        return sessionManager.getAllSessions();
    }

    public void submitAction(String sessionId, GameAction action) {
        sessionManager.submitAction(sessionId, action);
    }

    public long notifyDisconnect(String sessionId, String playerId) {
        return sessionManager.notifyDisconnect(sessionId, playerId);
    }

    public void notifyReconnect(String sessionId, String playerId) {
        sessionManager.notifyReconnect(sessionId, playerId);
    }

    public void startSession(String sessionId) {
        sessionManager.startSession(sessionId);
    }

    public void endSession(String sessionId) {
        sessionManager.endSession(sessionId);
    }

    // -----------------------------------------------------------------------
    // Event bus access
    // -----------------------------------------------------------------------

    /** Registers a listener that receives every session event. */
    public void subscribe(SessionEventListener listener) {
        eventBus.subscribe(listener);
    }

    /** Registers a listener scoped to a specific event type. */
    public void subscribe(SessionEventType type, SessionEventListener listener) {
        eventBus.subscribe(type, listener);
    }

    public void unsubscribe(SessionEventListener listener) {
        eventBus.unsubscribe(listener);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Shuts down all background threads. Should be called when the system is no longer needed.
     */
    public void shutdown() {
        matchmaker.shutdown();
        store.shutdown();
        eventBus.shutdown();
        if (sessionManager instanceof SessionManagerImpl impl) {
            impl.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Lobby requireLobby(String lobbyId) {
        Lobby lobby = lobbies.get(lobbyId);
        if (lobby == null) {
            throw new IllegalArgumentException("Lobby not found: " + lobbyId);
        }
        return lobby;
    }
}
