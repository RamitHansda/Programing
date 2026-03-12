package lld.messagebroker;

/**
 * Tracks which message IDs have already been successfully processed.
 *
 * <p>Because a reliable queue delivers at-least-once, a consumer may receive the same
 * message more than once (e.g. after a crash or visibility-timeout re-delivery).
 * Before processing, the consumer checks this store; if the ID is already present it
 * simply ack's and skips, keeping the operation idempotent.
 *
 * <p>In production this would be backed by Redis, a database, or an atomic conditional write
 * so that the check + mark is itself atomic. The in-memory implementation is suitable for
 * single-process demos and tests.
 */
public interface IdempotencyStore {

    /**
     * Returns {@code true} if this message has already been successfully processed.
     */
    boolean isProcessed(String messageId);

    /**
     * Record that the message was successfully processed.
     * Must be called ONLY after the handler succeeds and BEFORE calling {@code ack()}.
     */
    void markProcessed(String messageId);
}
