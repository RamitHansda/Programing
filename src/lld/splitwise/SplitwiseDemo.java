package lld.splitwise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Demonstration of Splitwise system with various scenarios
 */
public class SplitwiseDemo {

    public static void main(String[] args) {
        SplitwiseService splitwise = new SplitwiseService();

        System.out.println("=".repeat(80));
        System.out.println("SPLITWISE - EXPENSE SHARING SYSTEM");
        System.out.println("=".repeat(80));

        // Scenario 1: Simple equal split
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 1: Equal Split - Dinner among friends");
        System.out.println("=".repeat(80));
        scenario1EqualSplit(splitwise);

        // Scenario 2: Exact split
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 2: Exact Split - Shopping with different items");
        System.out.println("=".repeat(80));
        scenario2ExactSplit(splitwise);

        // Scenario 3: Percentage split
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 3: Percentage Split - Business expense");
        System.out.println("=".repeat(80));
        scenario3PercentageSplit(splitwise);

        // Scenario 4: Share-based split
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 4: Share-based Split - Rent split by room size");
        System.out.println("=".repeat(80));
        scenario4ShareSplit(splitwise);

        // Scenario 5: Group expenses
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 5: Group Expenses - Trip with friends");
        System.out.println("=".repeat(80));
        scenario5GroupExpenses(splitwise);

        // Scenario 6: Settlements
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 6: Recording Settlements");
        System.out.println("=".repeat(80));
        scenario6Settlements(splitwise);

        // Scenario 7: Debt simplification
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SCENARIO 7: Debt Simplification - Minimize Transactions");
        System.out.println("=".repeat(80));
        scenario7DebtSimplification(splitwise);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO COMPLETED SUCCESSFULLY");
        System.out.println("=".repeat(80));
    }

