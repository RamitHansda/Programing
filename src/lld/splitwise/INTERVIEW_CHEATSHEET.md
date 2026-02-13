# Splitwise LLD - Interview Cheat Sheet

## 🎯 Quick Reference (Review 5 minutes before interview)

---

## 1️⃣ Core Entities (30 seconds)

```
User ──► has id, name, email, phone
Expense ──► has amount, paidBy, splits[], type, groupId
Split ──► has user, amount
Group ──► has members[], expenses[]
Balance ──► tracks debt between two users
Transaction ──► records settlements
```

---

## 2️⃣ Key Design Decision: Strategy Pattern

```
ExpenseSplitter (Interface)
├── EqualSplitter        900/3 = 300 each
├── ExactSplitter        Alice:450, Bob:250, Charlie:300
├── PercentageSplitter   Total * (50%, 30%, 20%)
└── ShareSplitter        Total * (2:3:1 ratio)
```

**Why?** Extensible, testable, follows SOLID principles

---

## 3️⃣ Data Structure: Balance Sheet

```java
Map<String, Map<String, Double>> balanceSheet

Example:
"Alice" → { "Bob": 500 }     // Alice owes Bob ₹500
"Bob"   → { "Alice": -500 }  // Bob is owed ₹500
```

**Pros:** O(1) balance lookup  
**Cons:** O(u²) space worst case

---

## 4️⃣ Add Expense Flow

```
1. Validate (payer registered, users exist)
2. Get splitter from factory based on type
3. Calculate splits (splitter.calculateSplits())
4. Create expense object
5. Update balance sheet
   └─ For each split: if user != payer, update both balances
```

**Time:** O(n) where n = number of splits

---

## 5️⃣ Rounding Strategy

```java
Total: ₹100, 3 people
Person 1: ₹33.33
Person 2: ₹33.33
Person 3: ₹33.34  ← Gets remainder

// Code pattern:
if (i == last) {
    amount = total - sum;  // Remainder
} else {
    amount = round(splitAmount, 2);
    sum += amount;
}
```

**Key:** Last person always gets remainder to ensure exact total

---

## 6️⃣ Debt Simplification (Most Complex Part!)

**Problem:** Minimize number of transactions to settle all debts

**Algorithm:** Greedy approach
```
1. Calculate net balance per user: O(u²)
   Alice: +50 (owes), Bob: 0, Charlie: -50 (owed)

2. Separate debtors and creditors: O(u)

3. Sort both by amount (descending): O(u log u)

4. Match greedily: O(u)
   amount = min(largest_debtor, largest_creditor)
   create transaction
   update balances
   remove if settled

Total: O(u²)
```

**Not optimal** (NP-hard for exact), but good enough!

---

## 7️⃣ Complexity Quick Reference

| Operation | Time | Space |
|-----------|------|-------|
| Add User | O(1) | O(1) |
| Add Expense | O(n) | O(n) |
| Get Balance | O(1) | - |
| Simplify Debts | O(u²) | O(u) |

---

## 8️⃣ Common Interview Questions - Quick Answers

**Q: Why Strategy Pattern?**  
A: Extensible (add new split types), testable (independent tests), SOLID principles

**Q: How to scale?**  
A: Partition by userId, cache balances, read replicas, async debt simplification, CQRS

**Q: Handle concurrent updates?**  
A: Database transactions with ACID, optimistic locking with version field

**Q: Edit an expense?**  
A: Revert old balances, apply new ones. Better: create adjustment expense for audit trail

**Q: Multiple currencies?**  
A: Add Currency field, normalize to base currency, store exchange rate at time of expense

**Q: What are limitations?**  
A: In-memory only, O(u²) space, greedy simplification, no expense edits, single currency, no permissions

---

## 9️⃣ Code Snippets to Remember

### ExpenseSplitter Interface
```java
interface ExpenseSplitter {
    boolean validate(double total, List<Split> splits);
    void calculateSplits(double total, List<Split> splits);
}
```

### Equal Splitter (Simplest Example)
```java
class EqualSplitter implements ExpenseSplitter {
    void calculateSplits(double total, List<Split> splits) {
        double each = total / splits.size();
        double sum = 0;
        for (int i = 0; i < splits.size(); i++) {
            if (i == splits.size() - 1) {
                splits.get(i).setAmount(total - sum);
            } else {
                double amt = round(each, 2);
                splits.get(i).setAmount(amt);
                sum += amt;
            }
        }
    }
}
```

### Update Balance
```java
void updateBalance(Expense expense) {
    for (Split split : expense.getSplits()) {
        if (split.getUser() != expense.getPaidBy()) {
            // User owes payer
            balanceSheet[split.user][payer] += split.amount;
            balanceSheet[payer][split.user] -= split.amount;
        }
    }
}
```

---

## 🔟 Communication Tips

### Opening
"Let me clarify requirements first. What split types do we need? Should we support groups? Any scale requirements?"

### Explaining Strategy Pattern
"Different split types have different logic. Strategy pattern makes it extensible - I can add a new split type without touching existing code."

