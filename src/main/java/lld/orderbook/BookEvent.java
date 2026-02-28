package lld.orderbook;

import java.util.Objects;

/**
 * Book update event: add, cancel, or amend (for downstream to reconstruct or broadcast depth).
 */
public final class BookEvent {

  public enum Type {
    ADD,
    CANCEL,
    AMEND,
    FILL
  }

  private final Type type;
  private final Order order;
  private final String details;

  public BookEvent(Type type, Order order) {
    this(type, order, null);
  }

  public BookEvent(Type type, Order order, String details) {
    this.type = Objects.requireNonNull(type, "type");
    this.order = order;
    this.details = details;
  }

  public Type getType() {
    return type;
  }

  public Order getOrder() {
    return order;
  }

  public String getDetails() {
    return details;
  }

  @Override
  public String toString() {
    return "BookEvent{" + type + "," + order + (details != null ? "," + details : "") + "}";
  }
}
