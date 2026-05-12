package lld.multiplayer.exception;

import lld.multiplayer.model.SessionState;

public class InvalidSessionStateException extends RuntimeException {
    public InvalidSessionStateException(String sessionId, SessionState actual, SessionState... expected) {
        super("Session '" + sessionId + "' is in state " + actual
                + "; expected one of " + java.util.Arrays.toString(expected));
    }
}
