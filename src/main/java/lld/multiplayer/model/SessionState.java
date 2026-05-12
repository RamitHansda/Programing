package lld.multiplayer.model;

/**
 * Lifecycle states of a multiplayer session.
 *
 * Allowed transitions:
 *   WAITING_FOR_PLAYERS -> STARTING    (minPlayers threshold reached and host starts)
 *   WAITING_FOR_PLAYERS -> ABANDONED   (TTL expired with no activity)
 *   STARTING -> ACTIVE                 (all required players acknowledged ready)
 *   STARTING -> ABANDONED              (startup timed out)
 *   ACTIVE -> PAUSED                   (a player disconnects; grace period opens)
 *   ACTIVE -> FINISHED                 (game reaches a terminal condition)
 *   ACTIVE -> ABANDONED                (session TTL exceeded with no activity)
 *   PAUSED -> ACTIVE                   (disconnected player reconnects within grace period)
 *   PAUSED -> ABANDONED                (grace period expires without reconnect)
 *   FINISHED -> (terminal, no further transitions)
 *   ABANDONED -> (terminal, no further transitions)
 */
public enum SessionState {
    WAITING_FOR_PLAYERS,
    STARTING,
    ACTIVE,
    PAUSED,
    FINISHED,
    ABANDONED
}
