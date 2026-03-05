package lld.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IdempotentRequestHandler} and the TTL-aware
 * {@link InMemoryIdempotencyStore}.
 *
 * <p><b>Design principles demonstrated:</b>
 * <ul>
 *   <li>No mocking framework — all dependencies are replaced with simple
 *       in-process test doubles.</li>
 *   <li>The {@link java.time.Clock} is injected as a mutable field so TTL
 *       expiry can be exercised without {@code Thread.sleep}.</li>
 *   <li>Each nested class maps to one clearly-named behaviour contract.</li>
 * </ul>
 */
class IdempotentRequestHandlerTest {

    private static final String SYMBOL = "BTC-USD";
    private static final String USER_A = "user-alice";
    private static final String USER_B = "user-bob";

    // Mutable clock — advance it in tests to simulate TTL expiry without sleeping.
    private MutableClock clock;
    private InMemoryIdempotencyStore store;
    private CapturingPublisher publisher;
    private OrderEventProcessor processor;
    private IdempotentRequestHandler handler;

    @BeforeEach
    void setUp() {
        clock     = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        store     = new InMemoryIdempotencyStore(Duration.ofHours(24), clock);
        publisher = new CapturingPublisher();
        processor = new OrderEventProcessor(SYMBOL,
                new MatchingEngine(new OrderBook()), publisher, store);
        handler   = new IdempotentRequestHandler(processor, store);
    }

    // =========================================================================
    // Submit — first-time and duplicate
    // =========================================================================

    @Nested
    @DisplayName("Submit — first-time and duplicate detection")
    class SubmitDeduplication {

        @Test
        @DisplayName("First submit with idempotency key returns Processed")
        void firstSubmitReturnsProcessed() {
            var result = handler.handle(submitWithKey("o1", USER_A, "idem-1"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, result);
            var processed = (IdempotentRequestHandler.HandleResult.Processed) result;
            assertEquals("o1", processed.orderId());
        }

        @Test
        @DisplayName("First submit is forwarded to processor — events are emitted")
        void firstSubmitForwardedToProcessor() {
            handler.handle(submitWithKey("o1", USER_A, "idem-1"));

            assertFalse(publisher.events().isEmpty(), "processor must have published events");
            assertInstanceOf(OutboundOrderEvent.OrderAccepted.class, publisher.events().get(0));
        }

        @Test
        @DisplayName("Duplicate key returns Duplicate with the original orderId")
        void duplicateKeyReturnsDuplicate() {
            handler.handle(submitWithKey("o1", USER_A, "idem-1"));
            publisher.clear();

            var result = handler.handle(submitWithKey("o1-retry", USER_A, "idem-1"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Duplicate.class, result);
            var dup = (IdempotentRequestHandler.HandleResult.Duplicate) result;
            assertEquals("o1", dup.originalOrderId(),
                    "must return the orderId from the *first* submission");
        }

        @Test
        @DisplayName("Duplicate submit does NOT forward the command — no additional events")
        void duplicateSubmitNoEvents() {
            handler.handle(submitWithKey("o1", USER_A, "idem-1"));
            int eventsAfterFirst = publisher.events().size();

            handler.handle(submitWithKey("o1", USER_A, "idem-1"));

            assertEquals(eventsAfterFirst, publisher.events().size(),
                    "duplicate must not emit any new events");
        }

        @Test
        @DisplayName("Different idempotency keys are independent — both produce events")
        void differentKeysAreIndependent() {
            handler.handle(submitWithKey("o1", USER_A, "key-A"));
            handler.handle(submitWithKey("o2", USER_A, "key-B"));

            var accepted = publisher.allOfType(OutboundOrderEvent.OrderAccepted.class);
            assertEquals(2, accepted.size());
        }

        @Test
        @DisplayName("Submit with null idempotency key always processes")
        void nullKeyAlwaysProcesses() {
            var cmd = submit("o1", USER_A, null);
            var r1 = handler.handle(cmd);
            var r2 = handler.handle(cmd);

            // Both calls should be Processed (no dedup without a key)
            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, r1);
            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, r2);
        }

