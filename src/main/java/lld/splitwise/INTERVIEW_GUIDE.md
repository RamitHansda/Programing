# Splitwise LLD - Interview Guide

## 🎯 How to Present This Design in an Interview

This guide will help you confidently explain the Splitwise LLD in interviews with a structured approach.

---

## 📋 Part 1: Requirements Gathering (5-7 minutes)

### Start with Clarifying Questions

**You:** "Let me clarify the requirements before I start designing. I have a few questions:"

#### Functional Requirements
1. **"What are the core features we need to support?"**
   - Add expenses (who paid, amount, participants)
   - Split bills in different ways
   - Track balances between users
   - Settle debts

2. **"What types of expense splits should we support?"**
   - Equal split (most common)
   - Exact amounts (each person pays different amount)
   - Percentage-based (business expenses)
   - Share/ratio-based (rent by room size)

3. **"Do we need to support groups?"**
   - Yes, like "Goa Trip" or "Roommates"
   - Groups have multiple expenses over time

4. **"Should we support settling debts?"**
   - Yes, record when someone pays someone back
   - Optionally: simplify complex debts (minimize transactions)

#### Non-Functional Requirements
5. **"What's the expected scale?"**
   - Start with single-server solution
   - Assume thousands of users, not millions (for LLD focus)

6. **"Do we need persistence?"**
   - Focus on in-memory for LLD
   - Can discuss database later

7. **"Any performance requirements?"**
   - Balance calculation should be fast (O(1) or O(n))
   - Debt simplification can be slower (O(n²) acceptable)

---

## 🏗️ Part 2: High-Level Design (3-5 minutes)

### Present Your Approach

**You:** "Based on these requirements, here's my high-level approach:"

### 1. Core Entities

```
┌──────────────────────────────────────────────────────┐
│                   Core Entities                       │
├──────────────────────────────────────────────────────┤
│                                                       │
│  User ─────► Expense ─────► Split                   │
│              (who paid)     (who owes how much)      │
│                                                       │
│  Group ────► Contains multiple expenses              │
│                                                       │
│  Balance ──► Tracks debt between two users           │
│                                                       │
│  Transaction ──► Records settlements                  │
└──────────────────────────────────────────────────────┘
```

**Explain:** 
- "The core entity is **Expense** with a payer and multiple **Splits**"
- "Each **Split** represents how much one user owes"
- "**Groups** organize related expenses"
- "**Balance** tracks net amounts between users"

### 2. Key Design Decision: Strategy Pattern

```
ExpenseSplitter (Interface)
    │
    ├── EqualSplitter      (split equally)
    ├── ExactSplitter      (exact amounts)
    ├── PercentageSplitter (by percentage)
    └── ShareSplitter      (by ratio)
```

**Explain:**
- "Different split types have different calculation logic"
- "Strategy pattern makes it extensible - easy to add new split types"
- "Each splitter validates and calculates independently"

### 3. Service Layer

```
SplitwiseService
    │
    ├── registerUser()
    ├── createGroup()
    ├── addExpense()          ← Uses ExpenseSplitter
    ├── getBalances()
    ├── recordSettlement()
    └── simplifyDebts()       ← Optimization algorithm
```

**Explain:**
- "Service layer orchestrates all operations"
- "Maintains balance sheet for quick lookups"
- "Provides clean API for clients"

---

## 💻 Part 3: Detailed Design (15-20 minutes)

### Walk Through Key Components

#### 1. Data Structures

**Balance Sheet Design:**
```java
Map<String, Map<String, Double>> balanceSheet
```

**Explain this carefully:**
```
Example:
balanceSheet = {
    "Alice": {
        "Bob": 500,      // Alice owes Bob ₹500
        "Charlie": -200  // Charlie owes Alice ₹200
    },
    "Bob": {
        "Alice": -500,   // Bob is owed ₹500 by Alice
        "Charlie": 100   // Bob owes Charlie ₹100
    }
}
```

**Key points:**
- "Positive value = user owes the other person"
- "Negative value = other person owes the user"
- "O(1) lookup for balance between any two users"
- "Trade-off: O(u²) space for u users, but fast queries"

