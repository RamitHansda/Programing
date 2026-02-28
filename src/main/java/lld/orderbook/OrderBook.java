package lld.orderbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory order book for one symbol: bid side (price descending) and ask side (price ascending).
 * Maintains orderId → Order index for cancel/amend.
 */
public class OrderBook {

  // Bids: best first = highest price first → descending
  private final Map<BigDecimal, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
  // Asks: best first = lowest price first → ascending
  private final Map<BigDecimal, PriceLevel> asks = new TreeMap<>(Comparator.naturalOrder());
  private final Map<String, Order> orderById = new ConcurrentHashMap<>();

  public Map<BigDecimal, PriceLevel> getBids() {
    return bids;
  }

  public Map<BigDecimal, PriceLevel> getAsks() {
    return asks;
  }

  public Order getOrderById(String orderId) {
    return orderById.get(orderId);
  }

  /** Best bid = highest buy price, or null if no bids. */
  public BigDecimal getBestBid() {
    return bids.isEmpty() ? null : bids.keySet().iterator().next();
  }

  /** Best ask = lowest sell price, or null if no asks. */
  public BigDecimal getBestAsk() {
    return asks.isEmpty() ? null : asks.keySet().iterator().next();
  }

  /** Spread = best ask - best bid, or null if either side is empty. */
  public BigDecimal getSpread() {
    BigDecimal bestBid = getBestBid();
    BigDecimal bestAsk = getBestAsk();
    if (bestBid == null || bestAsk == null) return null;
    return bestAsk.subtract(bestBid);
  }

  /**
   * Adds a resting order to the book (correct side, correct price, FIFO by insertion).
   * Caller must ensure the order is not already in the book and has leaves quantity > 0.
   */
  public void addOrder(Order order) {
    if (order.getLeavesQuantity().signum() <= 0) return;
    BigDecimal price = order.getPrice();
    Map<BigDecimal, PriceLevel> side = order.getSide() == Side.BID ? bids : asks;
    PriceLevel level = side.computeIfAbsent(price, PriceLevel::new);
    level.addOrder(order);
    orderById.put(order.getOrderId(), order);
  }

  /**
   * Removes an order by ID. Returns the removed order, or null if not found.
   */
  public Order cancelOrder(String orderId) {
    Order order = orderById.remove(orderId);
    if (order == null) return null;
    BigDecimal price = order.getPrice();
    Map<BigDecimal, PriceLevel> side = order.getSide() == Side.BID ? bids : asks;
    PriceLevel level = side.get(price);
    if (level != null) {
      level.removeOrder(order);
      if (level.isEmpty()) {
        side.remove(price);
      }
    }
    return order;
  }

  /**
   * Amend: cancel existing and add new with same orderId but new price/qty.
   * Returns the previous order if found and removed; caller can then add the new order.
   */
  public Order amendOrder(String orderId, BigDecimal newPrice, BigDecimal newQuantity) {
    Order existing = cancelOrder(orderId);
    if (existing == null) return null;
    return existing;
  }

  /** Returns the best price level on the bid side (highest price), or null. */
  public PriceLevel getBestBidLevel() {
    if (bids.isEmpty()) return null;
    return bids.get(getBestBid());
  }

  /** Returns the best price level on the ask side (lowest price), or null. */
  public PriceLevel getBestAskLevel() {
    if (asks.isEmpty()) return null;
    return asks.get(getBestAsk());
  }

  /** Removes empty price level from the given side. */
  void removeEmptyLevel(Side side, BigDecimal price) {
    Map<BigDecimal, PriceLevel> map = side == Side.BID ? bids : asks;
    PriceLevel level = map.get(price);
    if (level != null && level.isEmpty()) {
      map.remove(price);
    }
  }

  /** Removes order from index (used by matching engine when order is fully filled). */
  void removeOrderFromIndex(Order order) {
    orderById.remove(order.getOrderId());
  }

  /** Snapshot of top of book (for display): up to `levels` bid and ask price levels with total quantity. */
  public List<String> getTopOfBook(int levels) {
    List<String> out = new ArrayList<>();
    int count = 0;
    for (Map.Entry<BigDecimal, PriceLevel> e : bids.entrySet()) {
      if (count++ >= levels) break;
      BigDecimal totalQty = BigDecimal.ZERO;
      for (Order o : e.getValue()) {
        totalQty = totalQty.add(o.getLeavesQuantity());
      }
      out.add("BID " + e.getKey() + " " + totalQty);
    }
    count = 0;
    for (Map.Entry<BigDecimal, PriceLevel> e : asks.entrySet()) {
      if (count++ >= levels) break;
      BigDecimal totalQty = BigDecimal.ZERO;
      for (Order o : e.getValue()) {
        totalQty = totalQty.add(o.getLeavesQuantity());
      }
      out.add("ASK " + e.getKey() + " " + totalQty);
    }
    return out;
  }
}
