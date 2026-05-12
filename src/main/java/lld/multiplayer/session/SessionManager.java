package lld.multiplayer.session;

import lld.multiplayer.model.GameAction;
import lld.multiplayer.model.Player;
import lld.multiplayer.model.Session;
import lld.multiplayer.model.SessionConfig;

import java.util.Collection;
import java.util.Optional;

/**
 * Primary service interface for managing the full lifecycle of multiplayer sessions.
 *
 * <p>All methods are thread-safe. Implementations publish {@link lld.multiplayer.event.SessionEvent}s
 * for every significant lifecycle transition so downstream components (spectators, analytics,
 * lobby UIs) can react without polling.
 *
 * <h2>Typical usage flow</h2>
 * <pre>{@code
 * // 1. Create
 * Session s = manager.createSession(config, host);
 *
 * // 2. Other players join
 * manager.joinSession(s.getSessionId(), player2);
 *
 * // 3. Host starts when min-players threshold is reached
 * manager.startSession(s.getSessionId());
 *
 * // 4. Players submit actions during the game
 * manager.submitAction(s.getSessionId(), action);
 *
 * // 5. Game logic (above this layer) calls endSession when a terminal state is reached
 * manager.endSession(s.getSessionId());
 * }</pre>
 */
public interface SessionManager {

    /**
     * Creates a new session with the given configuration, immediately adding {@code creator} as
     * the first player.
     *
     * @param config  session parameters; must not be {@code null}
     * @param creator the player who is creating the session; must not be {@code null}
     * @return the newly created session in state {@link lld.multiplayer.model.SessionState#WAITING_FOR_PLAYERS}
     */
    Session createSession(SessionConfig config, Player creator);

    /**
     * Adds {@code player} to an existing session.
     *
     * @param sessionId the target session
     * @param player    the player to add
     * @return the updated session
     * @throws lld.multiplayer.exception.SessionNotFoundException    if no session exists with this ID
     * @throws lld.multiplayer.exception.SessionFullException        if the session has reached {@code maxPlayers}
     * @throws lld.multiplayer.exception.InvalidSessionStateException if the session is not in WAITING_FOR_PLAYERS
     */
    Session joinSession(String sessionId, Player player);

    /**
     * Removes {@code player} from a session. If the session is active and drops below
     * {@code minPlayers}, the session transitions to PAUSED and a reconnect grace timer starts.
     *
     * @throws lld.multiplayer.exception.SessionNotFoundException     if no session exists with this ID
     * @throws lld.multiplayer.exception.PlayerNotInSessionException  if the player is not in this session
     */
    void leaveSession(String sessionId, Player player);

    /**
     * Transitions the session from WAITING_FOR_PLAYERS → STARTING → ACTIVE.
     * Requires at least {@code minPlayers} to be present.
     *
     * @throws lld.multiplayer.exception.InvalidSessionStateException if the session is not in WAITING_FOR_PLAYERS
     * @throws IllegalStateException                                   if fewer than {@code minPlayers} are present
     */
    void startSession(String sessionId);

    /**
     * Marks the session as finished with the given outcome description.
     *
     * @throws lld.multiplayer.exception.InvalidSessionStateException if the session is not in ACTIVE or PAUSED
     */
    void endSession(String sessionId);

    /**
     * Accepts a player action for an active session.
     * Duplicate submissions (same {@link GameAction#getIdempotencyKey()}) are silently ignored.
     *
     * @throws lld.multiplayer.exception.SessionNotFoundException     if no session exists
     * @throws lld.multiplayer.exception.PlayerNotInSessionException  if the acting player is not in this session
     * @throws lld.multiplayer.exception.InvalidSessionStateException if the session is not ACTIVE
     * @throws lld.multiplayer.exception.DuplicateActionException     if the idempotency key was already processed
     */
    void submitAction(String sessionId, GameAction action);

    /**
     * Signals that a player has disconnected unexpectedly. The session is paused if it was
     * active; a reconnect grace-period timer is started for the player.
     *
     * @return the grace-period end time as an epoch-millisecond value
     */
    long notifyDisconnect(String sessionId, String playerId);

    /**
     * Signals that a previously disconnected player is back. If the grace period has not
     * expired, the session resumes.
     *
     * @throws lld.multiplayer.exception.SessionNotFoundException if no session exists
     */
    void notifyReconnect(String sessionId, String playerId);

    /**
     * Retrieves a session by ID.
     *
     * @return an {@link Optional} containing the session, or empty if not found or expired
     */
    Optional<Session> getSession(String sessionId);

    /**
     * Returns all sessions currently tracked by this manager (non-expired).
     */
    Collection<Session> getAllSessions();
}
