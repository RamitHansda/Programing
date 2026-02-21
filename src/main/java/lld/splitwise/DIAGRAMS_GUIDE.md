# Splitwise LLD - Diagrams for Interview

## 🎨 Visual Guide: What to Draw During Interview

These diagrams will help you explain your design clearly. Practice drawing these!

---

## 📊 Diagram 1: Entity Relationship (Draw First!)

```
┌─────────────────┐
│      User       │
├─────────────────┤
│ - id: String    │
│ - name          │
│ - email         │
│ - phone         │
└─────────────────┘
        │
        │ participates in
        │
        ▼
┌─────────────────┐         ┌──────────────────┐
│    Expense      │ 1    n  │      Split       │
├─────────────────┤◆────────┤──────────────────┤
│ - id            │         │ - user: User     │
│ - description   │         │ - amount: double │
│ - totalAmount   │         └──────────────────┘
│ - paidBy: User  │
│ - splits[]      │
│ - type          │
│ - groupId       │
└─────────────────┘
        │
        │ belongs to
        │
        ▼
┌─────────────────┐
│     Group       │
├─────────────────┤
│ - id            │
│ - name          │
│ - members[]     │
│ - expenses[]    │
└─────────────────┘

┌─────────────────┐
│   Transaction   │
├─────────────────┤
│ - paidBy: User  │
│ - paidTo: User  │
│ - amount        │
│ - timestamp     │
└─────────────────┘
```

**When to draw:** First 5 minutes when discussing entities

**What to say:** "Here are the core entities. Expense is central - it has a payer and multiple Splits showing who owes how much. Groups contain multiple expenses."

---

## 🎯 Diagram 2: Strategy Pattern (Draw Second!)

```
┌──────────────────────────┐
│   ExpenseSplitter        │ ◄─── Interface
│  (Strategy Interface)    │
├──────────────────────────┤
│ + validate()             │
│ + calculateSplits()      │
└──────────────────────────┘
            △
            │ implements
            │
    ┌───────┴───────┬───────────┬──────────┐
    │               │           │          │
┌───────────┐  ┌───────────┐  ┌──────────┐  ┌───────────┐
│  Equal    │  │  Exact    │  │Percentage│  │  Share    │
│ Splitter  │  │ Splitter  │  │ Splitter │  │ Splitter  │
├───────────┤  ├───────────┤  ├──────────┤  ├───────────┤
│900/3      │  │Specified  │  │Total *   │  │Total *    │
│= 300 each │  │amounts    │  │percent   │  │ratio      │
└───────────┘  └───────────┘  └──────────┘  └───────────┘

         │
         │ created by
         ▼
┌──────────────────────────┐
│ ExpenseSplitterFactory   │
├──────────────────────────┤
│ + getSplitter(type)      │
└──────────────────────────┘
```

**When to draw:** When explaining "Why Strategy Pattern?"

**What to say:** "Different split types need different logic. Strategy pattern keeps them separate and makes it easy to add new types. The factory creates the right splitter based on expense type."

---

## 🗄️ Diagram 3: Balance Sheet Structure

```
Balance Sheet: Map<String, Map<String, Double>>

┌──────────────────────────────────────────────────┐
│             Balance Sheet                         │
├──────────────────────────────────────────────────┤
│                                                   │
│  "Alice" ───► { "Bob": 500, "Charlie": -200 }   │
│                   │              │               │
│                   │              └──── negative  │
│                   └──── positive      means      │
│                        means          Charlie    │
│                        Alice          owes Alice │
│                        owes Bob                  │
│                                                   │
│  "Bob" ───► { "Alice": -500, "Charlie": 100 }   │
│                                                   │
│  "Charlie" ───► { "Alice": 200, "Bob": -100 }   │
│                                                   │
└──────────────────────────────────────────────────┘

Key:
• Positive: This user owes the other user
• Negative: The other user owes this user
• Lookup: O(1) time
• Space: O(u²) worst case
```

**When to draw:** When discussing data structures

**What to say:** "I use a nested HashMap. Outer map is userId, inner map tracks what they owe each person. Positive means they owe, negative means they're owed. This gives O(1) balance lookups."

---

## 🔄 Diagram 4: Add Expense Flow

```
addExpense(desc, amount, paidBy, splits[], type, groupId)
    │
    ├─► 1. VALIDATE
    │   ├─ Payer registered?
    │   ├─ All users registered?
    │   └─ Amount > 0?
    │
    ├─► 2. GET SPLITTER
    │   └─ Factory.getSplitter(type)
    │
    ├─► 3. CALCULATE SPLITS
    │   └─ splitter.calculateSplits(amount, splits)
    │       │
    │       └─► EqualSplitter: 900/3 = 300 each
    │
    ├─► 4. CREATE EXPENSE
    │   └─ new Expense(desc, amount, paidBy, splits, type)
    │
    ├─► 5. ADD TO GROUP (if groupId provided)
    │   └─ group.addExpense(expense)
    │
    └─► 6. UPDATE BALANCES
        └─ For each split:
            if (split.user != paidBy) {
                balance[split.user][paidBy] += split.amount
                balance[paidBy][split.user] -= split.amount
            }

Time Complexity: O(n) where n = number of splits
```

