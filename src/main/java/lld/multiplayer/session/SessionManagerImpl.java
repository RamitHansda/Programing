package lld.multiplayer.session;

import lld.multiplayer.event.SessionEvent;
import lld.multiplayer.event.SessionEventBus;
import lld.multiplayer.event.SessionEventType;
import lld.multiplayer.exception.DuplicateActionException;
import lld.multiplayer.exception.InvalidSessionStateException;
import lld.multiplayer.exception.PlayerNotInSessionException;
import lld.multiplayer.exception.SessionFullException;
import lld.multiplayer.exception.SessionNotFoundException;
import lld.multiplayer.model.GameAction;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.PlayerState;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;
import lld.multiplayer.model.SessionState;
import lld.multiplayer.store.SessionStore;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Full implementation of {@link SessionManager}.
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li>The {@link Session} object itself uses a {@code ReentrantReadWriteLock}; this class
 *       serialises state-transition decisions via the session's write lock (accessed through
 *       {@code Session} methods).</li>
 *   <li>Per-player reconnect timers are tracked in {@link #reconnectTimers}, a
 *       {@link ConcurrentHashMap} keyed by {@code sessionId:playerId}. The timers are
 *       managed by a single daemon {@link ScheduledExecutorService}.</li>
 *   <li>Events are published asynchronously via {@link SessionEventBus} so the calling
 *       thread is never blocked by listener code.</li>
 * </ul>
 */
public class SessionManagerImpl implements SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManagerImpl.class.getName());

    private final SessionStore store;
    private final SessionEventBus eventBus;

    /** key = "sessionId:playerId" */
    private final Map<String, ScheduledFuture<?>> reconnectTimers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService timerExecutor;

    public SessionManagerImpl(SessionStore store, SessionEventBus eventBus) {
        this.store = store;
        this.eventBus = eventBus;
        this.timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-reconnect-timer");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // SessionManager API
    // -----------------------------------------------------------------------

    @Override
    public Session createSession(SessionConfig config, Player creator) {
        Session session = new Session(config);
        session.addPlayer(creator);
        creator.setState(PlayerState.IN_LOBBY);
        store.save(session);

        eventBus.publish(SessionEvent.builder(session.getSessionId(), SessionEventType.SESSION_CREATED)
                .payload("creatorId", creator.getPlayerId())
                .build());
        LOG.fine(() -> "Created " + session + " by " + creator.getPlayerId());
        return session;
    }

    @Override
    public Session joinSession(String sessionId, Player player) {
        Session session = requireSession(sessionId);

        SessionState state = session.getState();
        if (state != SessionState.WAITING_FOR_PLAYERS) {
            throw new InvalidSessionStateException(sessionId, state, SessionState.WAITING_FOR_PLAYERS);
        }
        if (session.isFull()) {
            throw new SessionFullException(sessionId);
        }
        session.addPlayer(player);
        player.setState(PlayerState.IN_LOBBY);
        store.save(session);

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.PLAYER_JOINED)
                .payload("playerId", player.getPlayerId())
                .payload("playerCount", session.getPlayerCount())
                .build());
        LOG.fine(() -> player.getPlayerId() + " joined " + sessionId);
        return session;
    }

    @Override
    public void leaveSession(String sessionId, Player player) {
        Session session = requireSession(sessionId);
        requirePlayerInSession(session, player.getPlayerId());

        session.removePlayer(player);
        player.setState(PlayerState.IDLE);

        SessionState currentState = session.getState();

        if (currentState == SessionState.ACTIVE && !session.hasMinimumPlayers()) {
            session.setState(SessionState.PAUSED);
            session.setPausedAt(Instant.now());
            eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_PAUSED)
                    .payload("reason", "PLAYER_LEFT")
                    .payload("playerId", player.getPlayerId())
                    .build());
        }

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.PLAYER_LEFT)
                .payload("playerId", player.getPlayerId())
                .build());

        if (session.getPlayerCount() == 0) {
            abandonSession(session, "ALL_PLAYERS_LEFT");
        } else {
            store.save(session);
        }
    }

    @Override
    public void startSession(String sessionId) {
        Session session = requireSession(sessionId);
        SessionState state = session.getState();

        if (state != SessionState.WAITING_FOR_PLAYERS) {
            throw new InvalidSessionStateException(sessionId, state, SessionState.WAITING_FOR_PLAYERS);
        }
        if (!session.hasMinimumPlayers()) {
            throw new IllegalStateException("Need at least " + session.getConfig().getMinPlayers()
                    + " players to start session " + sessionId);
        }

        session.setState(SessionState.STARTING);
        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_STARTING)
                .payload("playerCount", session.getPlayerCount())
                .build());

        session.setStartedAt(Instant.now());
        session.setState(SessionState.ACTIVE);
        session.getPlayers().forEach(p -> p.setState(PlayerState.IN_SESSION));
        store.save(session);

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_STARTED)
                .payload("startedAt", session.getStartedAt().toEpochMilli())
                .build());
        LOG.fine(() -> "Started session " + sessionId);
    }

    @Override
    public void endSession(String sessionId) {
        Session session = requireSession(sessionId);
        SessionState state = session.getState();

        if (state != SessionState.ACTIVE && state != SessionState.PAUSED) {
            throw new InvalidSessionStateException(sessionId, state,
                    SessionState.ACTIVE, SessionState.PAUSED);
        }

        session.getPlayers().forEach(p -> p.setState(PlayerState.IDLE));
        session.setState(SessionState.FINISHED);
        store.save(session);

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_FINISHED)
                .build());
        LOG.fine(() -> "Finished session " + sessionId);
    }

    @Override
    public void submitAction(String sessionId, GameAction action) {
        Session session = requireSession(sessionId);
        SessionState state = session.getState();

        if (state != SessionState.ACTIVE) {
            throw new InvalidSessionStateException(sessionId, state, SessionState.ACTIVE);
        }
        requirePlayerInSession(session, action.getPlayerId());

        boolean novel = session.recordActionIdIfAbsent(action.getIdempotencyKey());
        if (!novel) {
            throw new DuplicateActionException(action.getIdempotencyKey());
        }

        session.touch();
        store.save(session);

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.ACTION_SUBMITTED)
                .payload("actionId", action.getActionId())
                .payload("playerId", action.getPlayerId())
                .payload("actionType", action.getActionType())
                .payload("payload", action.getPayload())
                .build());
    }

    @Override
    public long notifyDisconnect(String sessionId, String playerId) {
        Session session = requireSession(sessionId);
        requirePlayerInSession(session, playerId);

        Player player = session.findPlayer(playerId).orElseThrow();
        player.setState(PlayerState.DISCONNECTED);

        if (session.getState() == SessionState.ACTIVE) {
            session.setState(SessionState.PAUSED);
            session.setPausedAt(Instant.now());
            eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_PAUSED)
                    .payload("reason", "PLAYER_DISCONNECTED")
                    .payload("playerId", playerId)
                    .build());
        }

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.PLAYER_DISCONNECTED)
                .payload("playerId", playerId)
                .build());

        long graceMillis = session.getConfig().getReconnectGracePeriodSeconds() * 1000L;
        long graceExpiry = System.currentTimeMillis() + graceMillis;

        String timerKey = timerKey(sessionId, playerId);
        cancelTimer(timerKey);

        if (graceMillis > 0) {
            ScheduledFuture<?> future = timerExecutor.schedule(
                    () -> onGraceExpired(sessionId, playerId),
                    graceMillis, TimeUnit.MILLISECONDS);
            reconnectTimers.put(timerKey, future);
        } else {
            onGraceExpired(sessionId, playerId);
        }

        store.save(session);
        return graceExpiry;
    }

    @Override
    public void notifyReconnect(String sessionId, String playerId) {
        Session session = requireSession(sessionId);
        requirePlayerInSession(session, playerId);

        cancelTimer(timerKey(sessionId, playerId));

        Player player = session.findPlayer(playerId).orElseThrow();
        player.setState(PlayerState.IN_SESSION);
        player.touchLastSeen();

        eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.PLAYER_RECONNECTED)
                .payload("playerId", playerId)
                .build());

        if (session.getState() == SessionState.PAUSED && allPlayersOnline(session)) {
            session.setState(SessionState.ACTIVE);
            store.save(session);
            eventBus.publish(SessionEvent.builder(sessionId, SessionEventType.SESSION_RESUMED)
                    .payload("triggeredBy", playerId)
                    .build());
        } else {
            store.save(session);
        }

        LOG.fine(() -> playerId + " reconnected to " + sessionId);
    }

    @Override
    public Optional<Session> getSession(String sessionId) {
        return store.findById(sessionId);
    }

    @Override
    public Collection<Session> getAllSessions() {
        return store.findAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Session requireSession(String sessionId) {
        return store.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private void requirePlayerInSession(Session session, String playerId) {
        if (session.findPlayer(playerId).isEmpty()) {
            throw new PlayerNotInSessionException(playerId, session.getSessionId());
        }
    }

    private boolean allPlayersOnline(Session session) {
        return session.getPlayers().stream()
                .noneMatch(p -> p.getState() == PlayerState.DISCONNECTED);
    }

    private void onGraceExpired(String sessionId, String playerId) {
        reconnectTimers.remove(timerKey(sessionId, playerId));
        Optional<Session> opt = store.findById(sessionId);
        if (opt.isEmpty()) return;
        Session session = opt.get();
        if (session.isTerminal()) return;

        Optional<Player> playerOpt = session.findPlayer(playerId);
        if (playerOpt.isEmpty()) return;

        Player player = playerOpt.get();
        if (player.getState() != PlayerState.DISCONNECTED) return;

        LOG.fine(() -> "Grace period expired for " + playerId + " in " + sessionId + "; abandoning");
        abandonSession(session, "RECONNECT_GRACE_EXPIRED");
    }

    private void abandonSession(Session session, String reason) {
        session.getPlayers().forEach(p -> p.setState(PlayerState.IDLE));
        session.setState(SessionState.ABANDONED);
        store.save(session);
        eventBus.publish(SessionEvent.builder(session.getSessionId(), SessionEventType.SESSION_ABANDONED)
                .payload("reason", reason)
                .build());
    }

    private String timerKey(String sessionId, String playerId) {
        return sessionId + ":" + playerId;
    }

    private void cancelTimer(String key) {
        ScheduledFuture<?> existing = reconnectTimers.remove(key);
        if (existing != null) existing.cancel(false);
    }

    /** Shuts down background timers. */
    public void shutdown() {
        timerExecutor.shutdown();
    }
}
