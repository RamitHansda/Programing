# LLD: Shopping cart and checkout

## Interview prompt

Design **cart**, **checkout**, **payment**, and **order** creation for an e-commerce flow.

## Clarifying questions

- **Inventory**: reserve stock at cart, checkout, or payment capture?
- **Guest vs logged-in** carts?
- **Coupons / tax**: in scope?

## Functional requirements

- Add/remove line items with quantities and price snapshots.
- Checkout validates stock and price freshness.
- Place order: payment + persistence as one business transaction (define boundaries).

## Non-functional requirements

- **Idempotency** on `placeOrder` (network retries).
- **Price snapshot** immutability on line items once checkout starts.

## Design patterns

| Pattern | Role |
|--------|------|
| **Builder** | `OrderBuilder` / `CheckoutSession` stepwise assembly (shipping + payment method). |
| **Strategy** | `PaymentMethod`, `ShippingEstimator`, `DiscountPolicy`. |
| **Decorator** | Cross-cutting fees (gift wrap) wrapping a `PricedCart` interface. |
| **Saga** (conceptual) | Payment capture + inventory commit + order write with compensations. |

## Staff-level inventory stance

Pick one and defend:

- **Optimistic**: order fails if stock changed (simple, good for LLD).
- **Reservation**: TTL holds (more realistic, more moving parts).

## Invariants

- Order totals equal sum(line snapshots) + fees − discounts (within money rounding rules).
- Cart line `skuId` quantity > 0.

## Java sketch

```java
public interface PaymentGateway {
    PaymentResult authorize(Money amount, PaymentInstrument instrument);
}

public final class CheckoutService {
    public OrderId placeOrder(PlaceOrderCommand cmd) { /* idempotent */ }
}
```

## Testing strategy

- Idempotency: duplicate `placeOrder` returns same `OrderId`.
- Coupon stacking rules isolated tests.

## Follow-ups

- **Partial fulfillment**, split shipments, marketplace sellers.
