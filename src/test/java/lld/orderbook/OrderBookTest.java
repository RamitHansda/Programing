package lld.orderbook;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

  @Test
  void restingOrdersAndTopOfBook() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);

    engine.match(new Order("b1", Side.BID, new BigDecimal("100"), new BigDecimal("10")));
    engine.match(new Order("b2", Side.BID, new BigDecimal("100"), new BigDecimal("5")));
    engine.match(new Order("a1", Side.ASK, new BigDecimal("101"), new BigDecimal("8")));

    assertEquals(new BigDecimal("100"), book.getBestBid());
    assertEquals(new BigDecimal("101"), book.getBestAsk());
    assertEquals(new BigDecimal("1"), book.getSpread());

    List<String> top = book.getTopOfBook(3);
    assertTrue(top.stream().anyMatch(s -> s.startsWith("BID 100")));
    assertTrue(top.stream().anyMatch(s -> s.startsWith("ASK 101")));
  }

  @Test
  void priceTimePriorityMatching() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);

    engine.match(new Order("a1", Side.ASK, new BigDecimal("100"), new BigDecimal("5")));
    engine.match(new Order("a2", Side.ASK, new BigDecimal("100"), new BigDecimal("5")));
    // Incoming buy 10 @ 100: should fill a1 (5) then a2 (5) - FIFO at same price
    MatchResult r = engine.match(new Order("b1", Side.BID, new BigDecimal("100"), new BigDecimal("10")));

    assertEquals(2, r.getTrades().size());
    assertEquals("a1", r.getTrades().get(0).getMakerOrderId());
    assertEquals("a2", r.getTrades().get(1).getMakerOrderId());
    assertEquals(new BigDecimal("5"), r.getTrades().get(0).getQuantity());
    assertEquals(new BigDecimal("5"), r.getTrades().get(1).getQuantity());
    assertTrue(book.getAsks().isEmpty());
  }

  @Test
  void partialFillAndResting() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);

    engine.match(new Order("a1", Side.ASK, new BigDecimal("100"), new BigDecimal("20")));
    MatchResult r = engine.match(new Order("b1", Side.BID, new BigDecimal("100"), new BigDecimal("7")));

    assertEquals(1, r.getTrades().size());
    assertEquals(new BigDecimal("7"), r.getTrades().get(0).getQuantity());
    assertEquals(new BigDecimal("100"), book.getBestAsk());
    // a1 should have 13 left
    Order remaining = book.getOrderById("a1");
    assertNotNull(remaining);
    assertEquals(new BigDecimal("13"), remaining.getLeavesQuantity());
  }

  @Test
  void cancelRemovesOrder() {
    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);

    engine.match(new Order("b1", Side.BID, new BigDecimal("99"), new BigDecimal("10")));
    assertEquals(new BigDecimal("99"), book.getBestBid());

    Order cancelled = engine.cancel("b1");
    assertNotNull(cancelled);
    assertEquals("b1", cancelled.getOrderId());
    assertNull(book.getBestBid());
    assertNull(book.getOrderById("b1"));
  }
}
