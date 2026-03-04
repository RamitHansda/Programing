package lld.orderbook;

import java.util.Optional;

/**
 * Deduplication store for idempotency keys supplied by order-submitting clients.
 *
 * <p><b>Problem:</b> A client may retry an {@code OrderCommand.Submit} after a network
 * timeout.  Without deduplication the matching engine would create two orders from
 * the same intent.
 *
 * <p><b>Protocol:</b>
 * <ol>
 *   <li>Client picks an idempotency key (e.g. a UUID it generates once per order intent).</li>
 *   <li>Gateway stamps the key on the {@code OrderCommand.Submit}.</li>
 *   <li>Processor calls {@link #setIfAbsent} before forwarding to the engine.</li>
 *   <li>On a retry with the same key, {@link #setIfAbsent} returns {@code false}; the
 *       processor skips processing and may return the original orderId to the client.</li>
 * </ol>
 *
 * <p><b>TTL:</b> keys should expire after a configurable window (e.g. 24 hours) to
 * bound memory growth.  In production this is backed by Redis with a TTL per key.
 */
public interface IdempotencyStore {

    /**
     * Attempts to register the mapping {@code idempotencyKey → orderId}.
     *
     * @param idempotencyKey the client-supplied dedup key (must be non-null, non-blank)
     * @param orderId        the gateway-assigned order ID for this submission
     * @return {@code true} if the key was absent and has been registered (proceed with
     *         processing); {@code false} if the key already existed (duplicate — skip)
     */
    boolean setIfAbsent(String idempotencyKey, String orderId);

    /**
     * Returns the orderId previously registered for this key, if any.
     * Useful for returning the original orderId to a retrying client.
     */
    Optional<String> get(String idempotencyKey);
}
