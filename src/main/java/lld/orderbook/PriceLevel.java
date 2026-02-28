package lld.orderbook;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * One price level: same price, orders in FIFO (first in, first out for time priority).
 */
public final class PriceLevel implements Iterable<Order> {

  private final BigDecimal price;
  private final Deque<Order> orders = new ArrayDeque<>();

  public PriceLevel(BigDecimal price) {
    this.price = Objects.requireNonNull(price, "price");
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void addOrder(Order order) {
    orders.addLast(order);
  }

  /** Removes the given order from this level. Returns true if removed. */
  public boolean removeOrder(Order order) {
    return orders.remove(order);
  }

  /** Removes and returns the first order (FIFO), or null if empty. */
  public Order pollFirst() {
    return orders.pollFirst();
  }

  public Order peekFirst() {
    return orders.peekFirst();
  }

  public boolean isEmpty() {
    return orders.isEmpty();
  }

  public int size() {
    return orders.size();
  }

  @Override
  public Iterator<Order> iterator() {
    return orders.iterator();
  }
}
