# How to Find What a User Owes in a Group

## 🎯 Quick Answer

There are **4 new methods** to find out what a user owes in a group:

```java
// 1. Get detailed balances with each group member
List<Balance> balances = splitwise.getUserBalancesInGroup(user, groupId);

// 2. Get total amount user owes in the group
double owes = splitwise.getTotalUserOwesInGroup(user, groupId);

// 3. Get total amount user is owed in the group
double owed = splitwise.getTotalUserOwedInGroup(user, groupId);

// 4. Get net balance (positive = owes, negative = is owed)
double net = splitwise.getUserNetBalanceInGroup(user, groupId);
```

---

## 📖 Detailed Explanation

### Method 1: `getUserBalancesInGroup(User user, String groupId)`

**Returns:** List of Balance objects showing what the user owes to/is owed by each group member

**Example:**
```java
User bob = ...;
String groupId = trip.getId();

List<Balance> balances = splitwise.getUserBalancesInGroup(bob, groupId);

for (Balance balance : balances) {
    System.out.println(balance);
}

// Output:
// Bob owes Alice: ₹500
// Charlie owes Bob: ₹300
```

**Use this when:** You want to see the breakdown of who Bob owes and who owes Bob within the group.

---

### Method 2: `getTotalUserOwesInGroup(User user, String groupId)`

**Returns:** Total amount the user owes to others in the group (sum of positive balances)

**Example:**
```java
double bobOwes = splitwise.getTotalUserOwesInGroup(bob, groupId);
System.out.println("Bob owes: ₹" + bobOwes);

// Output: Bob owes: ₹1200
```

**Use this when:** You want a single number showing how much Bob needs to pay in total.

---

### Method 3: `getTotalUserOwedInGroup(User user, String groupId)`

**Returns:** Total amount the user is owed by others in the group (sum of negative balances)

**Example:**
```java
double bobOwed = splitwise.getTotalUserOwedInGroup(bob, groupId);
System.out.println("Bob is owed: ₹" + bobOwed);

// Output: Bob is owed: ₹800
```

**Use this when:** You want a single number showing how much Bob should receive in total.

---

### Method 4: `getUserNetBalanceInGroup(User user, String groupId)`

**Returns:** Net balance (owes - owed)
- **Positive value** = user needs to pay this amount overall
- **Negative value** = user should receive this amount overall
- **Zero** = user is settled up

**Example:**
```java
double bobNet = splitwise.getUserNetBalanceInGroup(bob, groupId);

if (bobNet > 0) {
    System.out.println("Bob needs to pay: ₹" + bobNet);
} else if (bobNet < 0) {
    System.out.println("Bob should receive: ₹" + Math.abs(bobNet));
} else {
    System.out.println("Bob is settled up!");
}

// Output: Bob needs to pay: ₹400
```

**Use this when:** You want to know the final amount Bob needs to settle.

---

## 🔍 Complete Example

```java
SplitwiseService splitwise = new SplitwiseService();

// Setup: Create group and add expenses
User alice = splitwise.registerUser("Alice", "alice@ex.com", "111");
User bob = splitwise.registerUser("Bob", "bob@ex.com", "222");
User charlie = splitwise.registerUser("Charlie", "charlie@ex.com", "333");

Group trip = splitwise.createGroup("Weekend Trip", alice);
splitwise.addMemberToGroup(trip.getId(), bob);
splitwise.addMemberToGroup(trip.getId(), charlie);

// Alice paid for hotel: ₹9,000 (split equally)
splitwise.addExpense(
    "Hotel",
    9000.0,
    alice,
    Arrays.asList(new Split(alice, 0), new Split(bob, 0), new Split(charlie, 0)),
    ExpenseType.EQUAL,
    trip.getId()
);

// Bob paid for food: ₹6,000 (split equally)
splitwise.addExpense(
    "Food",
    6000.0,
    bob,
    Arrays.asList(new Split(alice, 0), new Split(bob, 0), new Split(charlie, 0)),
    ExpenseType.EQUAL,
    trip.getId()
);

// Now find out what Bob owes in the group
System.out.println("=== Bob's Status in Weekend Trip ===");

// 1. Detailed breakdown
List<Balance> bobBalances = splitwise.getUserBalancesInGroup(bob, trip.getId());
System.out.println("\nDetailed Balances:");
for (Balance balance : bobBalances) {
    System.out.println("  " + balance);
}

// 2. Summary numbers
double owes = splitwise.getTotalUserOwesInGroup(bob, trip.getId());
double owed = splitwise.getTotalUserOwedInGroup(bob, trip.getId());
double net = splitwise.getUserNetBalanceInGroup(bob, trip.getId());

System.out.println("\nSummary:");
System.out.println("  Total Bob owes:    ₹" + owes);    // ₹3,000 (to Alice)
System.out.println("  Total Bob is owed: ₹" + owed);    // ₹2,000 (from Charlie)
System.out.println("  Net balance:       ₹" + net);     // ₹1,000 (owes overall)
```

**Output:**
```
=== Bob's Status in Weekend Trip ===

Detailed Balances:
  Bob owes Alice: ₹3000.00
  Charlie owes Bob: ₹2000.00

Summary:
  Total Bob owes:    ₹3000.00
  Total Bob is owed: ₹2000.00
  Net balance:       ₹1000.00
```

---

## 💡 Understanding Balance Signs

