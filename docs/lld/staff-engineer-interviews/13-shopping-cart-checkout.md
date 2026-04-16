# LLD: Shopping cart and checkout

## Interview-ready snapshot

**Say first (≈30s):** **Cart** with line items + **price snapshots**; **checkout** validates freshness; **`placeOrder`** idempotent; payment + inventory as **conceptual saga** with explicit failure story.

**Default assumptions:** Optimistic stock or simple “fail if changed”—pick one early.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | When stock reserved; guest cart; coupons/tax. |
| Model | 10 min | Cart, LineItem, CheckoutSession, Order, ports. |
| API + flow | 10 min | add/remove → checkout → placeOrder happy path. |
| Hard | 12 min | Idempotency key; partial payment failure; compensating release. |
| Close | 5 min | Marketplace split payouts—out of scope unless asked. |

**Whiteboard order:** (1) cart lines + snapshots (2) checkout freeze (3) placeOrder idempotency (4) saga arrows (5) invariants on totals.

**Likely probes:** Duplicate submit? Price changes mid-checkout?

**30s closer:** Order is immutable fact; strategies for payment/shipping; saga boundaries explicit.

---

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

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `Cart` | Line items, owner; add/remove lines; may be ephemeral. |
| **Entity** | `LineItem` | SKU ref, quantity, **unit price snapshot** at time added or at checkout freeze. |
| **Aggregate** | `CheckoutSession` (optional) | Frozen pricing, shipping choice, payment instrument handle; expires. |
| **Entity** | `Order` | Immutable placed order: lines, totals, payment id, idempotency key. |
| **Value object** | `Money`, `SkuId`, `OrderId` | Arithmetic and identity. |
| **Ports** | `PaymentGateway`, `InventoryService` | Outbound; keep out of entity except through services. |

**Relationships:** `Cart` **has many** `LineItem`; `placeOrder` **creates** `Order` and triggers inventory/payment **saga** steps.

**Not modeled:** warehouse pick path, carrier tracking webhooks.

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
