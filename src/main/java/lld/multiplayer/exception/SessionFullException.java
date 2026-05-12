package lld.multiplayer.exception;

public class SessionFullException extends RuntimeException {
    public SessionFullException(String sessionId) {
        super("Session is full: " + sessionId);
    }
}
