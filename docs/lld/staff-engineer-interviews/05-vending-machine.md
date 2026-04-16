# LLD: Vending machine

## Interview-ready snapshot

**Say first (≈30s):** **State machine** for session (idle / has money / dispensing); inventory + coin hopper; dispense + change is **one atomic business step**; optional **Command** for trace.

**Default assumptions:** Single user at a time unless they ask concurrent kiosks.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Coin inventory for change; cancel behavior; admin restock. |
| Model | 10 min | Context, states, slots/SKUs, `ChangeMaker`, balances. |
| API + flow | 8 min | insert / select / dispense / cancel sequence on board. |
| Hard | 10 min | Out of stock vs insufficient funds; cannot dispense partial success. |
| Close | 5 min | Card payment as new state + adapter, not a rewrite. |

**Whiteboard order:** (1) state circles (2) context fields (3) one successful purchase path (4) change-making failure (5) invariants list.

**Likely probes:** Greedy change OK? What if machine cannot make exact change after accepting coins?

**30s closer:** States gate operations; domain invariants on money and inventory; payment modes extend via strategy + states.

---

## Interview prompt

Design a **vending machine**: accept coins, select product, dispense change, handle out-of-stock and insufficient funds.

## Clarifying questions

- **Inventory**: fixed slots with counts?
- **Coin inventory**: machine can run out of change?
- **Cancel**: return inserted coins?
- **Admin**: restock API in scope?

## Functional requirements

- Insert coins, track **current balance**.
- Select SKU; dispense if affordable and in stock; return **change**.
- Cancel returns inserted money (define whether change inventory is affected).

## Non-functional requirements

- **Clear state machine** (interviewers reward this).
- **Extensibility** for new payment modes (card) without rewriting core dispense path.

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Aggregate root** | `VendingMachine` / `VendingMachineContext` | Coin balance in session, current `VendingState`, catalog reference, coin hopper inventory. |
| **Entity** | `Slot` / `ProductRow` | SKU, price, stock count (identity = slot id). |
| **Value object** | `Coin`, `Money` | Denominations and arithmetic. |
| **Domain service** | `ChangeMaker` | Given target change and available coins, returns allocation or failure. |
| **State** | `VendingState` | Encodes which operations are legal from here. |

**Relationships:** machine **contains** many `Slot`s; **session** holds `insertedBalance` until commit or cancel.

**Not modeled:** payment network for cards (only an interface boundary if extended).

## Design patterns

| Pattern | Role |
|--------|------|
| **State** | `Idle`, `HasMoney`, `Dispensing`, `OutOfService` control valid operations. |
| **Command** (optional) | `CoinInsert`, `SelectProduct`, `Cancel` as commands for logging/replay. |
| **Strategy** (optional) | `ChangeMaker` (greedy coin selection) if coin sets vary by locale. |

## Invariants

- `insertedBalance >= 0`.
- Inventory counts never negative.
- Dispense + change operations are **atomic** as a unit (all-or-nothing).

## Concurrency

- Single user kiosk assumption is common; still mention **mutex** around transaction if multi-threaded tests exist.

## Java sketch

```java
public interface VendingState {
    void insertCoin(VendingMachineContext ctx, Coin c);
    void select(VendingMachineContext ctx, String sku);
    void cancel(VendingMachineContext ctx);
}
```

Keep **coin routing** (`Funds` + `CoinInventory`) separate from **product catalog**.

## Testing strategy

- State transition table tests.
- Change-making tests with depleted coin inventory (must fail gracefully).

## Follow-ups

- **Weighted items** (bulk dispenser), age verification flags (pattern-wise still state + policy).