#### 2. Adding an Expense - Step by Step

**You:** "Let me walk through what happens when we add an expense:"

```java
// Example: Alice paid ₹900 for dinner, split equally among 3
User alice = ..., bob = ..., charlie = ...;
List<Split> splits = [
    Split(alice, 0),
    Split(bob, 0),
    Split(charlie, 0)
];

addExpense("Dinner", 900, alice, splits, EQUAL, null);
```

**Step 1: Validation**
```java
- Verify payer is registered
- Verify all split users are registered
```

**Step 2: Calculate Split Amounts**
```java
- Factory creates EqualSplitter
- EqualSplitter calculates: 900 / 3 = 300 each
- Handles rounding: last person gets remainder
```

**Step 3: Create Expense**
```java
- Store expense with all details
- Add to group if specified
```

**Step 4: Update Balance Sheet**
```java
- Bob owes Alice: +300
- Charlie owes Alice: +300
- Skip Alice (paid for herself)
```

**Code snippet to show:**
```java
for (Split split : expense.getSplits()) {
    if (split.getUser() != paidBy) {
        balanceSheet[split.user][paidBy] += split.amount;
        balanceSheet[paidBy][split.user] -= split.amount;
    }
}
```

**Complexity:** O(n) where n = number of splits

#### 3. Rounding Strategy

**Interviewer might ask:** "How do you handle rounding?"

**You:** "Great question! Currency rounding is critical."

```java
// Example: ₹100 split among 3
// 100 / 3 = 33.333...

// My approach:
Person 1: ₹33.33
Person 2: ₹33.33
Person 3: ₹33.34  ← Gets the remainder

Total: 33.33 + 33.33 + 33.34 = 100.00 ✓
```

**Code:**
```java
for (int i = 0; i < splits.size(); i++) {
    if (i == splits.size() - 1) {
        // Last person gets remainder
        splits.get(i).setAmount(totalAmount - sum);
    } else {
        double amount = Math.round(splitAmount * 100.0) / 100.0;
        splits.get(i).setAmount(amount);
        sum += amount;
    }
}
```

**Key points:**
- "Ensures total always matches exactly"
- "Simple and predictable"
- "In production, use BigDecimal instead of double"

#### 4. Strategy Pattern Implementation

**Show the interface:**
```java
interface ExpenseSplitter {
    boolean validate(double total, List<Split> splits);
    void calculateSplits(double total, List<Split> splits);
}
```

**Example: EqualSplitter**
```java
class EqualSplitter implements ExpenseSplitter {
    boolean validate(double total, List<Split> splits) {
        return splits != null && !splits.isEmpty() && total > 0;
    }
    
    void calculateSplits(double total, List<Split> splits) {
        double amount = total / splits.size();
        // ... distribute with rounding
    }
}
```

**Why Strategy Pattern?**
- ✅ "Open-closed principle: can add new split types without modifying existing code"
- ✅ "Each strategy is independently testable"
- ✅ "Single responsibility: each splitter does one thing"
- ✅ "Client (Service) doesn't need to know split logic"

#### 5. Debt Simplification Algorithm

**Interviewer:** "Can you optimize the number of transactions needed to settle?"

**You:** "Absolutely! This is a classic graph problem. Let me explain my approach."

**Problem:**
```
Current state:
- Alice owes Bob ₹100
- Bob owes Charlie ₹100
- Charlie owes Alice ₹50

That's 3 transactions, but we can simplify!
```

**Solution: Greedy Algorithm**

```
Step 1: Calculate net balance for each user
Alice:  owes 100, owed 50  → net: +50 (debtor)
Bob:    owes 100, owed 100 → net: 0   (settled)
Charlie: owes 50, owed 100 → net: -50 (creditor)

Step 2: Match debtors with creditors
Alice owes ₹50 → Charlie is owed ₹50
Result: Alice pays Charlie ₹50 (only 1 transaction!)
```

**Algorithm:**
```java
1. Calculate net balance for each user: O(u²)
2. Separate into creditors and debtors: O(u)
3. Sort by absolute amount: O(u log u)
4. Greedily match largest debtor with largest creditor: O(u)

Total: O(u²)
```

