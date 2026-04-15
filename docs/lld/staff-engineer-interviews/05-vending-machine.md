# LLD: Vending machine

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
