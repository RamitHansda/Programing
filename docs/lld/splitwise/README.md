# Splitwise - Expense Sharing System

A comprehensive Low Level Design implementation of Splitwise, an expense-sharing application that helps users split bills and track who owes whom.

## 🎯 Features

### Core Features
- ✅ **User Management**: Register and manage users
- ✅ **Multiple Split Types**: 
  - Equal split
  - Exact amounts
  - Percentage-based
  - Share-based (ratio)
- ✅ **Group Management**: Create groups and manage group expenses
- ✅ **Balance Tracking**: Real-time balance calculation between users
- ✅ **Settlement**: Record payments and settle debts
- ✅ **Debt Simplification**: Minimize number of transactions needed

### Technical Features
- ✅ Strategy Pattern for expense splitting
- ✅ Factory Pattern for splitter creation
- ✅ Service Layer for business logic
- ✅ Efficient balance sheet management
- ✅ Proper rounding and currency handling
- ✅ Clean, maintainable, extensible code

## 📁 Project Structure

```
splitwise/
├── User.java                      # User entity
├── Expense.java                   # Expense entity
├── Group.java                     # Group entity
├── Split.java                     # Split entity (how much each user owes)
├── Balance.java                   # Balance between two users
├── Transaction.java               # Settlement transaction
├── ExpenseType.java               # Enum for split types
├── ExpenseSplitter.java           # Strategy interface
├── EqualSplitter.java             # Equal split implementation
├── ExactSplitter.java             # Exact amount split implementation
├── PercentageSplitter.java        # Percentage split implementation
├── ShareSplitter.java             # Share-based split implementation
├── ExpenseSplitterFactory.java    # Factory for splitters
├── SplitwiseService.java          # Main service class
├── SplitwiseDemo.java             # Comprehensive demo
├── DESIGN.md                      # Detailed design documentation
└── README.md                      # This file
```

## 🚀 Quick Start

### Running the Demo

```bash
# Compile all files
javac lld/splitwise/*.java

# Run the demo
java lld.splitwise.SplitwiseDemo
```

The demo showcases:
1. **Equal Split**: Dinner among friends
2. **Exact Split**: Shopping with different items
3. **Percentage Split**: Business expense
4. **Share-based Split**: Rent split by room size
5. **Group Expenses**: Trip with multiple expenses
6. **Settlements**: Recording payments
7. **Debt Simplification**: Minimizing transactions

## 💡 Usage Examples

### Example 1: Simple Equal Split

```java
SplitwiseService splitwise = new SplitwiseService();

// Register users
User alice = splitwise.registerUser("Alice", "alice@example.com", "1111111111");
User bob = splitwise.registerUser("Bob", "bob@example.com", "2222222222");
User charlie = splitwise.registerUser("Charlie", "charlie@example.com", "3333333333");

// Create splits (amount is 0 for equal split, calculated automatically)
List<Split> splits = Arrays.asList(
    new Split(alice, 0),
    new Split(bob, 0),
    new Split(charlie, 0)
);

// Add expense
Expense expense = splitwise.addExpense(
    "Dinner at Restaurant",
    900.0,
    alice,  // Alice paid
    splits,
    ExpenseType.EQUAL,
    null    // Not part of a group
);

// Check balances
List<Balance> balances = splitwise.getUserBalances(alice);
// Result: Bob owes Alice ₹300, Charlie owes Alice ₹300
```

### Example 2: Exact Amount Split

```java
// Bob paid for shopping, different amounts per person
List<Split> splits = Arrays.asList(
    new Split(alice, 450.0),   // Alice's share
    new Split(bob, 250.0),     // Bob's share
    new Split(charlie, 300.0)  // Charlie's share
);

Expense expense = splitwise.addExpense(
    "Shopping",
    1000.0,
    bob,
    splits,
    ExpenseType.EXACT,
    null
);
```

### Example 3: Percentage Split

```java
// Split by percentage (must total 100%)
List<Split> splits = Arrays.asList(
    new Split(alice, 50.0),    // 50%
    new Split(bob, 30.0),      // 30%
    new Split(charlie, 20.0)   // 20%
);

Expense expense = splitwise.addExpense(
    "Office Supplies",
    2000.0,
    charlie,
    splits,
    ExpenseType.PERCENTAGE,
    null
);
```

### Example 4: Share-based Split

```java
// Split by ratio (e.g., 2:3:1 for room sizes)
List<Split> splits = Arrays.asList(
    new Split(alice, 2),    // 2 shares
    new Split(bob, 3),      // 3 shares
    new Split(charlie, 1)   // 1 share
);

Expense expense = splitwise.addExpense(
    "Monthly Rent",
    30000.0,
    alice,
    splits,
    ExpenseType.SHARE,
    null
);
// Result: Alice: ₹10000, Bob: ₹15000, Charlie: ₹5000
```

### Example 5: Group Expenses

```java
// Create a group
Group group = splitwise.createGroup("Weekend Trip", alice);
splitwise.addMemberToGroup(group.getId(), bob);
splitwise.addMemberToGroup(group.getId(), charlie);

// Add group expenses
splitwise.addExpense("Hotel", 15000.0, alice, splits, EQUAL, group.getId());
splitwise.addExpense("Food", 9000.0, bob, splits, EQUAL, group.getId());

// Get group balances
List<Balance> groupBalances = splitwise.getGroupBalances(group.getId());
```

### Example 6: Settlement

```java
// Bob pays Alice ₹500
Transaction settlement = splitwise.recordSettlement(
    bob,
    alice,
    500.0,
    "Cash payment"
);

// Balance is automatically updated
```

