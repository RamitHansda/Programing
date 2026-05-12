package lld.multiplayer.lobby;

/**
 * State of a lobby (pre-session waiting room).
 *
 * <pre>
 *   OPEN  -> FULL      (maxPlayers reached)
 *   OPEN  -> CLOSED    (host closes / TTL exceeded)
 *   FULL  -> OPEN      (a player leaves)
 *   FULL  -> CLOSED    (host starts the session)
 *   CLOSED -> (terminal)
 * </pre>
 */
public enum LobbyState {
    OPEN,
    FULL,
    CLOSED
}