**Code walkthrough:**
```java
// Calculate net balances
Map<User, Double> netBalance = calculateNetBalances(users);

// Separate
List<User> debtors = netBalance.filter(b -> b > 0);
List<User> creditors = netBalance.filter(b -> b < 0);

// Sort descending
debtors.sort(reverse);
creditors.sort(reverse);

// Match greedily
while (debtors and creditors not empty) {
    amount = min(debtor.balance, creditor.balance);
    addTransaction(debtor, creditor, amount);
    update balances;
    remove if settled;
}
```

**Key points:**
- "This is a greedy approach, not always optimal (NP-hard problem)"
- "But it's efficient O(u²) and gives good results"
- "For exact solution, need exponential algorithms"
- "In practice, greedy is sufficient"

---

## 🎤 Part 4: Common Interview Questions & Answers

### Q1: "Why did you choose Strategy Pattern?"

**Answer:**
"I chose Strategy Pattern for expense splitting because:
1. **Encapsulation**: Each split type has its own validation and calculation logic
2. **Extensibility**: Easy to add new split types (e.g., weighted split) without changing existing code
3. **Testability**: Each splitter can be unit tested independently
4. **SOLID principles**: Follows Open-Closed and Single Responsibility principles"

### Q2: "How would you scale this for millions of users?"

**Answer:**
"Great question! Here's my approach for scaling:

**Horizontal Scaling:**
- Partition users by userId (consistent hashing)
- Each shard handles subset of users
- Balance queries stay within shard (mostly)

**Caching:**
- Cache user balances in Redis
- Invalidate on new expense
- Cache hit ratio should be high (people check balances more than add expenses)

**Database:**
- User/Group tables with indexes
- Expense log (append-only for audit)
- Computed balances (denormalized for reads)
- CQRS: separate read/write models

**Async Processing:**
- Debt simplification runs asynchronously
- Use message queue for non-critical updates
- Eventually consistent balances okay for some use cases

**Read Replicas:**
- Balance queries go to read replicas
- Write to master for expenses"

### Q3: "How do you handle concurrent expense additions?"

**Answer:**
"Concurrency is critical for data consistency:

**Approach 1: Optimistic Locking**
```java
@Version
private long version;

// Update only if version matches
UPDATE balances SET amount = ?, version = version + 1
WHERE user_id = ? AND version = ?
```

**Approach 2: Database Transactions**
```java
@Transactional
public Expense addExpense(...) {
    // All balance updates in one transaction
    // ACID guarantees
}
```

**Approach 3: Event Sourcing**
```
Store events: ExpenseAdded, SettlementRecorded
Replay events to compute current state
Naturally handles concurrency
```

For this LLD, I'd use **database transactions** - simple and effective."

### Q4: "What if a user wants to edit an expense?"

**Answer:**
"Good catch! My current design doesn't support updates. Here's how I'd add it:

**Option 1: Revert and Reapply**
```java
public void updateExpense(String expenseId, Expense newExpense) {
    Expense old = getExpense(expenseId);
    
    // Revert old balance changes (inverse operation)
    revertBalances(old);
    
    // Apply new balance changes
    updateBalances(newExpense);
    
    // Update expense record
    expenses.put(expenseId, newExpense);
}
```

**Option 2: Audit Trail (Better)**
```java
// Never modify expenses, create adjustment
public void adjustExpense(String expenseId, Expense adjustment) {
    createAdjustmentExpense(expenseId, adjustment);
    // Maintains history, easier to debug
}
```

**Trade-offs:**
- Option 1: Clean, but loses history
- Option 2: More complex, but maintains audit trail
- For production: **Option 2** for compliance/transparency"

### Q5: "How do you handle multiple currencies?"

**Answer:**
"Multi-currency adds complexity:

**Data Model Changes:**
```java
class Expense {
    double amount;
    Currency currency;  // NEW
}

class Balance {
    Map<Currency, Double> amounts;  // Changed
}
```