### Positive Balance (+)
```java
Balance: Bob owes Alice: ₹500
Amount: +500 (positive)
Meaning: Bob needs to pay Alice ₹500
```

### Negative Balance (-)
```java
Balance: Alice owes Bob: ₹500
Amount: -500 (negative, from Bob's perspective)
Meaning: Bob should receive ₹500 from Alice
```

---

## 📊 Calculation Logic

### How it works internally:

1. **Get user's balance sheet entry**: `balanceSheet.get(user.getId())`

2. **Filter to group members only**: Only include balances with users who are in the group

3. **Calculate totals**:
   ```java
   // Total owes (positive balances)
   owes = sum of all balance.getAmount() where amount > 0
   
   // Total owed (negative balances)
   owed = sum of abs(balance.getAmount()) where amount < 0
   
   // Net balance
   net = owes - owed
   ```

---

## 🎯 Use Cases

### Use Case 1: Show User Dashboard
```java
// Show Bob what he owes in each of his groups
for (Group group : bob.getGroups()) {
    double net = splitwise.getUserNetBalanceInGroup(bob, group.getId());
    if (Math.abs(net) > 0.01) {
        System.out.println(group.getName() + ": ₹" + net);
    }
}
```

### Use Case 2: Remind User to Settle
```java
double net = splitwise.getUserNetBalanceInGroup(bob, groupId);
if (net > 100) {
    sendReminder(bob, "You owe ₹" + net + " in " + group.getName());
}
```

### Use Case 3: Group Summary Report
```java
for (User member : group.getMembers()) {
    double net = splitwise.getUserNetBalanceInGroup(member, group.getId());
    System.out.println(member.getName() + ": ₹" + net);
}
```

### Use Case 4: Check Before Leaving Group
```java
double net = splitwise.getUserNetBalanceInGroup(user, groupId);
if (Math.abs(net) > 0.01) {
    System.out.println("Please settle your balance before leaving!");
} else {
    group.removeMember(user);
}
```

---

## ⚠️ Error Handling

### Group not found
```java
try {
    List<Balance> balances = splitwise.getUserBalancesInGroup(bob, "invalid-id");
} catch (IllegalArgumentException e) {
    System.out.println("Error: " + e.getMessage());
    // Output: Error: Group not found
}
```

### User not in group
```java
try {
    List<Balance> balances = splitwise.getUserBalancesInGroup(outsider, groupId);
} catch (IllegalArgumentException e) {
    System.out.println("Error: " + e.getMessage());
    // Output: Error: User is not a member of this group
}
```

---

## 🆚 Comparison: Different Methods

| Method | Returns | Use When |
|--------|---------|----------|
| `getUserBalances(user)` | All balances across all users | Want to see everything user owes/is owed |
| `getGroupBalances(groupId)` | All balances within a group | Want to see all group debts |
| `getUserBalancesInGroup(user, groupId)` | One user's balances in a group | Want Bob's status in this specific group |
| `getTotalUserOwesInGroup(user, groupId)` | Single number (owes) | Quick answer: how much does Bob owe? |
| `getUserNetBalanceInGroup(user, groupId)` | Single number (net) | Quick answer: overall, does Bob pay or receive? |

---

## 🧪 Testing

Run the demo to see it in action:
```bash
javac lld/splitwise/*.java
java lld.splitwise.GroupBalanceDemo
```

---

## 📝 API Reference

### getUserBalancesInGroup
```java
/**
 * Get a specific user's balances within a group
 * 
 * @param user The user to check
 * @param groupId The group ID
 * @return List of Balance objects (can be empty)
 * @throws IllegalArgumentException if group not found or user not in group
 */
public List<Balance> getUserBalancesInGroup(User user, String groupId)
```

### getTotalUserOwesInGroup
```java
/**
 * Get total amount a user owes in a group
 * 
 * @param user The user to check
 * @param groupId The group ID
 * @return Sum of all positive balances (amount user owes)
 * @throws IllegalArgumentException if group not found or user not in group
 */
public double getTotalUserOwesInGroup(User user, String groupId)
```

### getTotalUserOwedInGroup
```java
/**
 * Get total amount a user is owed in a group
 * 
 * @param user The user to check
 * @param groupId The group ID
 * @return Sum of all negative balances (amount user is owed)
 * @throws IllegalArgumentException if group not found or user not in group
 */
public double getTotalUserOwedInGroup(User user, String groupId)
```

### getUserNetBalanceInGroup
```java
/**
 * Get net balance for a user in a group
 * 
 * @param user The user to check
 * @param groupId The group ID
 * @return Net balance (positive = owes, negative = is owed, zero = settled)
 * @throws IllegalArgumentException if group not found or user not in group
 */
public double getUserNetBalanceInGroup(User user, String groupId)
```

---

## ✅ Summary

**To find what a user owes in a group, use:**

1. **Detailed view**: `getUserBalancesInGroup(user, groupId)` → See breakdown
2. **Quick answer**: `getUserNetBalanceInGroup(user, groupId)` → One number

**Example:**
```java
// Quick check
double net = splitwise.getUserNetBalanceInGroup(bob, groupId);
System.out.println("Bob " + (net > 0 ? "owes" : "is owed") + " ₹" + Math.abs(net));
```

---

**Need help?** See `GroupBalanceDemo.java` for a complete working example! 🚀