**When to draw:** When walking through "How does adding an expense work?"

**What to say:** "Here's the flow: validate inputs, get the right splitter from factory, calculate amounts, create expense, and update our balance sheet. It's O(n) for n splits."

---

## 💰 Diagram 5: Rounding Strategy

```
Problem: Split ₹100 among 3 people

                ┌──────────────┐
                │  Total: 100  │
                └──────┬───────┘
                       │ ÷ 3
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
    Person 1       Person 2       Person 3
    ₹33.33         ₹33.33         ₹33.34
                                   ↑
                            Gets remainder!

Calculation:
1. person1 = round(100/3) = 33.33
   sum = 33.33

2. person2 = round(100/3) = 33.33
   sum = 66.66

3. person3 = 100 - 66.66 = 33.34 ✓

Total: 33.33 + 33.33 + 33.34 = 100.00 (exact!)
```

**When to draw:** When asked "How do you handle rounding?"

**What to say:** "For rounding, I calculate individual amounts rounded to 2 decimals, then give the remainder to the last person. This ensures the total always matches exactly."

---

## 📈 Diagram 6: Debt Simplification Algorithm

```
BEFORE:
    ┌─────┐  owes 100  ┌─────┐
    │Alice├───────────►│ Bob │
    └─────┘            └──┬──┘
       ▲                  │ owes 100
       │                  ▼
       │               ┌─────────┐
       └───────────────┤ Charlie │
         owes 50       └─────────┘

Step 1: Calculate Net Balances
    Alice:   owes 100, owed 50  → net: +50 (debtor)
    Bob:     owes 100, owed 100 → net: 0   (settled)
    Charlie: owes 50,  owed 100 → net: -50 (creditor)

Step 2: Separate Debtors & Creditors
    Debtors:   [Alice: +50]
    Creditors: [Charlie: -50]

Step 3: Match Greedy
    Alice (owes 50) + Charlie (owed 50) = Perfect match!

AFTER:
    ┌─────┐  pays 50  ┌─────────┐
    │Alice├──────────►│ Charlie │
    └─────┘           └─────────┘

Result: 1 transaction instead of 3! ✓

Time: O(u²) for balance calculation
```

**When to draw:** When asked about debt simplification

**What to say:** "I use a greedy algorithm. First calculate each user's net balance - who owes overall and who's owed overall. Then match largest debtor with largest creditor repeatedly. This minimizes transactions."

---

## 🏗️ Diagram 7: System Architecture

```
┌─────────────────────────────────────────────────┐
│                  Client Layer                    │
│         (Mobile App, Web App, API)              │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│            SplitwiseService                      │
│             (Service Layer)                      │
├─────────────────────────────────────────────────┤
│ • registerUser()                                │
│ • createGroup()                                 │
│ • addExpense()  ─────┐                         │
│ • getBalances()      │                         │
│ • recordSettlement() │                         │
│ • simplifyDebts()    │                         │
└──────────────────────┼─────────────────────────┘
                       │
                       ▼
          ┌────────────────────────┐
          │ExpenseSplitterFactory  │
          └────────────┬───────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
    ┌────────┐    ┌────────┐    ┌────────┐
    │ Equal  │    │ Exact  │    │Percent │
    └────────┘    └────────┘    └────────┘

┌─────────────────────────────────────────────────┐
│              Data Layer                          │
├─────────────────────────────────────────────────┤
│ • Map<String, User> users                       │
│ • Map<String, Group> groups                     │
│ • List<Expense> expenses                        │
│ • Map<String, Map<String, Double>> balanceSheet│
└─────────────────────────────────────────────────┘
```

**When to draw:** When discussing overall architecture

**What to say:** "The architecture has three layers: Client layer calls the Service layer, which uses Strategy pattern for splits, and manages the Data layer with our balance sheet."

---

## 👥 Diagram 8: Group Expense Example

```
Scenario: 3 friends go to Goa

┌──────────────────────────────────────────────┐
│         Group: "Goa Trip 2026"               │
│         Members: Alice, Bob, Charlie         │
└──────────────────────────────────────────────┘

Expense 1: Hotel (Alice pays ₹15,000)
    ┌─────┐  paid  ┌──────────┐
    │Alice├───────►│  ₹15,000 │
    └─────┘        └────┬─────┘
                        │ ÷ 3 (equal)
            ┌───────────┼───────────┐
            ▼           ▼           ▼
         ₹5,000      ₹5,000      ₹5,000
         Alice        Bob       Charlie

    Balances after:
    Bob → Alice: +₹5,000
    Charlie → Alice: +₹5,000

Expense 2: Flights (Bob pays ₹18,000)
    ┌─────┐  paid  ┌──────────┐
    │ Bob ├───────►│  ₹18,000 │
    └─────┘        └────┬─────┘
                        │ ÷ 3 (equal)
            ┌───────────┼───────────┐
            ▼           ▼           ▼
         ₹6,000      ₹6,000      ₹6,000
         Alice        Bob       Charlie

    Balances after both expenses:
    Alice → Bob: +₹1,000  (owes 6K, owed 5K)
    Charlie → Alice: +₹5,000
    Charlie → Bob: +₹6,000

Final State:
    Alice owes Bob ₹1,000
    Charlie owes Alice ₹5,000
    Charlie owes Bob ₹6,000
```

