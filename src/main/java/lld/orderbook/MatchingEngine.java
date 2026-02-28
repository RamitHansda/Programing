package lld.orderbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Matching engine: accepts an order, matches against the opposite side using price-time
 * priority, produces trades and book updates. Single-threaded use per symbol (or external
 * serialization) for deterministic behavior.
 */
public class MatchingEngine {

  private final OrderBook book;

  public MatchingEngine(OrderBook book) {
    this.book = book;
  }

  public OrderBook getOrderBook() {
    return book;
  }

  /**
   * Process one incoming order: match against the opposite side, then place remainder (if any)
   * on the book. Returns trades and book events.
   */
  public MatchResult match(Order order) {
    MatchResult result = new MatchResult();
    if (order.getLeavesQuantity().signum() <= 0) {
      return result;
    }

    if (order.getSide() == Side.BID) {
      matchBuyOrder(order, result);
    } else {
      matchSellOrder(order, result);
    }

    // If incoming still has quantity, add remainder as resting order
    if (order.getLeavesQuantity().signum() > 0) {
      book.addOrder(order);
      result.addBookEvent(new BookEvent(BookEvent.Type.ADD, order));
    }

    return result;
  }

  /** Buy order: match against asks (lowest price first), then FIFO at each price. */
  private void matchBuyOrder(Order order, MatchResult result) {
    BigDecimal orderPrice = order.getPrice();
    while (order.getLeavesQuantity().signum() > 0) {
      PriceLevel bestAskLevel = book.getBestAskLevel();
      if (bestAskLevel == null) break;
      // Crossing: buy price >= best ask
      if (orderPrice.compareTo(bestAskLevel.getPrice()) < 0) break;

      matchAtLevel(order, bestAskLevel, Side.ASK, result);
    }
  }

  /** Sell order: match against bids (highest price first), then FIFO at each price. */
  private void matchSellOrder(Order order, MatchResult result) {
    BigDecimal orderPrice = order.getPrice();
    while (order.getLeavesQuantity().signum() > 0) {
      PriceLevel bestBidLevel = book.getBestBidLevel();
      if (bestBidLevel == null) break;
      // Crossing: sell price <= best bid
      if (orderPrice.compareTo(bestBidLevel.getPrice()) > 0) break;

      matchAtLevel(order, bestBidLevel, Side.BID, result);
    }
  }

  /**
   * Match incoming order against the first resting order at the given level (FIFO).
   * Emit trade(s), reduce quantities, remove resting order if filled, remove level if empty.
   */
  private void matchAtLevel(Order incoming, PriceLevel level, Side levelSide, MatchResult result) {
    Order resting = level.peekFirst();
    if (resting == null) return;

    BigDecimal fillQty = incoming.getLeavesQuantity().min(resting.getLeavesQuantity());
    if (fillQty.signum() <= 0) return;

    BigDecimal price = level.getPrice();
    Trade trade = new Trade(price, fillQty, incoming.getOrderId(), resting.getOrderId());
    result.addTrade(trade);
    result.addBookEvent(new BookEvent(BookEvent.Type.FILL, resting, "qty=" + fillQty));

    incoming.reduceLeaves(fillQty);
    resting.reduceLeaves(fillQty);

    if (resting.isFilled()) {
      level.pollFirst();
      book.removeOrderFromIndex(resting);
      if (level.isEmpty()) {
        book.removeEmptyLevel(levelSide, level.getPrice());
      }
    }
  }

  /** Cancel by order ID. Returns the cancelled order if found. */
  public Order cancel(String orderId) {
    Order removed = book.cancelOrder(orderId);
    return removed;
  }

  /**
   * Amend: cancel existing order and submit new order with same ID (new price/qty).
   * Returns match result for the new order only (cancel is a separate book event if needed).
   */
  public MatchResult amend(String orderId, BigDecimal newPrice, BigDecimal newQuantity) {
    Order existing = book.cancelOrder(orderId);
    if (existing == null) return new MatchResult();
    Order newOrder = new Order(orderId, existing.getSide(), newPrice, newQuantity, System.nanoTime());
    return match(newOrder);
  }
}