    private static void scenario1EqualSplit(SplitwiseService splitwise) {
        // Register users
        User alice = splitwise.registerUser("Alice", "alice@example.com", "1111111111");
        User bob = splitwise.registerUser("Bob", "bob@example.com", "2222222222");
        User charlie = splitwise.registerUser("Charlie", "charlie@example.com", "3333333333");

        System.out.println("Users registered: Alice, Bob, Charlie");

        // Alice paid for dinner, split equally among 3
        List<Split> splits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0)
        );

        Expense expense = splitwise.addExpense(
            "Dinner at Italian Restaurant",
            900.0,
            alice,
            splits,
            ExpenseType.EQUAL,
            null
        );

        System.out.println("\n✓ Expense added: " + expense.getDescription());
        System.out.println("  Total: ₹" + expense.getTotalAmount());
        System.out.println("  Paid by: " + expense.getPaidBy().getName());
        System.out.println("  Split type: " + expense.getExpenseType());
        System.out.println("\n  Split breakdown:");
        for (Split split : expense.getSplits()) {
            System.out.println("    - " + split);
        }

        System.out.println("\n📊 Current Balances:");
        showUserBalances(splitwise, alice);
    }

    private static void scenario2ExactSplit(SplitwiseService splitwise) {
        User alice = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Alice"))
                .findFirst().get();
        User bob = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Bob"))
                .findFirst().get();
        User charlie = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Charlie"))
                .findFirst().get();

        // Bob paid for shopping, exact amounts
        List<Split> splits = Arrays.asList(
            new Split(alice, 450.0),  // Alice bought expensive items
            new Split(bob, 250.0),    // Bob bought medium items
            new Split(charlie, 300.0) // Charlie bought some items
        );

        Expense expense = splitwise.addExpense(
            "Shopping at Supermarket",
            1000.0,
            bob,
            splits,
            ExpenseType.EXACT,
            null
        );

        System.out.println("\n✓ Expense added: " + expense.getDescription());
        System.out.println("  Total: ₹" + expense.getTotalAmount());
        System.out.println("  Paid by: " + expense.getPaidBy().getName());
        System.out.println("  Split type: " + expense.getExpenseType());
        System.out.println("\n  Split breakdown:");
        for (Split split : expense.getSplits()) {
            System.out.println("    - " + split);
        }

        System.out.println("\n📊 Current Balances:");
        showUserBalances(splitwise, bob);
    }

    private static void scenario3PercentageSplit(SplitwiseService splitwise) {
        User alice = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Alice"))
                .findFirst().get();
        User bob = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Bob"))
                .findFirst().get();

        // Charlie paid for business expense, split by percentage
        User charlie = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Charlie"))
                .findFirst().get();

        List<Split> splits = Arrays.asList(
            new Split(alice, 50.0),   // Alice 50%
            new Split(bob, 30.0),     // Bob 30%
            new Split(charlie, 20.0)  // Charlie 20%
        );

        Expense expense = splitwise.addExpense(
            "Office Supplies",
            2000.0,
            charlie,
            splits,
            ExpenseType.PERCENTAGE,
            null
        );

        System.out.println("\n✓ Expense added: " + expense.getDescription());
        System.out.println("  Total: ₹" + expense.getTotalAmount());
        System.out.println("  Paid by: " + expense.getPaidBy().getName());
        System.out.println("  Split type: " + expense.getExpenseType());
        System.out.println("\n  Split breakdown:");
        for (Split split : expense.getSplits()) {
            System.out.println("    - " + split);
        }

        System.out.println("\n📊 Current Balances:");
        showUserBalances(splitwise, charlie);
    }

    private static void scenario4ShareSplit(SplitwiseService splitwise) {
        User david = splitwise.registerUser("David", "david@example.com", "4444444444");
        User emma = splitwise.registerUser("Emma", "emma@example.com", "5555555555");
        User frank = splitwise.registerUser("Frank", "frank@example.com", "6666666666");

        System.out.println("New users registered: David, Emma, Frank");

        // David paid rent, split by room size (2:3:1 ratio)
        List<Split> splits = Arrays.asList(
            new Split(david, 2),  // Small room
            new Split(emma, 3),   // Large room
            new Split(frank, 1)   // Tiny room
        );

        Expense expense = splitwise.addExpense(
            "Monthly Rent",
            30000.0,
            david,
            splits,
            ExpenseType.SHARE,
            null
        );

        System.out.println("\n✓ Expense added: " + expense.getDescription());
        System.out.println("  Total: ₹" + expense.getTotalAmount());
        System.out.println("  Paid by: " + expense.getPaidBy().getName());
        System.out.println("  Split type: " + expense.getExpenseType());
        System.out.println("\n  Split breakdown (Ratio 2:3:1):");
        for (Split split : expense.getSplits()) {
            System.out.println("    - " + split);
        }

        System.out.println("\n📊 Current Balances:");
        showUserBalances(splitwise, david);
    }

    private static void scenario5GroupExpenses(SplitwiseService splitwise) {
        User alice = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Alice"))
                .findFirst().get();
        User bob = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Bob"))
                .findFirst().get();
        User charlie = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Charlie"))
                .findFirst().get();

        // Create a group for trip
        Group tripGroup = splitwise.createGroup("Goa Trip 2026", alice);
        splitwise.addMemberToGroup(tripGroup.getId(), bob);
        splitwise.addMemberToGroup(tripGroup.getId(), charlie);

        System.out.println("\n✓ Group created: " + tripGroup.getName());
        System.out.println("  Members: Alice, Bob, Charlie");

        // Add multiple expenses to the group
        // Expense 1: Hotel booking by Alice
        List<Split> hotelSplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0)
        );

        Expense hotelExpense = splitwise.addExpense(
            "Hotel Booking",
            15000.0,
            alice,
            hotelSplits,
            ExpenseType.EQUAL,
            tripGroup.getId()
        );

        System.out.println("\n  ✓ Hotel booking added: ₹" + hotelExpense.getTotalAmount());

        // Expense 2: Flight tickets by Bob
        List<Split> flightSplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0)
        );

        Expense flightExpense = splitwise.addExpense(
            "Flight Tickets",
            18000.0,
            bob,
            flightSplits,
            ExpenseType.EQUAL,
            tripGroup.getId()
        );

        System.out.println("  ✓ Flight tickets added: ₹" + flightExpense.getTotalAmount());

        // Expense 3: Activities by Charlie
        List<Split> activitySplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0)
        );

        Expense activityExpense = splitwise.addExpense(
            "Water Sports & Activities",
            9000.0,
            charlie,
            activitySplits,
            ExpenseType.EQUAL,
            tripGroup.getId()
        );

        System.out.println("  ✓ Activities added: ₹" + activityExpense.getTotalAmount());

        System.out.println("\n📊 Group Balances for '" + tripGroup.getName() + "':");
        List<Balance> groupBalances = splitwise.getGroupBalances(tripGroup.getId());
        for (Balance balance : groupBalances) {
            System.out.println("  " + balance);
        }
    }

    private static void scenario6Settlements(SplitwiseService splitwise) {
        User alice = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Alice"))
                .findFirst().get();
        User bob = splitwise.getUsers().values().stream()
                .filter(u -> u.getName().equals("Bob"))
                .findFirst().get();

        System.out.println("\n📊 Before Settlement:");
        System.out.println("  Bob's balance with Alice: ₹" + 
            String.format("%.2f", splitwise.getBalance(bob, alice)));

        // Bob settles up with Alice
        Transaction settlement = splitwise.recordSettlement(
            bob,
            alice,
            500.0,
            "Partial settlement"
        );

        System.out.println("\n✓ Settlement recorded: " + settlement);

        System.out.println("\n📊 After Settlement:");
        System.out.println("  Bob's balance with Alice: ₹" + 
            String.format("%.2f", splitwise.getBalance(bob, alice)));
    }

    private static void scenario7DebtSimplification(SplitwiseService splitwise) {
        // Create a complex debt scenario
        User george = splitwise.registerUser("George", "george@example.com", "7777777777");
        User hannah = splitwise.registerUser("Hannah", "hannah@example.com", "8888888888");
        User iris = splitwise.registerUser("Iris", "iris@example.com", "9999999999");

        System.out.println("New users registered: George, Hannah, Iris");

        // George paid for dinner
        splitwise.addExpense(
            "Group Dinner",
            1200.0,
            george,
            Arrays.asList(
                new Split(george, 0),
                new Split(hannah, 0),
                new Split(iris, 0)
            ),
            ExpenseType.EQUAL,
            null
        );

        // Hannah paid for movie
        splitwise.addExpense(
            "Movie Tickets",
            900.0,
            hannah,
            Arrays.asList(
                new Split(george, 0),
                new Split(hannah, 0),
                new Split(iris, 0)
            ),
            ExpenseType.EQUAL,
            null
        );

        // Iris paid for snacks
        splitwise.addExpense(
            "Snacks",
            600.0,
            iris,
            Arrays.asList(
                new Split(george, 0),
                new Split(hannah, 0),
                new Split(iris, 0)
            ),
            ExpenseType.EQUAL,
            null
        );

        System.out.println("\n✓ Multiple expenses added between George, Hannah, and Iris");

        System.out.println("\n📊 Current Balances (Complex scenario):");
        for (Balance balance : splitwise.getUserBalances(george)) {
            System.out.println("  " + balance);
        }
        for (Balance balance : splitwise.getUserBalances(hannah)) {
            System.out.println("  " + balance);
        }
        for (Balance balance : splitwise.getUserBalances(iris)) {
            System.out.println("  " + balance);
        }

        System.out.println("\n🔄 Simplifying debts...");
        List<Transaction> simplifiedTransactions = splitwise.simplifyDebts(
            Arrays.asList(george, hannah, iris)
        );

        System.out.println("\n✓ Simplified to " + simplifiedTransactions.size() + " transaction(s):");
        for (Transaction transaction : simplifiedTransactions) {
            System.out.println("  💰 " + transaction);
        }
    }

    private static void showUserBalances(SplitwiseService splitwise, User user) {
        List<Balance> balances = splitwise.getUserBalances(user);
        if (balances.isEmpty()) {
            System.out.println("  " + user.getName() + " is settled up with everyone!");
        } else {
            for (Balance balance : balances) {
                System.out.println("  " + balance);
            }
        }
    }
}
