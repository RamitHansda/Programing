package lld.multiplayer.event;

/**
 * Enumeration of all events that the session system can emit.
 *
 * <p>Consumers subscribe via {@link SessionEventListener} and filter by event type to react to
 * specific session lifecycle changes without tight coupling to the session manager.
 */
public enum SessionEventType {

    /** A brand-new session was created (state: WAITING_FOR_PLAYERS). */
    SESSION_CREATED,

    /** A player successfully joined the session. */
    PLAYER_JOINED,

    /** A player voluntarily left the session. */
    PLAYER_LEFT,

    /** The session transitioned from WAITING_FOR_PLAYERS → STARTING. */
    SESSION_STARTING,

    /** The session became ACTIVE (all players ready). */
    SESSION_STARTED,

    /** A player's connection was lost; session may pause. */
    PLAYER_DISCONNECTED,

    /** A disconnected player successfully reconnected within the grace period. */
    PLAYER_RECONNECTED,

    /** The session was paused because a player disconnected. */
    SESSION_PAUSED,

    /** The session resumed after a player reconnected. */
    SESSION_RESUMED,

    /** A {@link lld.multiplayer.model.GameAction} was submitted and accepted. */
    ACTION_SUBMITTED,

    /** The game reached a terminal outcome (win, draw, forfeit). */
    SESSION_FINISHED,

    /** The session was abandoned (TTL expired or unrecoverable disconnect). */
    SESSION_ABANDONED
}