**When to draw:** For a concrete example walkthrough

**What to say:** "Let me show a real example. Three friends create a trip group. As expenses are added, balances automatically update. Each person can see exactly what they owe."

---

## 🔄 Diagram 9: Settlement Flow

```
BEFORE Settlement:
    ┌─────┐  owes 500  ┌─────┐
    │ Bob ├───────────►│Alice│
    └─────┘            └─────┘

recordSettlement(Bob, Alice, 300)
    │
    ├─► 1. Validate users exist
    ├─► 2. Validate amount > 0
    ├─► 3. Create Transaction record
    └─► 4. Update balances:
            balance[Bob][Alice] -= 300
            balance[Alice][Bob] += 300

AFTER Settlement:
    ┌─────┐  owes 200  ┌─────┐
    │ Bob ├───────────►│Alice│
    └─────┘            └─────┘

    Transaction recorded:
    "Bob paid Alice ₹300"
```

**When to draw:** When explaining settlements

**What to say:** "When someone settles up, we record a transaction and update balances in both directions. The transaction creates an audit trail."

---

## 🎯 Diagram 10: Complexity Summary Table

```
┌──────────────────────┬──────────┬────────────┐
│     Operation        │   Time   │   Space    │
├──────────────────────┼──────────┼────────────┤
│ Register User        │   O(1)   │    O(1)    │
│ Create Group         │   O(1)   │    O(1)    │
│ Add Expense          │   O(n)   │    O(n)    │
│ Get Balance (2 users)│   O(1)   │     -      │
│ Get User Balances    │   O(u)   │    O(u)    │
│ Get Group Balances   │   O(m²)  │    O(m)    │
│ Record Settlement    │   O(1)   │    O(1)    │
│ Simplify Debts       │   O(u²)  │    O(u)    │
└──────────────────────┴──────────┴────────────┘

Space Complexity:
• Users:         O(u)
• Expenses:      O(e)
• Balance Sheet: O(u²) worst case
                 O(connections) in practice

n = splits, u = users, m = group members, e = expenses
```

**When to draw:** When asked about complexity

**What to say:** "Here are the key operations and their complexity. The balance sheet is O(u²) worst case, but most operations are constant or linear time."

---

## 📝 Quick Drawing Tips

### Before You Draw:
1. ✅ Ask: "Can I use the whiteboard to explain?"
2. ✅ Leave space for annotations
3. ✅ Use clear, simple boxes and arrows

### While Drawing:
1. ✅ Draw and explain simultaneously
2. ✅ Keep it neat but don't stress perfection
3. ✅ Use consistent notation

### Symbols to Use:
```
┌─────┐  Box (entity/class)
├─────┤  Separator line
│     │  Vertical line
─────   Horizontal line
───►    Arrow (relationship)
△       Triangle (inheritance)
◆       Diamond (composition)
```

---

## 🎯 Which Diagram When?

| Interview Stage | Draw This |
|----------------|-----------|
| **Initial design (5-10 min)** | Diagram 1 (Entities) |
| **Explaining patterns (10-15 min)** | Diagram 2 (Strategy Pattern) |
| **Data structures (15-20 min)** | Diagram 3 (Balance Sheet) |
| **Flow explanation (20-25 min)** | Diagram 4 (Add Expense Flow) |
| **Edge cases (25-30 min)** | Diagram 5 (Rounding) |
| **Optimization question** | Diagram 6 (Debt Simplification) |
| **Architecture question** | Diagram 7 (System Architecture) |
| **Concrete example** | Diagram 8 (Group Example) |
| **Complexity question** | Diagram 10 (Complexity Table) |

---

## 💡 Pro Drawing Tips

1. **Start with a box in the center** (usually Expense)
2. **Add related entities around it** (User, Split, Group)
3. **Draw relationships with arrows** (label them!)
4. **Use examples with actual numbers** (₹900, 3 people)
5. **Annotate with complexity** (O(1), O(n))
6. **Use colors if available** (but not required)

---

## 🎯 Practice Exercise

**Before interview, practice drawing these 3 diagrams in under 2 minutes each:**

1. **Entity Relationship** (Diagram 1)
2. **Strategy Pattern** (Diagram 2)
3. **Add Expense Flow** (Diagram 4)

These three will cover 80% of interview discussions!

---

## 🚀 Remember

**Visual communication is powerful!**

- Diagrams make complex concepts clear
- Shows you can think structurally
- Helps interviewer follow your logic
- Gives you something to point to during discussion

**Don't be afraid to draw!** Even simple boxes and arrows help tremendously.

---

**You've got this! Go ace that interview! 🎯**