        @Test
        @DisplayName("Submit with blank idempotency key always processes (blank treated as absent)")
        void blankKeyAlwaysProcesses() {
            var r1 = handler.handle(submit("o1", USER_A, "  "));
            var r2 = handler.handle(submit("o2", USER_A, "  "));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, r1);
            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, r2);
        }
    }

    // =========================================================================
    // Cancel and Amend pass-through
    // =========================================================================

    @Nested
    @DisplayName("Cancel and Amend — pass-through, no deduplication")
    class PassThrough {

        @Test
        @DisplayName("Cancel returns Processed with the orderId")
        void cancelReturnsProcessed() {
            handler.handle(submitWithKey("o1", USER_A, "key-1"));
            publisher.clear();

            var result = handler.handle(cancel("o1"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, result);
            assertEquals("o1",
                    ((IdempotentRequestHandler.HandleResult.Processed) result).orderId());
        }

        @Test
        @DisplayName("Cancel is forwarded to processor — OrderCancelled event emitted")
        void cancelForwardedToProcessor() {
            handler.handle(submitWithKey("o1", USER_A, "key-1"));
            publisher.clear();

            handler.handle(cancel("o1"));

            assertFalse(publisher.allOfType(OutboundOrderEvent.OrderCancelled.class).isEmpty());
        }

        @Test
        @DisplayName("Amend returns Processed with the orderId")
        void amendReturnsProcessed() {
            handler.handle(submitWithKey("o1", USER_A, "key-1"));
            publisher.clear();

            var result = handler.handle(amend("o1", "98", "5"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, result);
            assertEquals("o1",
                    ((IdempotentRequestHandler.HandleResult.Processed) result).orderId());
        }
    }

    // =========================================================================
    // TTL expiry in InMemoryIdempotencyStore
    // =========================================================================

    @Nested
    @DisplayName("TTL expiry — expired keys are treated as absent")
    class TtlExpiry {

        @Test
        @DisplayName("Key registered before TTL window: second call is duplicate")
        void withinTtlIsDuplicate() {
            handler.handle(submitWithKey("o1", USER_A, "idem-x"));

            // Advance clock by 23 h 59 min — still within 24-h TTL
            clock.advance(Duration.ofHours(23).plusMinutes(59));

            var result = handler.handle(submitWithKey("o2", USER_A, "idem-x"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Duplicate.class, result);
        }

        @Test
        @DisplayName("Key registered, then TTL elapses: second call is processed as new")
        void afterTtlTreatedAsNew() {
            handler.handle(submitWithKey("o1", USER_A, "idem-x"));

            // Advance clock past the 24-h TTL
            clock.advance(Duration.ofHours(25));

            var result = handler.handle(submitWithKey("o2", USER_A, "idem-x"));

            assertInstanceOf(IdempotentRequestHandler.HandleResult.Processed.class, result,
                    "expired key must be treated as absent — new submission must go through");
        }

        @Test
        @DisplayName("evictExpired() removes only expired entries")
        void evictExpiredRemovesOnlyExpired() {
            store.setIfAbsent("key-old", "order-old");  // registered at T=0
            clock.advance(Duration.ofHours(25));         // advance past TTL

            store.setIfAbsent("key-new", "order-new");  // registered at T+25h (fresh TTL)

            int evicted = store.evictExpired();

            assertEquals(1, evicted,            "only the expired entry must be removed");
            assertEquals(1, store.size(),       "key-new must still be present");
            assertTrue(store.get("key-new").isPresent());
            assertTrue(store.get("key-old").isEmpty(), "expired key must be gone");
        }

        @Test
        @DisplayName("Store with ZERO TTL keeps keys forever")
        void zeroTtlNeverExpires() {
            var noTtlStore = new InMemoryIdempotencyStore(Duration.ZERO, clock);
            noTtlStore.setIfAbsent("idem", "o1");

            clock.advance(Duration.ofDays(365));  // advance a full year

            assertTrue(noTtlStore.get("idem").isPresent(),
                    "zero-TTL store must keep keys indefinitely");
        }
    }

    // =========================================================================
    // Store behaviour — concurrent safety (single-threaded model — basic contract)
    // =========================================================================

    @Nested
    @DisplayName("IdempotencyStore — atomic setIfAbsent semantics")
    class StoreAtomics {

        @Test
        @DisplayName("setIfAbsent returns true on first call, false on second")
        void setIfAbsentFirstTrueSecondFalse() {
            assertTrue(store.setIfAbsent("k", "o1"));
            assertFalse(store.setIfAbsent("k", "o1-retry"));
        }

        @Test
        @DisplayName("get returns the value from the first setIfAbsent, not the second")
        void getReturnsFirstValue() {
            store.setIfAbsent("k", "o1");
            store.setIfAbsent("k", "o1-retry");

            assertEquals("o1", store.get("k").orElseThrow());
        }

        @Test
        @DisplayName("setIfAbsent on blank key throws IllegalArgumentException")
        void blankKeyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.setIfAbsent("   ", "o1"));
        }

        @Test
        @DisplayName("setIfAbsent on null key throws IllegalArgumentException")
        void nullKeyThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> store.setIfAbsent(null, "o1"));
        }
    }

    // =========================================================================
    // Failure contract — PublishException propagates
    // =========================================================================

    @Nested
    @DisplayName("Failure contract — PublishException propagates to caller")
    class FailureContract {

        @Test
        @DisplayName("PublishException from processor propagates through handler")
        void publishExceptionPropagates() {
            var explodingPublisher = new EventPublisher() {
                @Override
                public void publish(OutboundOrderEvent event) {
                    throw new EventPublisher.PublishException("Kafka unavailable",
                            new RuntimeException("timeout"));
                }
                @Override
                public void publishToDlq(OrderCommand command, Throwable cause) {}
            };

            var failingProcessor = new OrderEventProcessor(
                    SYMBOL, new MatchingEngine(new OrderBook()),
                    explodingPublisher, store);
            var failingHandler = new IdempotentRequestHandler(failingProcessor, store);

            assertThrows(EventPublisher.PublishException.class,
                    () -> failingHandler.handle(submitWithKey("o1", USER_A, "idem-fail")),
                    "PublishException must not be swallowed — offset must not be committed");
        }
    }

    // =========================================================================
    // Command factory helpers
    // =========================================================================

    private OrderCommand.Submit submitWithKey(String orderId, String userId, String key) {
        return new OrderCommand.Submit(
                orderId, userId, SYMBOL, Side.ASK,
                new BigDecimal("100"), new BigDecimal("5"),
                OrderType.LIMIT, key, System.nanoTime());
    }

    private OrderCommand.Submit submit(String orderId, String userId, String key) {
        return new OrderCommand.Submit(
                orderId, userId, SYMBOL, Side.ASK,
                new BigDecimal("100"), new BigDecimal("5"),
                OrderType.LIMIT, key, System.nanoTime());
    }

    private static OrderCommand.Cancel cancel(String orderId) {
        return new OrderCommand.Cancel(orderId, SYMBOL, "requester", System.nanoTime());
    }

    private static OrderCommand.Amend amend(String orderId, String price, String qty) {
        return new OrderCommand.Amend(orderId, SYMBOL,
                new BigDecimal(price), new BigDecimal(qty), System.nanoTime());
    }

    // =========================================================================
    // Test doubles
    // =========================================================================

    /**
     * A {@link Clock} whose current instant can be advanced in tests.
     * Avoids any use of {@code Thread.sleep} in TTL tests.
     */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant initial) { this.now = initial; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone()              { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z){ return this; }
        @Override public Instant instant()                 { return now; }
    }

    /** In-memory {@link EventPublisher} that records published events. */
    static class CapturingPublisher implements EventPublisher {

        private final List<OutboundOrderEvent> published = new ArrayList<>();
        private final AtomicInteger dlqCounter = new AtomicInteger();

        @Override public void publish(OutboundOrderEvent event) { published.add(event); }
        @Override public void publishToDlq(OrderCommand cmd, Throwable cause) { dlqCounter.incrementAndGet(); }

        List<OutboundOrderEvent> events() { return List.copyOf(published); }
        void clear() { published.clear(); }
        int dlqCount() { return dlqCounter.get(); }

        <T extends OutboundOrderEvent> List<T> allOfType(Class<T> type) {
            return published.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
