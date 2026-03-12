package lld.messagebroker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link IdempotencyStore}.
 *
 * Uses a {@link ConcurrentHashMap} keyed set so that concurrent consumers can safely
 * check-and-mark without locking. Note: in a distributed environment this must be replaced
 * with a shared store (e.g. Redis SETNX) to guarantee cross-instance idempotency.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isProcessed(String messageId) {
        return processedIds.contains(messageId);
    }

    @Override
    public void markProcessed(String messageId) {
        processedIds.add(messageId);
    }
}
