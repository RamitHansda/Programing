# Vending Machine - Low Level Design

A comprehensive implementation of a Vending Machine system demonstrating the State Design Pattern, thread safety, and clean object-oriented design.

## Features

- 🪙 Accept coins and notes
- 🥤 Product selection and dispensing
- 💰 Automatic change calculation
- 📦 Inventory management
- ❌ Transaction cancellation
- 🔒 Thread-safe operations
- 🎯 State pattern implementation

## Quick Start

### Running the Demo

```bash
cd src/lld/vendingmachine
javac *.java
java lld.vendingmachine.VendingMachineDemo
```

### Basic Usage

```java
// Create vending machine
VendingMachine machine = new VendingMachine();

// Add products
machine.addProduct(new Product("A1", "Coke", 125, ProductType.BEVERAGE), 10);

// Make purchase
machine.insertNote(Note.ONE);       // $1.00
machine.insertCoin(Coin.QUARTER);   // $0.25
machine.selectProduct("A1");        // Buy Coke
```

## Architecture

### State Pattern

The vending machine uses the State Pattern with three states:

1. **IdleState** - Waiting for money
2. **HasMoneyState** - Money inserted, waiting for selection
3. **DispensingState** - Dispensing product

### Class Structure

```
VendingMachine (Context)
├── Inventory (Product management)
├── VendingMachineState (Strategy)
│   ├── IdleState
│   ├── HasMoneyState
│   └── DispensingState
├── Product (Data)
├── Coin (Enum)
├── Note (Enum)
└── ProductType (Enum)
```

## Key Classes

### VendingMachine
Main class that users interact with. Delegates operations to current state.

```java
public void insertCoin(Coin coin)
public void insertNote(Note note)
public void selectProduct(String productId)
public void cancelTransaction()
```

### VendingMachineState
Interface defining behavior for each state.

```java
void insertCoin(VendingMachine machine, Coin coin)
void selectProduct(VendingMachine machine, String productId)
void dispenseProduct(VendingMachine machine)
void cancelTransaction(VendingMachine machine)
```

### Inventory
Thread-safe inventory management using ConcurrentHashMap.

```java
void addProduct(Product product, int quantity)
boolean removeProduct(Product product)
boolean isAvailable(Product product)
Product getProduct(String productId)
```

## Demo Scenarios

The demo includes 6 scenarios:

1. ✅ Successful purchase with exact money
2. ✅ Purchase with change return
3. ⚠️ Insufficient funds
4. ❌ Product out of stock
5. ↩️ Transaction cancellation
6. ⚠️ Invalid product selection

## Design Patterns Used

- **State Pattern**: For managing vending machine states
- **Enum Pattern**: For coins, notes, and product types
- **Singleton-like**: Thread-safe state management

## Thread Safety

- `VendingMachine`: Uses synchronized blocks with lock object
- `Inventory`: Uses ConcurrentHashMap + synchronized methods
- `State objects`: Stateless, inherently thread-safe

## Extension Points

### Add New Payment Method
```java
// Add to VendingMachineState interface
void insertCard(VendingMachine machine, Card card);
```

### Add New State
```java
public class MaintenanceState implements VendingMachineState {
    // Implement all interface methods
}
```

### Add Product Heating
```java
public class HeatingState implements VendingMachineState {
    // Add delay before dispensing hot beverages
}
```

## SOLID Principles

- **Single Responsibility**: Each class has one clear purpose
- **Open/Closed**: Can add states without modifying existing code
- **Liskov Substitution**: All states are interchangeable
- **Interface Segregation**: Focused interfaces
- **Dependency Inversion**: Depends on abstractions (State interface)

## Interview Tips

### Common Questions

**Q: Why State Pattern instead of if-else?**
- Eliminates complex conditionals
- Easy to add new states
- Each state encapsulates its behavior
- Follows Open/Closed Principle

**Q: How to handle insufficient change?**
- Maintain coin/note inventory
- Implement change-making algorithm
- Handle "exact change only" scenario

**Q: How to make it more realistic?**
- Add hardware interfaces (coin acceptor, dispenser)
- Implement payment card processing
- Add UI/display management
- Include error recovery mechanisms

## File Structure

```
vendingmachine/
├── Coin.java                    # Coin denominations
├── Note.java                    # Note denominations
├── ProductType.java             # Product categories
├── Product.java                 # Product data class
├── Inventory.java               # Inventory management
├── VendingMachineState.java     # State interface
├── IdleState.java              # Idle state implementation
├── HasMoneyState.java          # Has money state
├── DispensingState.java        # Dispensing state
├── VendingMachine.java         # Main class
├── VendingMachineDemo.java     # Demo/test
├── DESIGN.md                   # Detailed design doc
└── README.md                   # This file
```

## Testing

### Manual Testing
Run `VendingMachineDemo.java` to see all scenarios.

### Unit Testing Ideas
- Test each state independently
- Test inventory concurrent access
- Test money calculations
- Test edge cases (out of stock, invalid product)

## Future Enhancements

- [ ] Credit card payment support
- [ ] Mobile payment integration
- [ ] Product expiry tracking
- [ ] Sales analytics
- [ ] Remote monitoring
- [ ] Dynamic pricing
- [ ] User authentication
- [ ] Loyalty programs

## License

Free to use for learning and interview preparation.

## Author

Created as part of Low Level Design practice.

---

**Happy Coding! 🚀**
