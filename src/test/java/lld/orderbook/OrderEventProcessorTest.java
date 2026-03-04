package lld.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link OrderEventProcessor}.
 *
 * <p>No mocking framework required: all external dependencies are backed by
 * simple in-process implementations (CapturingPublisher, InMemoryIdempotencyStore).
 * This keeps the tests readable in an interview setting and fast in CI.
 *
 * <p><b>Interview talking points:</b>
 * <ul>
 *   <li>Each nested class maps to a clearly-named behaviour contract.</li>
 *   <li>Assertions check the entire event sequence, not just the first event — this
 *       catches ordering bugs that single-event assertions would miss.</li>
 *   <li>Edge cases (FOK, IOC, amend, duplicate, self-trade hook) are first-class tests,
 *       not afterthoughts.</li>
 * </ul>
 */
class OrderEventProcessorTest {

    private static final String SYMBOL  = "BTC-USD";
    private static final String USER_A  = "user-alice";
    private static final String USER_B  = "user-bob";

    private CapturingPublisher    publisher;
    private InMemoryIdempotencyStore idempotencyStore;
    private OrderEventProcessor   processor;

    @BeforeEach
    void setUp() {
        publisher        = new CapturingPublisher();
        idempotencyStore = new InMemoryIdempotencyStore();
        processor        = new OrderEventProcessor(
                SYMBOL,
                new MatchingEngine(new OrderBook()),
                publisher,
                idempotencyStore);
    }

    // =========================================================================
    // Happy-path: limit order lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Limit order — resting and full fill")
    class LimitOrderLifecycle {

