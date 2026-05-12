package lld.multiplayer.exception;

public class DuplicateActionException extends RuntimeException {
    public DuplicateActionException(String idempotencyKey) {
        super("Action with idempotency key '" + idempotencyKey + "' has already been processed");
    }
}