**Exchange Rates:**
```java
class ExchangeRateService {
    double convert(double amount, Currency from, Currency to);
}
```

**Balance Calculation:**
```java
// Option 1: Store in original currency, convert on display
// Option 2: Normalize to base currency (e.g., USD)

// I'd choose Option 2:
- Simpler balance calculation
- Base currency for internal storage
- Convert to user's currency on display
```

**Challenges:**
- Exchange rates change over time
- Need to store rate at time of expense
- Rounding errors compound"

### Q6: "How do you ensure the system is testable?"

**Answer:**
"Testability is built into the design:

**1. Dependency Injection Ready**
```java
class SplitwiseService {
    private final ExpenseSplitterFactory factory;
    
    // Can inject mock factory for testing
    public SplitwiseService(ExpenseSplitterFactory factory) {
        this.factory = factory;
    }
}
```

**2. Strategy Pattern = Easy Mocking**
```java
// Test with mock splitter
ExpenseSplitter mockSplitter = mock(ExpenseSplitter.class);
when(factory.getSplitter(EQUAL)).thenReturn(mockSplitter);
```

**3. Unit Tests per Component**
- Test each splitter independently
- Test service layer with mock splitters
- Test balance calculations separately

**4. Integration Tests**
- Test complete flows (add expense → check balance)
- Test edge cases (rounding, empty splits)

**5. Test Pyramid**
```
       E2E (few)
    Integration (some)
  Unit Tests (many)
```

### Q7: "What are the limitations of your design?"

**Answer:**
"Good question! Here are known limitations:

**1. Memory-based**
- Current design: in-memory only
- Won't survive restarts
- Need database for production

