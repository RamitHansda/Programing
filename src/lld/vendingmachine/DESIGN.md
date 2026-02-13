# Vending Machine - Low Level Design

## Table of Contents
1. [Overview](#overview)
2. [Requirements](#requirements)
3. [Design Patterns](#design-patterns)
4. [Class Diagram](#class-diagram)
5. [Core Components](#core-components)
6. [State Machine](#state-machine)
7. [Thread Safety](#thread-safety)
8. [Usage Examples](#usage-examples)
9. [Interview Discussion Points](#interview-discussion-points)

---

## Overview

A Vending Machine is a classic LLD problem that demonstrates various design patterns and object-oriented principles. This implementation showcases:
- State Design Pattern for managing machine states
- Thread-safe operations for concurrent access
- Clean separation of concerns
- SOLID principles

### Key Features
- Insert coins and notes
- Select products
- Dispense products
- Return change
- Manage inventory
- Handle out-of-stock scenarios
- Transaction cancellation

---

## Requirements

### Functional Requirements
1. **Product Management**
   - Store multiple products with different prices
   - Track inventory for each product
   - Display available products

2. **Money Handling**
   - Accept coins (penny, nickel, dime, quarter)
   - Accept notes ($1, $5, $10, $20)
   - Track current balance
   - Return change

3. **Product Dispensing**
   - Allow product selection
   - Validate sufficient funds
   - Check product availability
   - Dispense product and return change

4. **Transaction Management**
   - Support transaction cancellation
   - Return money on cancellation
   - Handle invalid selections

### Non-Functional Requirements
1. **Thread Safety**: Support concurrent operations
2. **Extensibility**: Easy to add new product types or payment methods
3. **Maintainability**: Clean, modular code
4. **Reliability**: Handle edge cases gracefully

---

## Design Patterns

### 1. State Pattern
**Why**: Vending machine behavior changes based on its state
- **IdleState**: Waiting for money insertion
- **HasMoneyState**: Money inserted, waiting for product selection
- **DispensingState**: Dispensing product

**Benefits**:
- Eliminates complex conditional logic
- Easy to add new states
- Each state encapsulates its behavior
- Follows Open/Closed Principle

### 2. Enum Pattern
**Why**: Fixed set of coins, notes, and product types
- `Coin` enum for coin denominations
- `Note` enum for note denominations
- `ProductType` enum for product categories

**Benefits**:
- Type safety
- Fixed set of valid values
- Cannot create invalid instances

---

## Class Diagram

```
┌─────────────────────────┐
│   VendingMachine        │
├─────────────────────────┤
│ - inventory: Inventory  │
│ - currentState: State   │
│ - currentBalance: int   │
│ - selectedProduct       │
├─────────────────────────┤
│ + insertCoin()          │
│ + insertNote()          │
│ + selectProduct()       │
│ + cancelTransaction()   │
└─────────────────────────┘
           │
           │ uses
           ▼
┌─────────────────────────┐
│ VendingMachineState     │◄──────────────┐
├─────────────────────────┤               │
│ + insertCoin()          │               │
│ + insertNote()          │               │
│ + selectProduct()       │               │
│ + dispenseProduct()     │               │
│ + returnChange()        │               │
│ + cancelTransaction()   │               │
└─────────────────────────┘               │
           △                              │
           │ implements                   │
           │                              │
     ┌─────┴─────┬────────────────────────┤
     │           │                        │
┌────┴────┐ ┌────┴────────┐    ┌──────────┴────────┐
│IdleState│ │HasMoneyState│    │DispensingState    │
└─────────┘ └─────────────┘    └───────────────────┘

┌─────────────────────────┐
│      Inventory          │
├─────────────────────────┤
│ - productQuantity: Map  │
│ - productById: Map      │
├─────────────────────────┤
│ + addProduct()          │
│ + removeProduct()       │
│ + isAvailable()         │
│ + getProduct()          │
└─────────────────────────┘
           │
           │ manages
           ▼
┌─────────────────────────┐
│       Product           │
├─────────────────────────┤
│ - id: String            │
│ - name: String          │
│ - price: int            │
│ - type: ProductType     │
└─────────────────────────┘

┌──────────┐  ┌──────────┐  ┌──────────────┐
│  Coin    │  │   Note   │  │ ProductType  │
├──────────┤  ├──────────┤  ├──────────────┤
│ PENNY    │  │ ONE      │  │ BEVERAGE     │
│ NICKEL   │  │ FIVE     │  │ SNACK        │
│ DIME     │  │ TEN      │  │ CHOCOLATE    │
│ QUARTER  │  │ TWENTY   │  │ CHIPS        │
└──────────┘  └──────────┘  │ CANDY        │
                            └──────────────┘
```

---

## Core Components

### 1. VendingMachine (Context)
**Responsibility**: Main interface for users and state management
```java
- Delegates operations to current state
- Maintains inventory
- Tracks balance and selected product
- Thread-safe using synchronized blocks
```

### 2. VendingMachineState (Strategy)
**Responsibility**: Define behavior for each state
```java
- Each state implements this interface
- Defines all possible operations
- State transitions happen within implementations
```

### 3. Concrete States

#### IdleState
```
Behavior:
- Accept money → transition to HasMoneyState
- Reject product selection (no money)
- No cancellation needed
```

#### HasMoneyState
```
Behavior:
- Accept more money (accumulate)
- Allow product selection
  → If valid & sufficient funds → transition to DispensingState
  → If insufficient → stay in HasMoneyState
- Allow cancellation → return money → transition to IdleState
```

#### DispensingState
```
Behavior:
- Dispense product
- Return change if any
- Transition to IdleState
- Block all other operations during dispensing
```

### 4. Inventory
**Responsibility**: Manage product stock
```java
- Thread-safe using ConcurrentHashMap
- Track quantity per product
- Add/remove products
- Check availability
```

### 5. Product
**Responsibility**: Represent a product
```java
- Immutable data class
- Contains id, name, price, type
- Override equals/hashCode for proper map usage
```

---

## State Machine

### State Transition Diagram

```
                    ┌──────────┐
                    │  Idle    │
                    │  State   │
                    └────┬─────┘
                         │
                    insertCoin()
                    insertNote()
                         │
                         ▼
                    ┌──────────┐
          ┌─────────┤ HasMoney │
          │         │  State   │◄──────┐
          │         └────┬─────┘       │
          │              │             │
          │      selectProduct()       │
          │      (valid + funds)       │
          │              │             │
    cancelTransaction()  ▼             │
          │         ┌──────────┐       │
          │         │Dispensing│   insufficient
          │         │  State   │   funds/invalid
          │         └────┬─────┘       │
          │              │             │
          │      dispenseProduct()     │
          │              │             │
          │              ▼             │
          └─────────►┌──────────┐      │
                     │  Idle    │──────┘
                     │  State   │
                     └──────────┘
```

### State Behavior Matrix

| Action              | IdleState | HasMoneyState | DispensingState |
|---------------------|-----------|---------------|-----------------|
| insertCoin()        | Accept → HasMoney | Accept (accumulate) | Reject |
| insertNote()        | Accept → HasMoney | Accept (accumulate) | Reject |
| selectProduct()     | Reject    | Validate → Dispensing | Reject |
| dispenseProduct()   | Reject    | Reject        | Execute → Idle |
| cancelTransaction() | N/A       | Return money → Idle | Reject |

---

## Thread Safety

### Concurrency Considerations

1. **VendingMachine**: Uses synchronized blocks on all public methods
   ```java
   synchronized (lock) {
       currentState.insertCoin(this, coin);
   }
   ```

2. **Inventory**: Uses ConcurrentHashMap for thread-safe map operations
   - Additional synchronization on removeProduct() for atomic read-modify-write

3. **State Objects**: Stateless - safe to share

### Why Thread Safety Matters
- Multiple users might use the machine simultaneously
- Prevents race conditions
- Ensures inventory consistency
- Protects balance from concurrent modifications

---

## Usage Examples

### Basic Purchase Flow
```java
VendingMachine machine = new VendingMachine();

// Add products
machine.addProduct(new Product("A1", "Coke", 125, ProductType.BEVERAGE), 10);

// Display products
machine.displayProducts();

// Make purchase
machine.insertNote(Note.ONE);       // Insert $1.00
machine.insertCoin(Coin.QUARTER);   // Insert $0.25
machine.selectProduct("A1");        // Buy Coke ($1.25)
```

### Handle Insufficient Funds
```java
machine.insertNote(Note.ONE);       // Insert $1.00
machine.selectProduct("B1");        // Try to buy $1.50 item
// Output: "Insufficient funds. Please insert $0.50 more"

machine.insertCoin(Coin.QUARTER);   // Add $0.25
machine.insertCoin(Coin.QUARTER);   // Add $0.25
machine.selectProduct("B1");        // Now succeeds
```

### Cancel Transaction
```java
machine.insertNote(Note.FIVE);      // Insert $5.00
machine.displayStatus();            // Check status
machine.cancelTransaction();        // Cancel and get money back
// Output: "Returning change: $5.00"
```

---

## Interview Discussion Points

### 1. Why State Pattern?
**Q**: Why not use if-else statements?

**A**: 
- **Complexity**: With 3 states and 6 operations, we'd have 18 conditional checks
- **Maintenance**: Adding a new state requires modifying existing code
- **SRP Violation**: Single class handling all state logic
- **State Pattern**: Each state is a separate class with its own behavior

### 2. Extension Points

**Q**: How to add new features?

**A**: 
1. **New Payment Method** (e.g., Credit Card):
   - Add `insertCard()` method to State interface
   - Implement in all concrete states
   - Or use Strategy Pattern for payment processing

2. **New State** (e.g., MaintenanceState):
   - Create new class implementing VendingMachineState
   - Define behavior for all operations
   - Add transition logic

3. **Product Heating** (for beverages):
   - Add `requiresHeating` flag to Product
   - Create HeatingState between Dispensing and Idle
   - Simulate heating delay

### 3. Thread Safety Trade-offs

**Q**: Why synchronize the entire method?

**A**:
- **Simplicity**: Coarse-grained locking is easier to reason about
- **Correctness**: Ensures atomic state transitions
- **Performance**: For a vending machine, throughput isn't critical
- **Alternative**: Fine-grained locking (lock-free algorithms) adds complexity

### 4. Money Handling

**Q**: How to return exact change?

**A**: Current implementation returns total change amount. To return specific coins/notes:
1. Maintain coin/note inventory
2. Implement greedy change-making algorithm
3. Handle insufficient change scenario
4. Update Inventory to track money denominations

### 5. Error Handling

**Q**: What if product gets stuck?

**A**: Enhancement ideas:
1. Add `ProductDispenser` abstraction
2. Implement retry logic
3. Add RefundState for failures
4. Log errors for maintenance
5. Notify user and refund money

### 6. Persistence

**Q**: How to persist state across restarts?

**A**:
1. Serialize inventory and balance
2. Save to database/file
3. Reload on startup
4. Handle partial transactions (return money on restart)

### 7. SOLID Principles

**Single Responsibility**: 
- Each state class handles one state's behavior
- Inventory manages only stock
- Product is just data

**Open/Closed**: 
- Can add new states without modifying existing code
- Can add new product types via enum

**Liskov Substitution**: 
- All states are interchangeable via State interface

**Interface Segregation**: 
- State interface focused on vending operations

**Dependency Inversion**: 
- VendingMachine depends on State abstraction, not concrete states

### 8. Real-World Considerations

**Physical Integration**:
- Coin acceptor hardware interface
- Product dispenser motor control
- Display/UI integration
- Network connectivity for payments

**Business Logic**:
- Sales tracking and analytics
- Remote monitoring
- Dynamic pricing
- Promotions and discounts

**Reliability**:
- Power failure recovery
- Hardware malfunction handling
- Security (vandalism, theft)
- Regulatory compliance

---

## Complexity Analysis

### Time Complexity
- `insertCoin/Note`: O(1)
- `selectProduct`: O(1) - hash map lookup
- `dispenseProduct`: O(1)
- `displayProducts`: O(n) where n = number of products

### Space Complexity
- O(n) for inventory where n = number of unique products
- O(1) for state objects (stateless)

---

## Testing Scenarios

1. **Happy Path**: Insert money, select product, receive product and change
2. **Insufficient Funds**: Try to buy expensive item with little money
3. **Out of Stock**: Try to buy unavailable product
4. **Cancel Transaction**: Insert money and cancel
5. **Invalid Product**: Select non-existent product ID
6. **Exact Change**: Buy product with exact money
7. **Multiple Purchases**: Sequential purchases
8. **Concurrent Access**: Multiple threads accessing machine

---

## Potential Enhancements

1. **Payment Methods**
   - Credit/Debit card support
   - Mobile payment (UPI, Apple Pay)
   - QR code payment

2. **Advanced Inventory**
   - Expiry date tracking
   - Low stock alerts
   - Auto-reorder system

3. **User Experience**
   - Touch screen UI
   - Product images
   - Nutrition information
   - Favorites/history

4. **Analytics**
   - Sales reporting
   - Popular products
   - Revenue tracking
   - Usage patterns

5. **Remote Management**
   - Cloud connectivity
   - Remote diagnostics
   - OTA updates
   - Inventory monitoring

---

## Summary

This vending machine design demonstrates:
- ✅ Clean state management using State Pattern
- ✅ Thread-safe operations
- ✅ Proper encapsulation and abstraction
- ✅ Extensible design
- ✅ Real-world considerations
- ✅ SOLID principles

The implementation is production-ready for the core functionality and provides clear extension points for advanced features.
