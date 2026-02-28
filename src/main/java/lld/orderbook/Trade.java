package lld.orderbook;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A fill: price, quantity, taker (incoming) order ID and maker (resting) order ID.
 */
public final class Trade {

  private final BigDecimal price;
  private final BigDecimal quantity;
  private final String takerOrderId;
  private final String makerOrderId;

  public Trade(BigDecimal price, BigDecimal quantity, String takerOrderId, String makerOrderId) {
    this.price = Objects.requireNonNull(price, "price");
    this.quantity = Objects.requireNonNull(quantity, "quantity");
    this.takerOrderId = Objects.requireNonNull(takerOrderId, "takerOrderId");
    this.makerOrderId = Objects.requireNonNull(makerOrderId, "makerOrderId");
  }

  public BigDecimal getPrice() {
    return price;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public String getTakerOrderId() {
    return takerOrderId;
  }

  public String getMakerOrderId() {
    return makerOrderId;
  }

  @Override
  public String toString() {
    return "Trade{price=" + price + ",qty=" + quantity + ",taker=" + takerOrderId + ",maker=" + makerOrderId + "}";
  }
}