### Example 7: Simplify Debts

```java
// Complex scenario: A→B: 100, B→C: 100, C→A: 50
// Simplified: A→C: 50 (only 1 transaction instead of 3)

List<Transaction> simplified = splitwise.simplifyDebts(
    Arrays.asList(alice, bob, charlie)
);
```

### Example 8: Find What a User Owes in a Group

```java
// Get Bob's detailed balances in the group
List<Balance> bobBalances = splitwise.getUserBalancesInGroup(bob, groupId);

// Get summary amounts
double owes = splitwise.getTotalUserOwesInGroup(bob, groupId);
double owed = splitwise.getTotalUserOwedInGroup(bob, groupId);
double net = splitwise.getUserNetBalanceInGroup(bob, groupId);

System.out.println("Bob owes: ₹" + owes);
System.out.println("Bob is owed: ₹" + owed);
System.out.println("Net balance: ₹" + net);

// Output:
// Bob owes: ₹5000.00
// Bob is owed: ₹2000.00
// Net balance: ₹3000.00 (Bob needs to pay overall)
```

## 🏗️ Architecture

### Design Patterns Used

1. **Strategy Pattern**: For different expense splitting strategies
   - `ExpenseSplitter` interface
   - Implementations: `EqualSplitter`, `ExactSplitter`, `PercentageSplitter`, `ShareSplitter`

2. **Factory Pattern**: For creating appropriate splitter
   - `ExpenseSplitterFactory`

3. **Service Layer**: For business logic encapsulation
   - `SplitwiseService`

### Key Components

1. **Entities**: `User`, `Expense`, `Group`, `Split`, `Balance`, `Transaction`
2. **Strategies**: `ExpenseSplitter` and implementations
3. **Service**: `SplitwiseService` (main orchestrator)

### Data Structures

**Balance Sheet**: `Map<String, Map<String, Double>>`
- Outer map: userId → inner map
- Inner map: otherUserId → amount owed
- Positive: user owes other user
- Negative: other user owes user

## 🧮 Algorithms

### Balance Calculation
- **Time Complexity**: O(n) where n = number of splits
- **Space Complexity**: O(u²) where u = number of users

### Debt Simplification
- **Algorithm**: Greedy approach
- **Time Complexity**: O(u²) where u = number of users
- **Space Complexity**: O(u)

Steps:
1. Calculate net balance for each user
2. Separate creditors and debtors
3. Match largest creditor with largest debtor
4. Settle and repeat

## 🎓 Interview Tips

### Key Discussion Points

1. **Why Strategy Pattern?**
   - Different split logic for each type
   - Easy to add new split types
   - Testable independently

2. **How to handle rounding?**
   - Round to 2 decimal places
   - Last person gets remainder
   - Ensures total always matches

3. **How to scale?**
   - Partition by user ID
   - Cache balances
   - Event sourcing
   - CQRS pattern

4. **Concurrent updates?**
   - Add locking mechanism
   - Database transactions
   - Optimistic locking

5. **Debt simplification optimality?**
   - Greedy is good enough for most cases
   - Exact solution is NP-hard
   - Can use approximation algorithms

### Common Interview Questions

**Q: How would you handle multiple currencies?**
A: Add `Currency` field to `Expense`, store exchange rates, convert to base currency for balance calculation.

**Q: What if an expense needs to be edited?**
A: Add `updateExpense()` method, revert old balance changes, apply new ones. Consider audit trail.

**Q: How to handle recurring expenses?**
A: Add `RecurringExpense` entity with frequency, end date. Background job to create expenses.

**Q: How to optimize for read-heavy workload?**
A: Cache user balances, invalidate on expense. Use CQRS pattern with read replicas.

## 📊 Complexity Analysis

| Operation | Time | Space |
|-----------|------|-------|
| Add User | O(1) | O(1) |
| Add Expense | O(n) | O(n) |
| Get Balance | O(1) | - |
| Get User Balances | O(u) | O(u) |
| Simplify Debts | O(u²) | O(u) |
| Record Settlement | O(1) | O(1) |

where:
- n = number of splits in expense
- u = number of users

## 🚧 Future Enhancements

### High Priority
- [ ] Add persistence layer (database)
- [ ] Multi-currency support
- [ ] Receipt scanning (OCR)
- [ ] Payment integration

### Medium Priority
- [ ] Recurring expenses
- [ ] Expense categories
- [ ] Notifications (email/SMS)
- [ ] Analytics and reports

### Low Priority
- [ ] Mobile app
- [ ] Social features
- [ ] Budget tracking
- [ ] Export to PDF

## 🧪 Testing

### Unit Tests to Write
- [ ] Test each splitter independently
- [ ] Test balance calculations
- [ ] Test debt simplification
- [ ] Test rounding edge cases
- [ ] Test concurrent updates

### Edge Cases Handled
✅ Rounding errors  
✅ Empty splits list  
✅ Single user expense  
✅ Zero amount expense  
✅ Negative amounts (rejected)  
✅ Percentage not totaling 100% (rejected)  

## 📚 Additional Resources

- [DESIGN.md](./DESIGN.md) - Detailed design documentation
- [SplitwiseDemo.java](./SplitwiseDemo.java) - Comprehensive examples

## 🤝 Contributing

This is a learning project. Feel free to:
- Add more split types
- Improve algorithms
- Add features
- Write tests
- Improve documentation

## 📝 License

This is an educational project for learning Low Level Design concepts.

---

**Happy Learning! 🚀**

For detailed design decisions and trade-offs, see [DESIGN.md](./DESIGN.md).
