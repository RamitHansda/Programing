package lld.multiplayer.exception;

public class PlayerNotInSessionException extends RuntimeException {
    public PlayerNotInSessionException(String playerId, String sessionId) {
        super("Player '" + playerId + "' is not in session '" + sessionId + "'");
    }
}