### Explaining Balance Sheet
"I use nested HashMap for O(1) lookups. Positive means user owes, negative means they're owed. Trade-off is O(u²) space."

### Handling Tough Questions
"That's a great question. I haven't implemented that yet, but here's how I'd approach it..."

"Let me think about this... I see two options here..."

---

## 📊 Example Walkthrough (Practice This!)

**Interviewer:** "Walk me through adding an expense"

**You:** 
"Sure! Let's say Alice paid ₹900 for dinner with Bob and Charlie.

1. **Input:** `addExpense("Dinner", 900, alice, [alice, bob, charlie], EQUAL)`

2. **Factory** creates `EqualSplitter`

3. **Splitter calculates:** 900 ÷ 3 = 300 each
   - Alice: 300, Bob: 300, Charlie: 300

4. **Create expense** object with these splits

5. **Update balances:**
   - Bob owes Alice: +300
   - Charlie owes Alice: +300
   - Skip Alice (she paid)

6. **Result:** Bob's balance with Alice is now +300"

---

## 🎯 Design Patterns Mentioned

1. **Strategy Pattern** - ExpenseSplitters
2. **Factory Pattern** - ExpenseSplitterFactory  
3. **Service Layer** - SplitwiseService (facade)

---

## 🔑 Key Phrases to Use

✅ "This follows SOLID principles..."  
✅ "The trade-off here is..."  
✅ "We could optimize this by..."  
✅ "For production, I'd also consider..."  
✅ "An alternative approach would be..."  
✅ "This is O(1) lookup but O(n²) space..."  
✅ "Let me draw this out..."  

---

## 🚨 Red Flags to Avoid

❌ Don't jump to code without discussing design  
❌ Don't assume requirements without asking  
❌ Don't say "I don't know" - say "Let me reason through this"  
❌ Don't over-engineer upfront  
❌ Don't forget to handle edge cases  

---

## ⏱️ Time Allocation (45-min interview)

```
0-5 min:   Clarify requirements
5-10 min:  High-level design (entities, patterns)
10-30 min: Detailed design (data structures, algorithms)
30-40 min: Follow-up questions, scaling, edge cases
40-45 min: Your questions
```

---

## 💪 Confidence Boosters

**You know:**
- ✅ How to design clean OOP systems
- ✅ When and why to use design patterns
- ✅ How to analyze complexity
- ✅ How to discuss trade-offs
- ✅ How to think about scale

**You have:**
- ✅ A complete, working implementation
- ✅ All edge cases handled
- ✅ Clean, readable code
- ✅ Comprehensive documentation

**You can:**
- ✅ Explain the design clearly
- ✅ Write code on a whiteboard
- ✅ Answer follow-up questions
- ✅ Discuss alternatives and trade-offs
- ✅ Think on your feet

---

## 🎤 Last-Minute Checklist

Before entering the interview:

- [ ] Can draw entity diagram
- [ ] Can explain Strategy Pattern in 30 seconds
- [ ] Know the 4 split types
- [ ] Know balance sheet structure
- [ ] Know debt simplification algorithm
- [ ] Know time/space complexity
- [ ] Have 3 scaling approaches ready
- [ ] Can code a splitter from scratch

---

## 🎯 Opening Statement (Memorize This!)

"Thanks for the problem! Splitwise is an expense-sharing app that helps users split bills and track who owes whom. Before I start designing, let me clarify a few requirements: What split types should we support - just equal split or also exact amounts and percentages? Do we need group management? Any specific scale or performance requirements? Should we handle settlements between users?"

---

## 🎯 Closing Statement

"To summarize: I've designed a system with clean entity models using Strategy Pattern for extensible split types, a service layer managing business logic, and a balance sheet for O(1) lookups. The debt simplification uses a greedy algorithm that's O(u²) but sufficient for most cases. For production, I'd add database persistence, caching, and proper transaction handling. Are there any specific areas you'd like me to dive deeper into?"

---

## 📝 If Whiteboard Coding

**Start with:**
```java
// 1. Interface first
interface ExpenseSplitter {
    void calculateSplits(double total, List<Split> splits);
}

// 2. Simple implementation
class EqualSplitter implements ExpenseSplitter {
    public void calculateSplits(double total, List<Split> splits) {
        // ... implementation
    }
}

// 3. Show usage
ExpenseSplitter splitter = factory.getSplitter(EQUAL);
splitter.calculateSplits(1000, splits);
```

---

## 🚀 You've Got This!

- ✅ You understand the problem deeply
- ✅ You have a solid, extensible design
- ✅ You can explain trade-offs clearly
- ✅ You know how to scale it
- ✅ You've handled edge cases

**Now go ace that interview! 💪**

---

## 🎓 One More Thing

Remember: **The interviewer wants you to succeed!**

They're evaluating:
1. Can you think through problems?
2. Can you design clean systems?
3. Can you communicate clearly?
4. Can you handle feedback?

You can do all of these. Trust your preparation. Think out loud. Ask questions. You've got this! 🚀
