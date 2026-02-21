# Splitwise - Low Level Design

## Table of Contents
1. [Overview](#overview)
2. [Requirements](#requirements)
3. [Core Entities](#core-entities)
4. [Design Patterns](#design-patterns)
5. [Class Diagram](#class-diagram)
6. [Key Components](#key-components)
7. [Algorithms](#algorithms)
8. [API Design](#api-design)
9. [Use Cases](#use-cases)
10. [Complexity Analysis](#complexity-analysis)
11. [Trade-offs](#trade-offs)
12. [Future Enhancements](#future-enhancements)

---

## Overview

Splitwise is an expense-sharing application that allows users to:
- Split bills and expenses with friends
- Track who owes whom
- Settle debts efficiently
- Manage group expenses
- Simplify complex debt chains

---

## Requirements

### Functional Requirements
1. **User Management**
   - Register users with name, email, phone
   - Each user has a unique ID

2. **Expense Management**
   - Add expenses with multiple split types
   - Support for individual and group expenses
   - Track who paid and how much each person owes

3. **Split Types**
   - **Equal**: Split amount equally among all participants
   - **Exact**: Specify exact amount for each participant
   - **Percentage**: Split by percentage (must total 100%)
   - **Share**: Split by ratio (e.g., 1:2:3)

4. **Group Management**
   - Create groups for shared expenses
   - Add/remove members
   - Track group expenses separately

5. **Balance Tracking**
   - Real-time balance calculation
   - View balances between any two users
   - View all balances for a user
   - View group balances

6. **Settlement**
   - Record payments between users
   - Simplify debts (minimize transactions)

### Non-Functional Requirements
1. **Performance**: Fast balance calculations
2. **Scalability**: Handle thousands of users and expenses
3. **Accuracy**: Precise currency calculations (handle rounding)
4. **Maintainability**: Clean, extensible code

---

## Core Entities

### 1. User
```java
- id: String (UUID)
- name: String
- email: String
- phoneNumber: String
```

### 2. Expense
```java
- id: String (UUID)
- description: String
- totalAmount: double
- paidBy: User
- splits: List<Split>
- expenseType: ExpenseType
- createdAt: LocalDateTime
- groupId: String (nullable)
```

### 3. Split
```java
- user: User
- amount: double
```

### 4. Group
```java
- id: String (UUID)
- name: String
- members: List<User>
- expenses: List<Expense>
- createdAt: LocalDateTime
- createdBy: User
```

### 5. Balance
```java
- user1: User
- user2: User
- amount: double (positive = user1 owes user2)
```

### 6. Transaction
```java
- id: String (UUID)
- paidBy: User
- paidTo: User
- amount: double
- timestamp: LocalDateTime
- description: String
```

---

## Design Patterns

### 1. Strategy Pattern
**Used for**: Expense splitting strategies

Different split types (Equal, Exact, Percentage, Share) implement the `ExpenseSplitter` interface.

```
ExpenseSplitter (Interface)
    ↑
    |
    +-- EqualSplitter
    +-- ExactSplitter
    +-- PercentageSplitter
    +-- ShareSplitter
```

**Benefits**:
- Easy to add new split types
- Each strategy is independently testable
- Open-closed principle: open for extension, closed for modification

### 2. Factory Pattern
**Used for**: Creating appropriate splitter based on expense type

```java
ExpenseSplitterFactory.getSplitter(ExpenseType type)
```

**Benefits**:
- Centralized creation logic
- Easy to maintain splitter instantiation

### 3. Service Layer Pattern
**Used for**: Business logic encapsulation

`SplitwiseService` acts as a facade for all operations, managing:
- User registration
- Group management
- Expense creation
- Balance calculations
- Settlement tracking

---

## Class Diagram

```
┌─────────────────┐
│      User       │
├─────────────────┤
│ - id            │
│ - name          │
│ - email         │
│ - phoneNumber   │
└─────────────────┘
        ↑
        |
        | participates in
        |
┌─────────────────┐         ┌──────────────────┐
│     Expense     │◆────────│      Split       │
├─────────────────┤         ├──────────────────┤
│ - id            │         │ - user           │
│ - description   │         │ - amount         │
│ - totalAmount   │         └──────────────────┘
│ - paidBy        │
│ - splits        │
│ - expenseType   │
│ - groupId       │
└─────────────────┘
        ↑
        |
        | contains
        |
┌─────────────────┐
│      Group      │
├─────────────────┤
│ - id            │
│ - name          │
│ - members       │
│ - expenses      │
└─────────────────┘

┌──────────────────────┐
│  ExpenseSplitter     │ (Interface)
├──────────────────────┤
│ + validate()         │
│ + calculateSplits()  │
└──────────────────────┘
          ↑
          |
   ┌──────┴──────┬──────────────┬──────────────┐
   |             |              |              |
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│  Equal   │ │  Exact   │ │Percentage│ │  Share   │
│ Splitter │ │ Splitter │ │ Splitter │ │ Splitter │
└──────────┘ └──────────┘ └──────────┘ └──────────┘

┌─────────────────────────┐
│   SplitwiseService      │
├─────────────────────────┤
│ - users                 │
│ - groups                │
│ - expenses              │
│ - transactions          │
│ - balanceSheet          │
├─────────────────────────┤
│ + registerUser()        │
│ + createGroup()         │
│ + addExpense()          │
│ + recordSettlement()    │
│ + getBalances()         │
│ + simplifyDebts()       │
└─────────────────────────┘
```

---

## Key Components

### 1. ExpenseSplitter Interface
Defines the contract for splitting strategies:

```java
boolean validate(double totalAmount, List<Split> splits)
void calculateSplits(double totalAmount, List<Split> splits)
```

### 2. Balance Sheet
Internal data structure in `SplitwiseService`:

```java
Map<String, Map<String, Double>> balanceSheet
```

- Outer map: userId → inner map
- Inner map: otherUserId → balance amount
- Positive balance: user owes other user
- Negative balance: other user owes user

### 3. Debt Simplification Algorithm
**Greedy approach** to minimize number of transactions:

1. Calculate net balance for each user
2. Separate into creditors (owed money) and debtors (owe money)
3. Match largest creditor with largest debtor
4. Settle as much as possible
5. Repeat until all balances are settled

---

## Algorithms

### 1. Balance Calculation (After Expense)

```
For each split in expense:
    if split.user != paidBy:
        balanceSheet[split.user][paidBy] += split.amount
        balanceSheet[paidBy][split.user] -= split.amount
```

**Time Complexity**: O(n) where n = number of splits

### 2. Debt Simplification

```
Input: List of users
Output: List of simplified transactions

1. Calculate net balance for each user: O(u²) where u = users
2. Separate creditors and debtors: O(u)
3. Sort by amount: O(u log u)
4. Match and settle: O(u)

Total: O(u² + u log u) = O(u²)
```

**Example**:
```
Before:
- A owes B: ₹100
- B owes C: ₹100
- C owes A: ₹50

After simplification:
- A owes C: ₹50
```

### 3. Rounding Handling

All splitters handle rounding by:
- Rounding individual amounts to 2 decimal places
- Giving remainder to last person to ensure total matches exactly

```java
for (int i = 0; i < splits.size(); i++) {
    if (i == splits.size() - 1) {
        splits.get(i).setAmount(totalAmount - sum);
    } else {
        double amount = Math.round(splitAmount * 100.0) / 100.0;
        splits.get(i).setAmount(amount);
        sum += amount;
    }
}
```

---

## API Design

### User Management
```java
User registerUser(String name, String email, String phoneNumber)
```

### Group Management
```java
Group createGroup(String name, User creator)
void addMemberToGroup(String groupId, User user)
```

### Expense Management
```java
Expense addExpense(
    String description,
    double totalAmount,
    User paidBy,
    List<Split> splits,
    ExpenseType type,
    String groupId
)
```

### Balance Queries
```java
double getBalance(User user1, User user2)
List<Balance> getUserBalances(User user)
List<Balance> getAllBalances()
List<Balance> getGroupBalances(String groupId)
```

### Settlement
```java
Transaction recordSettlement(
    User paidBy,
    User paidTo,
    double amount,
    String description
)

List<Transaction> simplifyDebts(List<User> participants)
```

---

## Use Cases

### Use Case 1: Split Dinner Bill Equally
```java
User alice = splitwise.registerUser("Alice", "alice@ex.com", "1111");
User bob = splitwise.registerUser("Bob", "bob@ex.com", "2222");
User charlie = splitwise.registerUser("Charlie", "charlie@ex.com", "3333");

List<Split> splits = Arrays.asList(
    new Split(alice, 0),
    new Split(bob, 0),
    new Split(charlie, 0)
);

Expense expense = splitwise.addExpense(
    "Dinner",
    900.0,
    alice,
    splits,
    ExpenseType.EQUAL,
    null
);

// Result: Bob and Charlie each owe Alice ₹300
```

### Use Case 2: Group Trip Expenses
```java
Group trip = splitwise.createGroup("Goa Trip", alice);
splitwise.addMemberToGroup(trip.getId(), bob);
splitwise.addMemberToGroup(trip.getId(), charlie);

// Hotel by Alice
splitwise.addExpense("Hotel", 15000.0, alice, splits, EQUAL, trip.getId());

// Flights by Bob
splitwise.addExpense("Flights", 18000.0, bob, splits, EQUAL, trip.getId());

// Get group balances
List<Balance> balances = splitwise.getGroupBalances(trip.getId());
```

### Use Case 3: Settle Up
```java
// Bob pays Alice
Transaction settlement = splitwise.recordSettlement(
    bob,
    alice,
    500.0,
    "Cash payment"
);

// Balance is updated automatically
```

### Use Case 4: Simplify Complex Debts
```java
// Multiple expenses create circular debts
// A → B: 100, B → C: 100, C → A: 50

List<Transaction> simplified = splitwise.simplifyDebts(
    Arrays.asList(userA, userB, userC)
);

// Result: Only 1 transaction: A → C: 50
```

---

## Complexity Analysis

### Space Complexity
- **Users**: O(U)
- **Groups**: O(G)
- **Expenses**: O(E)
- **Balance Sheet**: O(U²) - worst case, all users have balances with each other

### Time Complexity

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Register User | O(1) | HashMap insert |
| Create Group | O(1) | HashMap insert |
| Add Expense | O(S) | S = number of splits |
| Get User Balance | O(U) | U = number of users |
| Get Group Balance | O(M²) | M = group members |
| Simplify Debts | O(U²) | Worst case balance calculation |
| Record Settlement | O(1) | Direct update |

---

## Trade-offs

### 1. Balance Sheet Design
**Chosen**: `Map<String, Map<String, Double>>`

**Pros**:
- O(1) lookup for balance between two users
- Easy to update after expense
- Clear ownership of debt

**Cons**:
- O(U²) space in worst case
- Redundant data (A→B stored in both A's and B's maps)

**Alternative**: Store only net balances
- Less space but harder to track individual relationships

### 2. Rounding Strategy
**Chosen**: Last person gets remainder

**Pros**:
- Ensures total always matches exactly
- Simple to implement

**Cons**:
- Last person might pay slightly more/less

**Alternative**: Distribute rounding error randomly
- More "fair" but complex

### 3. Debt Simplification
**Chosen**: Greedy algorithm

**Pros**:
- O(U²) time complexity
- Simple to understand
- Good enough for most cases

**Cons**:
- Not always optimal (NP-hard problem)

**Alternative**: Exact algorithms
- Better optimization but exponential time

---

## Future Enhancements

### 1. Persistence Layer
- Add database support (MySQL, PostgreSQL)
- Implement repository pattern
- Transaction management

### 2. Currency Support
- Multi-currency expenses
- Exchange rate handling
- Currency conversion

### 3. Advanced Features
- Recurring expenses
- Bill splitting with tax/tip
- Receipt scanning (OCR)
- Payment integration (UPI, PayPal)

### 4. Notifications
- Email/SMS notifications for new expenses
- Reminders for pending settlements
- Group activity updates

### 5. Advanced Debt Simplification
- Consider multiple currencies
- Optimize across multiple groups
- Historical debt patterns

### 6. Analytics
- Expense categorization
- Spending patterns
- Monthly reports
- Group spending analytics

### 7. Security
- User authentication
- Authorization (who can add expenses to groups)
- Data encryption
- Audit logs

### 8. Optimizations
- Cache frequently accessed balances
- Batch updates for multiple expenses
- Lazy debt simplification (only when requested)

---

## Interview Discussion Points

### 1. Why Strategy Pattern for Splitters?
- Each split type has different logic
- Easy to test independently
- Can add new types without modifying existing code
- Follows Single Responsibility Principle

### 2. How to handle currency precision?
- Use `BigDecimal` for production (currently using `double`)
- Round to 2 decimal places consistently
- Last person gets remainder to ensure exact total

### 3. How to scale the system?
- **Horizontal scaling**: Partition users by ID
- **Caching**: Cache user balances (invalidate on expense)
- **Event sourcing**: Store events instead of current state
- **CQRS**: Separate read/write models

### 4. How to handle concurrent updates?
- Add locking mechanism (optimistic or pessimistic)
- Use database transactions
- Event ordering in distributed system

### 5. What if expense amount changes after creation?
- Current design doesn't support updates
- Enhancement: Add `updateExpense()` method
- Revert old balance changes, apply new ones
- Consider audit trail

### 6. How to optimize debt simplification?
- Current O(U²) is acceptable for most cases
- For large systems, run asynchronously
- Cache results, invalidate on new expense
- Consider approximation algorithms

---

## Code Quality Features

### 1. Immutability
- Entity IDs are final
- Use defensive copies in getters

### 2. Validation
- All splitters validate input
- Service layer validates business rules
- Fail fast with clear error messages

### 3. Error Handling
- IllegalArgumentException for invalid inputs
- Clear error messages
- Validation before state changes

### 4. Clean Code
- Single Responsibility Principle
- Meaningful names
- Small, focused methods
- Comprehensive documentation

### 5. Testing
- Easy to unit test (dependency injection ready)
- Each splitter can be tested independently
- Service layer mockable

---

## Summary

This Splitwise LLD demonstrates:
- ✅ Clean object-oriented design
- ✅ Proper use of design patterns
- ✅ Efficient algorithms
- ✅ Scalable architecture
- ✅ Production-ready features
- ✅ Interview-ready explanations

The design balances **simplicity** with **extensibility**, making it easy to understand while allowing for future enhancements.
