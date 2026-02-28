package lld.orderbook;

import java.math.BigDecimal;

/**
 * Demo: order book and matching engine with price-time priority.
 * Shows submit → match → trades + book events, cancel, and top of book.
 */
public class OrderBookDemo {

  public static void main(String[] args) {
    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);

    System.out.println("=== Limit orders (resting) ===");
    engine.match(new Order("o1", Side.BID, new BigDecimal("100.00"), new BigDecimal("10")));
    engine.match(new Order("o2", Side.BID, new BigDecimal("100.00"), new BigDecimal("5")));
    engine.match(new Order("o3", Side.ASK, new BigDecimal("100.50"), new BigDecimal("8")));
    engine.match(new Order("o4", Side.ASK, new BigDecimal("101.00"), new BigDecimal("20")));
    printBook(book);
    System.out.println("Best bid=" + book.getBestBid() + ", best ask=" + book.getBestAsk() + ", spread=" + book.getSpread());

    System.out.println("\n=== Incoming buy @ 100.50 (crosses with ask 100.50) ===");
    MatchResult r = engine.match(new Order("o5", Side.BID, new BigDecimal("100.50"), new BigDecimal("10")));
    r.getTrades().forEach(t -> System.out.println("  " + t));
    r.getBookEvents().forEach(e -> System.out.println("  " + e));
    printBook(book);

    System.out.println("\n=== Incoming sell @ 99.00 (resting only) ===");
    engine.match(new Order("o6", Side.ASK, new BigDecimal("99.00"), new BigDecimal("3")));
    printBook(book);

    System.out.println("\n=== Cancel o2 ===");
    Order cancelled = engine.cancel("o2");
    System.out.println("Cancelled: " + cancelled);
    printBook(book);

    System.out.println("\n=== Amend o4: replace with 101.00 qty 5 ===");
    MatchResult amendResult = engine.amend("o4", new BigDecimal("101.00"), new BigDecimal("5"));
    System.out.println("Amend trades: " + amendResult.getTrades());
    printBook(book);
  }

  static void printBook(OrderBook book) {
    System.out.println("Book (top 5): " + book.getTopOfBook(5));
  }
}