**2. Balance Sheet Space**
- O(u²) space for u users
- Sparse in practice (most users don't interact)
- Could use sparse matrix or lazy computation

**3. Debt Simplification**
- Greedy algorithm, not optimal
- For exact solution: NP-hard
- Good enough for most cases

**4. No Expense Updates**
- Can't edit expenses currently
- Would need audit trail

**5. Single Currency**
- Only supports one currency
- Multi-currency needs more work

**6. No Permissions**
- Anyone can add expense to group
- Need authorization layer

But these are **known trade-offs** for an LLD scope. In production, we'd address them."

### Q8: "Walk me through the flow of a group trip expense."

**Answer:**
"Let me walk through a real example:

**Setup:**
```java
// 3 friends going to Goa
User alice = registerUser("Alice", ...);
User bob = registerUser("Bob", ...);
User charlie = registerUser("Charlie", ...);

Group trip = createGroup("Goa Trip 2026", alice);
addMemberToGroup(trip, bob);
addMemberToGroup(trip, charlie);
```

**Expense 1: Hotel (Alice pays)**
```java
// Alice books hotel: ₹15,000
addExpense(
    description: "Hotel Booking",
    amount: 15000,
    paidBy: alice,
    splits: [alice, bob, charlie],  // equal split
    type: EQUAL,
    groupId: trip.getId()
);

// After this:
// Bob owes Alice: ₹5,000
// Charlie owes Alice: ₹5,000
```

**Expense 2: Flights (Bob pays)**
```java
// Bob books flights: ₹18,000
addExpense(
    description: "Flight Tickets",
    amount: 18000,
    paidBy: bob,
    splits: [alice, bob, charlie],
    type: EQUAL,
    groupId: trip.getId()
);

// After this:
// Bob owes Alice: 5,000 - 6,000 = -1,000 (Alice owes Bob ₹1,000)
// Charlie owes Alice: ₹5,000
// Charlie owes Bob: ₹6,000
```

**Expense 3: Activities (Charlie pays)**
```java
// Charlie pays for activities: ₹9,000
addExpense(
    description: "Water Sports",
    amount: 9000,
    paidBy: charlie,
    splits: [alice, bob, charlie],
    type: EQUAL,
    groupId: trip.getId()
);

// Final balances:
// Alice owes Bob: ₹1,000
// Alice owes Charlie: ₹3,000
// Bob owes Charlie: ₹3,000
```

**Check Group Balances:**
```java
List<Balance> balances = getGroupBalances(trip.getId());
// Shows only balances between trip members
```

**Simplify Debts:**
```java
simplifyDebts([alice, bob, charlie]);
// Optimizes to minimum transactions
```

This demonstrates how expenses accumulate in a group and balances are automatically maintained."

---

## 📊 Part 5: Complexity Analysis

**Be ready to discuss:**

| Operation | Time | Space | Justification |
|-----------|------|-------|---------------|
| Add User | O(1) | O(1) | HashMap insert |
| Add Expense | O(n) | O(n) | n splits to process |
| Get Balance | O(1) | - | Direct HashMap lookup |
| User Balances | O(u) | O(u) | Iterate user's map |
| Group Balances | O(m²) | O(m) | m group members |
| Simplify Debts | O(u²) | O(u) | Balance calculation |
| Settlement | O(1) | O(1) | Direct update |

**Space Complexity:**
- Users: O(u)
- Expenses: O(e)
- Balance Sheet: O(u²) worst case, O(connections) in practice

---

## 🎯 Part 6: Code Walkthrough Tips

### If asked to write code on whiteboard/screen:

**Start with the interface:**
```java
interface ExpenseSplitter {
    void calculateSplits(double total, List<Split> splits);
}
```

**Then a simple implementation:**
```java
class EqualSplitter implements ExpenseSplitter {
    public void calculateSplits(double total, List<Split> splits) {
        int n = splits.size();
        double each = total / n;
        double sum = 0;
        
        for (int i = 0; i < n; i++) {
            if (i == n - 1) {
                splits.get(i).setAmount(total - sum);
            } else {
                double amt = Math.round(each * 100) / 100.0;
                splits.get(i).setAmount(amt);
                sum += amt;
            }
        }
    }
}
```

**Then show usage:**
```java
class SplitwiseService {
    public Expense addExpense(...) {
        // 1. Get splitter
        ExpenseSplitter splitter = factory.getSplitter(type);
        
        // 2. Calculate splits
        splitter.calculateSplits(amount, splits);
        
        // 3. Create expense
        Expense expense = new Expense(...);
        
        // 4. Update balances
        updateBalances(expense);
        
        return expense;
    }
}
```

---

## 🗣️ Part 7: How to Communicate

### Tone & Approach

**DO:**
- ✅ Think out loud: "I'm considering two approaches..."
- ✅ Ask clarifying questions: "Should we support...?"
- ✅ Explain trade-offs: "This gives us O(1) lookup but uses O(n²) space..."
- ✅ Draw diagrams: Entity relationships, flow diagrams
- ✅ Start simple, then optimize: "Let's start with basic version, then add..."
- ✅ Acknowledge limitations: "This doesn't handle X, but we could add..."

**DON'T:**
- ❌ Jump straight to code without discussion
- ❌ Assume requirements without asking
- ❌ Over-engineer initially
- ❌ Say "I don't know" without reasoning
- ❌ Get defensive about design choices

### Example Phrases

**When explaining decisions:**
- "I chose X because..."
- "The trade-off here is..."
- "An alternative would be..., but..."

**When unsure:**
- "Let me think about this for a moment..."
- "I see two approaches here..."
- "That's an interesting edge case, let me consider..."

**When asked difficult questions:**
- "That's a great question. In a production system, I would..."
- "I haven't implemented that yet, but here's how I'd approach it..."

---

## ⏱️ Part 8: Time Management (45-minute interview)

```
0-5 min:   Requirements gathering & clarifications
5-10 min:  High-level design, entities, patterns
10-30 min: Detailed design, code walkthrough
30-40 min: Questions, edge cases, scaling
40-45 min: Wrap up, your questions
```

**Pace yourself:**
- Don't spend 30 minutes on one component
- If running out of time: "Let me briefly cover..."
- Prioritize: Core functionality > Nice-to-haves

---

## 🎓 Part 9: What Interviewers Look For

### They're evaluating:

1. **Problem Understanding**
   - Did you ask good questions?
   - Did you clarify ambiguities?

2. **Design Skills**
   - Appropriate entities and relationships
   - Good use of design patterns
   - Clean separation of concerns

3. **Coding Ability**
   - Can you translate design to code?
   - Handle edge cases?
   - Clean, readable code?

4. **Trade-off Analysis**
   - Understand pros/cons of decisions
   - Consider alternatives
   - Think about scale

5. **Communication**
   - Explain clearly
   - Respond to feedback
   - Think out loud

6. **Depth of Knowledge**
   - Understand algorithms (debt simplification)
   - Know data structures (why HashMap?)
   - Awareness of production concerns

---

## 🚀 Part 10: Practice Script

**Practice saying out loud:**

### Opening (30 seconds)
"Thanks for the problem! Splitwise is an expense-sharing app. Before I start, let me clarify a few things. What features are most important? Should we support groups? What split types do we need?"

### High-Level Design (2 minutes)
"Based on that, I see these core entities: User, Expense, Group, and Split. An Expense has a payer and multiple Splits showing who owes how much. For different split types, I'll use Strategy Pattern - this keeps the code extensible and clean."

### Detailed Design (5 minutes)
"Let me walk through adding an expense. First, we validate inputs. Then we use the appropriate splitter strategy to calculate amounts - for example, EqualSplitter divides total by number of people. Finally, we update our balance sheet, which is a nested map giving us O(1) balance lookups."

### Trade-offs (2 minutes)
"The balance sheet uses O(u²) space in the worst case, but gives us constant-time queries. We could use a sparse representation if memory is a concern, but queries would be slower."

### Scaling (2 minutes)
"To scale, we'd partition by user ID, add caching for balances, and use read replicas. Debt simplification can run asynchronously since it's not real-time critical."

---

## 📚 Part 11: Related Problems to Mention

Show breadth of knowledge:

1. **"This is similar to double-entry bookkeeping in accounting"**
   - Every transaction has two sides
   - Debits and credits must balance

2. **"The debt simplification is like the graph settlement problem"**
   - Directed graph with weighted edges
   - Finding minimum spanning tree

3. **"Balance calculation is like matrix operations"**
   - Could represent as adjacency matrix
   - Sparse matrix optimizations

4. **"This relates to the change-making problem"**
   - Minimize number of transactions
   - Greedy vs dynamic programming

---

## ✅ Final Checklist

Before the interview, ensure you can:

- [ ] Draw entity diagram from memory
- [ ] Explain Strategy Pattern and why you used it
- [ ] Code at least one splitter from scratch
- [ ] Explain balance sheet data structure
- [ ] Walk through adding an expense step-by-step
- [ ] Discuss rounding strategy
- [ ] Explain debt simplification algorithm
- [ ] Answer "how to scale?" question
- [ ] Handle "what if expense needs to be edited?"
- [ ] Discuss time/space complexity of key operations

---

## 💡 Pro Tips

1. **Draw a lot**: Entity diagrams, flow charts, balance sheet structure
2. **Use examples**: "Let's say Alice, Bob, and Charlie go to dinner..."
3. **Start simple**: "Let's start with equal split, then add others"
4. **Show iteration**: "In version 1, we'll do X. Later we can optimize to Y"
5. **Be honest**: If you don't know something, say how you'd figure it out
6. **Stay calm**: If you make a mistake, correct it gracefully

---

## 🎯 Remember

**The interviewer wants to see:**
- Your thought process (not just the final answer)
- How you handle ambiguity
- Your communication skills
- How you respond to feedback
- Your depth of knowledge

**Be confident!** You have a solid design here. Now practice explaining it clearly.

---

## 🎤 Mock Interview Questions

Practice answering these:

1. Design Splitwise expense sharing system
2. How would you split a bill four ways?
3. Optimize: minimize transactions to settle all debts
4. How to handle groups with ongoing expenses?
5. Scale to millions of users
6. Add support for multiple currencies
7. Handle editing/deleting expenses
8. Add recurring expenses (subscriptions)
9. Implement "remind friend" feature
10. Add expense categories and analytics

---

**Good luck with your interview! 🚀**

You have a solid design, you understand the trade-offs, and you can explain it clearly. You've got this!