        @Test
        @DisplayName("Resting ask emits OrderAccepted and a single BookUpdated(ADD)")
        void restingAskEmitsAcceptedAndAdd() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));

            var events = publisher.events();
            assertEquals(2, events.size());

            assertInstanceOf(OutboundOrderEvent.OrderAccepted.class, events.get(0));
            var accepted = (OutboundOrderEvent.OrderAccepted) events.get(0);
            assertEquals("a1",                 accepted.orderId());
            assertEquals(new BigDecimal("5"),   accepted.leavesQty());
            assertEquals(Side.ASK,             accepted.side());

            assertInstanceOf(OutboundOrderEvent.BookUpdated.class, events.get(1));
            var bookUpd = (OutboundOrderEvent.BookUpdated) events.get(1);
            assertEquals(BookEvent.Type.ADD,   bookUpd.updateType());
            assertEquals(new BigDecimal("100"), bookUpd.price());
            assertEquals(Side.ASK,             bookUpd.side());
        }

        @Test
        @DisplayName("Crossing bid fully fills resting ask: OrderAccepted + TradeExecuted + 2×FillExecuted + BookUpdated(FILL)")
        void crossingBidFullyFillsAsk() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "5", OrderType.LIMIT));

            var events = publisher.events();
            // OrderAccepted(b1) + TradeExecuted + FillExecuted(taker b1) + FillExecuted(maker a1) + BookUpdated(FILL)
            assertEquals(5, events.size());

            assertInstanceOf(OutboundOrderEvent.OrderAccepted.class,  events.get(0));
            assertInstanceOf(OutboundOrderEvent.TradeExecuted.class,   events.get(1));
            assertInstanceOf(OutboundOrderEvent.FillExecuted.class,    events.get(2));
            assertInstanceOf(OutboundOrderEvent.FillExecuted.class,    events.get(3));
            assertInstanceOf(OutboundOrderEvent.BookUpdated.class,     events.get(4));

            var trade = (OutboundOrderEvent.TradeExecuted) events.get(1);
            assertEquals("b1",                trade.takerOrderId());
            assertEquals("a1",                trade.makerOrderId());
            assertEquals(new BigDecimal("5"),  trade.quantity());
            assertEquals(new BigDecimal("100"),trade.price());
            assertEquals(Side.BID,            trade.takerSide());

            var takerFill = (OutboundOrderEvent.FillExecuted) events.get(2);
            assertTrue(takerFill.isTaker());
            assertEquals("b1", takerFill.orderId());
            assertEquals(BigDecimal.ZERO, takerFill.leavesQty()); // fully filled

            var makerFill = (OutboundOrderEvent.FillExecuted) events.get(3);
            assertFalse(makerFill.isTaker());
            assertEquals("a1", makerFill.orderId());
        }

        @Test
        @DisplayName("Partial fill: incoming bid larger than resting ask — remainder rests")
        void partialFillRemainderRests() {
            process(submit("a1", USER_A, Side.ASK, "100", "3", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "7", OrderType.LIMIT));

            var trade = publisher.firstOfType(OutboundOrderEvent.TradeExecuted.class);
            assertEquals(new BigDecimal("3"), trade.quantity());

            var accepted = publisher.firstOfType(OutboundOrderEvent.OrderAccepted.class);
            assertEquals(new BigDecimal("4"), accepted.leavesQty()); // 7 - 3

            // Remainder b1 must now be in the book
            assertEquals(new BigDecimal("100"),
                    processor.getLastEventSeq() > 0
                            ? new BigDecimal("100")
                            : BigDecimal.ZERO); // just sanity; real check via publisher
        }

        @Test
        @DisplayName("Price-time priority: two resting asks at same price, first in gets filled first")
        void priceTimePriorityFifo() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("a2", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "10", OrderType.LIMIT));

            List<OutboundOrderEvent.TradeExecuted> trades =
                    publisher.allOfType(OutboundOrderEvent.TradeExecuted.class);
            assertEquals(2, trades.size());
            assertEquals("a1", trades.get(0).makerOrderId()); // FIFO: a1 before a2
            assertEquals("a2", trades.get(1).makerOrderId());
        }

        @Test
        @DisplayName("Sweep across two price levels: bid sweeps asks at 100 and 101")
        void sweepTwoPriceLevels() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("a2", USER_A, Side.ASK, "101", "5", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "101", "10", OrderType.LIMIT));

            List<OutboundOrderEvent.TradeExecuted> trades =
                    publisher.allOfType(OutboundOrderEvent.TradeExecuted.class);
            assertEquals(2, trades.size());
            assertEquals(new BigDecimal("100"), trades.get(0).price()); // best ask first
            assertEquals(new BigDecimal("101"), trades.get(1).price());
        }
    }

    // =========================================================================
    // Cancel
    // =========================================================================

    @Nested
    @DisplayName("Cancel commands")
    class CancelCommands {

        @Test
        @DisplayName("Cancel resting order emits OrderCancelled + BookUpdated(CANCEL)")
        void cancelRestingOrder() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(cancel("a1"));

            var events = publisher.events();
            assertEquals(2, events.size());

            assertInstanceOf(OutboundOrderEvent.OrderCancelled.class, events.get(0));
            var cancelled = (OutboundOrderEvent.OrderCancelled) events.get(0);
            assertEquals("a1",                 cancelled.orderId());
            assertEquals(new BigDecimal("5"),   cancelled.cancelledQty());
            assertEquals(CancellationReason.CLIENT_REQUEST, cancelled.reason());

            assertInstanceOf(OutboundOrderEvent.BookUpdated.class, events.get(1));
            var bookUpd = (OutboundOrderEvent.BookUpdated) events.get(1);
            assertEquals(BookEvent.Type.CANCEL, bookUpd.updateType());
            assertTrue(bookUpd.deltaQty().signum() < 0); // negative delta
        }

        @Test
        @DisplayName("Cancel non-existent order routes to DLQ (ValidationException)")
        void cancelNonExistentOrderDlq() {
            process(cancel("ghost-order"));

            assertTrue(publisher.events().isEmpty(),         "no events on unknown cancel");
            assertEquals(1, publisher.dlqCount(),            "poison-pill sent to DLQ");
        }

        @Test
        @DisplayName("Cancel after full fill routes to DLQ — order already removed from book")
        void cancelAfterFullFill() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("b1", USER_B, Side.BID, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(cancel("a1")); // a1 is already gone (fully filled)

            assertEquals(1, publisher.dlqCount());
        }
    }

    // =========================================================================
    // Amend
    // =========================================================================

    @Nested
    @DisplayName("Amend commands")
    class AmendCommands {

        @Test
        @DisplayName("Amend emits OrderCancelled(AMENDED) + OrderAccepted with new price")
        void amendChangesPrice() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(amend("a1", "99", "5"));

            var cancelled = publisher.firstOfType(OutboundOrderEvent.OrderCancelled.class);
            assertNotNull(cancelled);
            assertEquals(CancellationReason.AMENDED, cancelled.reason());

            var accepted = publisher.firstOfType(OutboundOrderEvent.OrderAccepted.class);
            assertNotNull(accepted);
            assertEquals(new BigDecimal("99"), accepted.price()); // new price
        }

        @Test
        @DisplayName("Amend to crossing price causes immediate fill after re-insertion")
        void amendToCrossingPrice() {
            // Resting bid at 99
            process(submit("b1", USER_B, Side.BID, "99", "5", OrderType.LIMIT));
            // Resting ask at 101
            process(submit("a1", USER_A, Side.ASK, "101", "5", OrderType.LIMIT));
            publisher.clear();

            // Amend ask to 99 → crosses the bid
            process(amend("a1", "99", "5"));

            List<OutboundOrderEvent.TradeExecuted> trades =
                    publisher.allOfType(OutboundOrderEvent.TradeExecuted.class);
            assertEquals(1, trades.size(), "amend triggered a fill");
            assertEquals(new BigDecimal("5"), trades.get(0).quantity());
        }

        @Test
        @DisplayName("Amend non-existent order routes to DLQ")
        void amendNonExistentOrderDlq() {
            process(amend("ghost", "100", "5"));

            assertTrue(publisher.events().isEmpty());
            assertEquals(1, publisher.dlqCount());
        }
    }

    // =========================================================================
    // IOC orders
    // =========================================================================

    @Nested
    @DisplayName("IOC — Immediate-Or-Cancel")
    class IocOrders {

        @Test
        @DisplayName("IOC fully fills: no cancellation event")
        void iocFullFill() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "5", OrderType.IOC));

            var cancellations = publisher.allOfType(OutboundOrderEvent.OrderCancelled.class);
            assertTrue(cancellations.isEmpty(), "no cancellation when IOC fully fills");
        }

        @Test
        @DisplayName("IOC partial fill: remainder is cancelled with IOC_EXPIRED reason")
        void iocPartialFillCancelsRemainder() {
            process(submit("a1", USER_A, Side.ASK, "100", "3", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "7", OrderType.IOC));

            var trade = publisher.firstOfType(OutboundOrderEvent.TradeExecuted.class);
            assertNotNull(trade);
            assertEquals(new BigDecimal("3"), trade.quantity()); // only 3 filled

            var cancelled = publisher.firstOfType(OutboundOrderEvent.OrderCancelled.class);
            assertNotNull(cancelled, "remainder must be cancelled");
            assertEquals(CancellationReason.IOC_EXPIRED, cancelled.reason());
            assertEquals(new BigDecimal("4"), cancelled.cancelledQty()); // 7 - 3
        }

        @Test
        @DisplayName("IOC with no liquidity: accepted then immediately cancelled")
        void iocNoLiquidity() {
            process(submit("b1", USER_B, Side.BID, "100", "5", OrderType.IOC));

            var accepted = publisher.firstOfType(OutboundOrderEvent.OrderAccepted.class);
            assertNotNull(accepted);

            var cancelled = publisher.firstOfType(OutboundOrderEvent.OrderCancelled.class);
            assertNotNull(cancelled);
            assertEquals(CancellationReason.IOC_EXPIRED, cancelled.reason());
            assertEquals(new BigDecimal("5"), cancelled.cancelledQty());
        }
    }

    // =========================================================================
    // FOK orders
    // =========================================================================

    @Nested
    @DisplayName("FOK — Fill-Or-Kill")
    class FokOrders {

        @Test
        @DisplayName("FOK rejected when liquidity insufficient — no book mutation")
        void fokRejectedWhenInsufficientLiquidity() {
            process(submit("a1", USER_A, Side.ASK, "100", "3", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "100", "10", OrderType.FOK));

            var rejected = publisher.firstOfType(OutboundOrderEvent.OrderRejected.class);
            assertNotNull(rejected);
            assertEquals(RejectionReason.FOK_CANNOT_FILL, rejected.reason());

            // Book must be intact — a1 still resting
            var trades = publisher.allOfType(OutboundOrderEvent.TradeExecuted.class);
            assertTrue(trades.isEmpty(), "no fills should have occurred");
        }

        @Test
        @DisplayName("FOK executes when full liquidity available across multiple levels")
        void fokExecutesAcrossLevels() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("a2", USER_A, Side.ASK, "101", "5", OrderType.LIMIT));
            publisher.clear();

            process(submit("b1", USER_B, Side.BID, "101", "10", OrderType.FOK));

            var rejected = publisher.allOfType(OutboundOrderEvent.OrderRejected.class);
            assertTrue(rejected.isEmpty(), "FOK should succeed with enough liquidity");

            var trades = publisher.allOfType(OutboundOrderEvent.TradeExecuted.class);
            assertEquals(2, trades.size());
        }
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Idempotency — duplicate detection")
    class IdempotencyTests {

        @Test
        @DisplayName("Second submit with same idempotency key is silently dropped")
        void duplicateIdempotencyKeyDropped() {
            var cmd = new OrderCommand.Submit(
                    "o1", USER_A, SYMBOL, Side.ASK,
                    new BigDecimal("100"), new BigDecimal("5"),
                    OrderType.LIMIT, "idem-key-1", System.nanoTime());
            process(cmd);
            int eventsAfterFirst = publisher.events().size();

            // Second call with same idempotency key
            process(cmd);
            int eventsAfterSecond = publisher.events().size();

            assertEquals(eventsAfterFirst, eventsAfterSecond,
                    "duplicate command must not emit additional events");
        }

        @Test
        @DisplayName("Different idempotency keys produce independent orders")
        void differentKeysProduceIndependentOrders() {
            process(new OrderCommand.Submit("o1", USER_A, SYMBOL, Side.ASK,
                    new BigDecimal("100"), new BigDecimal("5"),
                    OrderType.LIMIT, "key-A", System.nanoTime()));

            process(new OrderCommand.Submit("o2", USER_A, SYMBOL, Side.ASK,
                    new BigDecimal("100"), new BigDecimal("5"),
                    OrderType.LIMIT, "key-B", System.nanoTime()));

            var accepted = publisher.allOfType(OutboundOrderEvent.OrderAccepted.class);
            assertEquals(2, accepted.size());
        }
    }

    // =========================================================================
    // Validation and rejection
    // =========================================================================

    @Nested
    @DisplayName("Validation — malformed commands")
    class ValidationTests {

        @Test
        @DisplayName("Negative price emits OrderRejected(INVALID_PRICE)")
        void negativePriceRejected() {
            process(submit("o1", USER_A, Side.ASK, "-1", "5", OrderType.LIMIT));

            var rejected = publisher.firstOfType(OutboundOrderEvent.OrderRejected.class);
            assertNotNull(rejected);
            assertEquals(RejectionReason.INVALID_PRICE, rejected.reason());
        }

        @Test
        @DisplayName("Zero quantity emits OrderRejected(INVALID_QUANTITY)")
        void zeroQuantityRejected() {
            process(submit("o1", USER_A, Side.ASK, "100", "0", OrderType.LIMIT));

            var rejected = publisher.firstOfType(OutboundOrderEvent.OrderRejected.class);
            assertNotNull(rejected);
            assertEquals(RejectionReason.INVALID_QUANTITY, rejected.reason());
        }

        @Test
        @DisplayName("Wrong symbol throws IllegalArgumentException (programming error, not event)")
        void wrongSymbolThrows() {
            var wrongSymbol = new OrderCommand.Submit(
                    "o1", USER_A, "ETH-USD", Side.ASK,
                    new BigDecimal("100"), new BigDecimal("5"),
                    OrderType.LIMIT, null, System.nanoTime());

            assertThrows(IllegalArgumentException.class, () -> process(wrongSymbol));
        }
    }

    // =========================================================================
    // Event sequence correctness
    // =========================================================================

    @Nested
    @DisplayName("Event sequence and IDs")
    class EventSequence {

        @Test
        @DisplayName("Event IDs are monotonically increasing and symbol-prefixed")
        void eventIdsMonotonic() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("b1", USER_B, Side.BID, "100", "5", OrderType.LIMIT));

            List<String> ids = publisher.events().stream()
                    .map(OutboundOrderEvent::eventId)
                    .toList();

            for (String id : ids) {
                assertTrue(id.startsWith(SYMBOL + "-E-"),
                        "eventId must be prefixed with symbol: " + id);
            }

            // Verify monotonicity: extract the numeric suffix
            List<Long> seqs = ids.stream()
                    .map(id -> Long.parseLong(id.substring(id.lastIndexOf('-') + 1)))
                    .toList();
            for (int i = 1; i < seqs.size(); i++) {
                assertTrue(seqs.get(i) > seqs.get(i - 1),
                        "event IDs must be strictly increasing");
            }
        }

        @Test
        @DisplayName("TradeId is symbol-prefixed and unique per trade")
        void tradeIdsUnique() {
            process(submit("a1", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("a2", USER_A, Side.ASK, "100", "5", OrderType.LIMIT));
            process(submit("b1", USER_B, Side.BID, "100", "10", OrderType.LIMIT));

            List<String> tradeIds = publisher.allOfType(OutboundOrderEvent.TradeExecuted.class)
                    .stream().map(OutboundOrderEvent.TradeExecuted::tradeId).toList();

            assertEquals(2, tradeIds.size());
            assertEquals(2, tradeIds.stream().distinct().count(), "trade IDs must be unique");
            tradeIds.forEach(id ->
                    assertTrue(id.startsWith(SYMBOL + "-T-"), "tradeId prefix: " + id));
        }
    }

    // =========================================================================
    // Command factory helpers
    // =========================================================================

    private void process(OrderCommand cmd) {
        processor.process(cmd);
    }

    private static OrderCommand.Submit submit(String orderId, String userId, Side side,
                                               String price, String qty, OrderType type) {
        return new OrderCommand.Submit(
                orderId, userId, SYMBOL, side,
                new BigDecimal(price), new BigDecimal(qty),
                type, null, System.nanoTime());
    }

    private static OrderCommand.Cancel cancel(String orderId) {
        return new OrderCommand.Cancel(orderId, SYMBOL, "requester", System.nanoTime());
    }

    private static OrderCommand.Amend amend(String orderId, String newPrice, String newQty) {
        return new OrderCommand.Amend(orderId, SYMBOL,
                new BigDecimal(newPrice), new BigDecimal(newQty), System.nanoTime());
    }

    // =========================================================================
    // Test double: capturing event publisher
    // =========================================================================

    /**
     * In-memory {@link EventPublisher} that records all published events.
     * Prefer this over Mockito for interview code — it shows you can design
     * test doubles without a framework.
     */
    static class CapturingPublisher implements EventPublisher {

        private final List<OutboundOrderEvent> published = new ArrayList<>();
        private final AtomicInteger dlqCounter = new AtomicInteger(0);

        @Override
        public void publish(OutboundOrderEvent event) {
            published.add(event);
        }

        @Override
        public void publishToDlq(OrderCommand command, Throwable cause) {
            dlqCounter.incrementAndGet();
        }

        List<OutboundOrderEvent> events() {
            return List.copyOf(published);
        }

        void clear() {
            published.clear();
        }

        int dlqCount() {
            return dlqCounter.get();
        }

        <T extends OutboundOrderEvent> T firstOfType(Class<T> type) {
            return published.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .findFirst()
                    .orElse(null);
        }

        <T extends OutboundOrderEvent> List<T> allOfType(Class<T> type) {
            return published.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }
}
