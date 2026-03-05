package lld.orderbook;

import java.util.Objects;
import java.util.Optional;

/**
 * <h2>Idempotent Request Handler — gateway-level deduplication (Layer 1)</h2>
 *
 * <p>Sits in front of the {@link OrderEventProcessor} and intercepts
 * {@link OrderCommand.Submit} commands that carry a client-supplied
 * {@code idempotencyKey}.  The two-layer idempotency scheme in the system is:
 *
 * <pre>
 * Layer 1  (this class — gateway level)
 *   idempotencyKey → orderId mapping checked before the command enters the processor.
 *   Duplicate → return HandleResult.Duplicate immediately; command is NOT re-processed.
 *
 * Layer 2  (consumer level — eventId on every outbound event)
 *   Downstream DB consumers upsert by eventId / orderId / tradeId so at-least-once
 *   Kafka delivery does not cause duplicate rows.
 * </pre>
 *
 * <h3>Why a dedicated handler instead of embedding logic in the processor?</h3>
 * <ul>
 *   <li>The processor returns {@code void}; callers (the Kafka consumer loop, HTTP
 *       gateway) need a <em>typed result</em> to decide whether to reply
 *       "accepted" or "already exists" to the client.</li>
 *   <li>Separating the concern makes the processor unit-testable in isolation and
 *       lets this handler evolve independently (e.g. adding rate-limiting or
 *       circuit-breaker logic without touching matching code).</li>
 * </ul>
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>For a {@code Submit} with a non-null key:
 *       <ul>
 *         <li>Fast-path read: {@code store.get(key)}.  If present → {@link HandleResult.Duplicate}.</li>
 *         <li>Otherwise: delegate to the processor (which atomically registers the key
 *             via {@link IdempotencyStore#setIfAbsent} before touching the book).</li>
 *         <li>Return {@link HandleResult.Processed} with the {@code orderId}.</li>
 *       </ul>
 *   </li>
 *   <li>For a {@code Submit} with no key: delegate unconditionally.</li>
 *   <li>For {@code Cancel} / {@code Amend}: pass-through; these commands carry no
 *       idempotency key in the current protocol (they are idempotent by design —
 *       cancelling an already-cancelled order routes to the DLQ, not a duplicate fill).</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>Inherits the single-threaded-per-symbol contract from {@link OrderEventProcessor}.
 * The fast-path {@code store.get()} read is non-mutating and safe to call before the
 * single-threaded processor path.  The authoritative atomic gate is the processor's
 * internal {@link IdempotencyStore#setIfAbsent} call.
 */
public final class IdempotentRequestHandler {

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Typed outcome returned to the caller (Kafka consumer loop / HTTP gateway).
     *
     * <p>Sealed + records give exhaustive pattern-matching in callers and free
     * {@code equals} / {@code hashCode} for tests.
     */
    public sealed interface HandleResult
            permits HandleResult.Processed, HandleResult.Duplicate {

        /**
         * The command was sent to the processor.  The {@code orderId} can be used to
         * correlate the outbound events that will arrive shortly on the events topic.
         * Note: the order may have been <em>rejected</em> (validation failure, FOK miss)
         * — this result only signals that processing was attempted, not that a fill occurred.
         */
        record Processed(String orderId) implements HandleResult {}

        /**
         * A previous submission with the same idempotency key was already processed.
         * The {@code originalOrderId} is the order that was created on the first call;
         * the gateway should return this to the client so they can look up its status.
         * The current command was <em>not</em> forwarded to the processor.
         */
        record Duplicate(String originalOrderId) implements HandleResult {}
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final OrderEventProcessor processor;
    private final IdempotencyStore store;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param processor the downstream processor for this symbol
     * @param store     shared idempotency store (same instance used by the processor)
     */
    public IdempotentRequestHandler(OrderEventProcessor processor, IdempotencyStore store) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.store     = Objects.requireNonNull(store,     "store");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Handle one inbound command with idempotency enforcement.
     *
     * <p>{@link OrderCommand.Submit} commands with a non-null, non-blank
     * {@code idempotencyKey} are subject to deduplication.  All other commands
     * are passed through to the processor directly.
     *
     * <p>This method has the same failure contract as {@link OrderEventProcessor#process}:
     * {@link EventPublisher.PublishException} propagates (offset not committed);
     * validation failures produce an {@link OutboundOrderEvent.OrderRejected} event
     * and return normally.
     *
     * @param command the inbound command (must not be null)
     * @return {@link HandleResult.Processed} when the command was forwarded to the
     *         processor; {@link HandleResult.Duplicate} when the idempotency key
     *         matched a prior submission
     */
    public HandleResult handle(OrderCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        return switch (command) {
            case OrderCommand.Submit s  -> handleSubmit(s);
            case OrderCommand.Cancel c  -> passThrough(c, c.orderId());
            case OrderCommand.Amend  a  -> passThrough(a, a.orderId());
        };
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private HandleResult handleSubmit(OrderCommand.Submit cmd) {
        String key = cmd.idempotencyKey();

        if (key != null && !key.isBlank()) {
            // Fast-path read: if the key already exists, return immediately.
            // The processor's internal setIfAbsent is the authoritative atomic gate;
            // this read is a non-mutating pre-check that avoids queueing a duplicate
            // into the single-threaded processor pipeline.
            Optional<String> existing = store.get(key);
            if (existing.isPresent()) {
                return new HandleResult.Duplicate(existing.get());
            }
        }

        // Delegate to the processor.  The processor will atomically register the
        // idempotency key (setIfAbsent) before touching the order book, so any
        // concurrent duplicate that slips through the fast-path read above will be
        // caught there.
        processor.process(cmd);
        return new HandleResult.Processed(cmd.orderId());
    }

    private HandleResult passThrough(OrderCommand command, String orderId) {
        processor.process(command);
        return new HandleResult.Processed(orderId);
    }
}
