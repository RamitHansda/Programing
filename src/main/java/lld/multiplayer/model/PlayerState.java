package lld.multiplayer.model;

/**
 * Lifecycle states a player can occupy across the multiplayer system.
 *
 * Allowed transitions:
 *   IDLE  -> IN_MATCHMAKING  (player enqueues)
 *   IN_MATCHMAKING -> IDLE   (player cancels or times out)
 *   IN_MATCHMAKING -> IN_LOBBY (match found, awaiting session start)
 *   IN_LOBBY -> IN_SESSION    (session transitions to ACTIVE)
 *   IN_SESSION -> DISCONNECTED (network loss during active session)
 *   DISCONNECTED -> IN_SESSION  (successful reconnect within grace period)
 *   DISCONNECTED -> IDLE       (grace period expired, session abandoned this player)
 *   IN_SESSION -> IDLE         (player leaves or session ends)
 *   IN_LOBBY -> IDLE           (player leaves lobby before start)
 */
public enum PlayerState {
    IDLE,
    IN_MATCHMAKING,
    IN_LOBBY,
    IN_SESSION,
    DISCONNECTED
}
