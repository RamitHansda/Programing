package lld.orderbook;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Limit order: price, quantity, side. Uses BigDecimal for price and quantity to avoid
 * rounding errors (per HLD: fixed-point or decimal, not float).
 * <p>
 * Leaves quantity is the remaining quantity after partial fills; for a new order it equals
 * the original quantity.
 */
public final class Order {

  private final String orderId;
  private final Side side;
  private final BigDecimal price;
  private final BigDecimal originalQuantity;
  private BigDecimal leavesQuantity;
  private final long timestamp;

  public Order(String orderId, Side side, BigDecimal price, BigDecimal quantity, long timestamp) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.side = Objects.requireNonNull(side, "side");
    this.price = Objects.requireNonNull(price, "price");
    this.originalQuantity = Objects.requireNonNull(quantity, "quantity");
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
    this.leavesQuantity = quantity;
    this.timestamp = timestamp;
  }

  public Order(String orderId, Side side, BigDecimal price, BigDecimal quantity) {
    this(orderId, side, price, quantity, System.nanoTime());
  }

  public String getOrderId() {
    return orderId;
  }

  public Side getSide() {
    return side;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public BigDecimal getOriginalQuantity() {
    return originalQuantity;
  }

  public BigDecimal getLeavesQuantity() {
    return leavesQuantity;
  }

  public long getTimestamp() {
    return timestamp;
  }

  /** Reduces leaves quantity by fillQty (caller must ensure fillQty <= leavesQuantity). */
  void reduceLeaves(BigDecimal fillQty) {
    this.leavesQuantity = this.leavesQuantity.subtract(fillQty);
  }

  public boolean isFilled() {
    return leavesQuantity.signum() <= 0;
  }

  @Override
  public String toString() {
    return "Order{" + orderId + "," + side + "," + price + "," + leavesQuantity + "/" + originalQuantity + "}";
  }
}
