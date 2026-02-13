package lld.splitwise;

import java.util.Arrays;
import java.util.List;

/**
 * Demonstrates how to find out what a user owes in a group
 */
public class GroupBalanceDemo {

    public static void main(String[] args) {
        SplitwiseService splitwise = new SplitwiseService();

        System.out.println("=".repeat(70));
        System.out.println("HOW TO FIND WHAT A USER OWES IN A GROUP");
        System.out.println("=".repeat(70));

        // Create users
        User alice = splitwise.registerUser("Alice", "alice@example.com", "111");
        User bob = splitwise.registerUser("Bob", "bob@example.com", "222");
        User charlie = splitwise.registerUser("Charlie", "charlie@example.com", "333");
        User david = splitwise.registerUser("David", "david@example.com", "444");

        System.out.println("\n✓ Users registered: Alice, Bob, Charlie, David");

        // Create a trip group
        Group trip = splitwise.createGroup("Goa Trip 2026", alice);
        splitwise.addMemberToGroup(trip.getId(), bob);
        splitwise.addMemberToGroup(trip.getId(), charlie);
        splitwise.addMemberToGroup(trip.getId(), david);

        System.out.println("✓ Group created: " + trip.getName());
        System.out.println("  Members: Alice, Bob, Charlie, David\n");

        // Add multiple expenses to the group
        System.out.println("📝 Adding expenses...\n");

        // Expense 1: Hotel by Alice (₹20,000)
        List<Split> hotelSplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0),
            new Split(david, 0)
        );
        splitwise.addExpense(
            "Hotel Booking",
            20000.0,
            alice,
            hotelSplits,
            ExpenseType.EQUAL,
            trip.getId()
        );
        System.out.println("  ✓ Alice paid ₹20,000 for Hotel (split equally)");

        // Expense 2: Flights by Bob (₹24,000)
        List<Split> flightSplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0),
            new Split(david, 0)
        );
        splitwise.addExpense(
            "Flight Tickets",
            24000.0,
            bob,
            flightSplits,
            ExpenseType.EQUAL,
            trip.getId()
        );
        System.out.println("  ✓ Bob paid ₹24,000 for Flights (split equally)");

        // Expense 3: Activities by Charlie (₹8,000)
        List<Split> activitySplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0),
            new Split(david, 0)
        );
        splitwise.addExpense(
            "Water Sports",
            8000.0,
            charlie,
            activitySplits,
            ExpenseType.EQUAL,
            trip.getId()
        );
        System.out.println("  ✓ Charlie paid ₹8,000 for Activities (split equally)");

        // Expense 4: Dinner by Alice (₹4,000) - but David didn't join
        List<Split> dinnerSplits = Arrays.asList(
            new Split(alice, 0),
            new Split(bob, 0),
            new Split(charlie, 0)
        );
        splitwise.addExpense(
            "Dinner at Beach",
            4000.0,
            alice,
            dinnerSplits,
            ExpenseType.EQUAL,
            trip.getId()
        );
        System.out.println("  ✓ Alice paid ₹4,000 for Dinner (David didn't join)");

        System.out.println("\n" + "=".repeat(70));
        System.out.println("METHOD 1: Get specific user's balances in group");
        System.out.println("=".repeat(70));

        // Method 1: Get Bob's balances within the group
        System.out.println("\n🔍 Bob's balances in '" + trip.getName() + "':");
        List<Balance> bobBalances = splitwise.getUserBalancesInGroup(bob, trip.getId());
        
        if (bobBalances.isEmpty()) {
            System.out.println("   Bob is settled up with everyone in the group!");
        } else {
            for (Balance balance : bobBalances) {
                System.out.println("   " + balance);
            }
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("METHOD 2: Get total amounts (owes, owed, net)");
        System.out.println("=".repeat(70));

        // Method 2: Get Bob's total amounts in the group
        double bobOwes = splitwise.getTotalUserOwesInGroup(bob, trip.getId());
        double bobOwed = splitwise.getTotalUserOwedInGroup(bob, trip.getId());
        double bobNet = splitwise.getUserNetBalanceInGroup(bob, trip.getId());

        System.out.println("\n💰 Bob's Summary in '" + trip.getName() + "':");
        System.out.println("   Total Bob owes:    ₹" + String.format("%.2f", bobOwes));
        System.out.println("   Total Bob is owed: ₹" + String.format("%.2f", bobOwed));
        System.out.println("   Net balance:       ₹" + String.format("%.2f", bobNet));
        
        if (bobNet > 0) {
            System.out.println("   → Bob needs to pay ₹" + String.format("%.2f", bobNet));
        } else if (bobNet < 0) {
            System.out.println("   → Bob should receive ₹" + String.format("%.2f", Math.abs(bobNet)));
        } else {
            System.out.println("   → Bob is settled up!");
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("ALL USERS IN THE GROUP");
        System.out.println("=".repeat(70));

        // Show for all users
        for (User user : trip.getMembers()) {
            System.out.println("\n👤 " + user.getName() + ":");
            
            List<Balance> userBalances = splitwise.getUserBalancesInGroup(user, trip.getId());
            if (userBalances.isEmpty()) {
                System.out.println("   Settled up with everyone!");
            } else {
                for (Balance balance : userBalances) {
                    System.out.println("   " + balance);
                }
            }
            
            double owes = splitwise.getTotalUserOwesInGroup(user, trip.getId());
            double owed = splitwise.getTotalUserOwedInGroup(user, trip.getId());
            double net = splitwise.getUserNetBalanceInGroup(user, trip.getId());
            
            System.out.println("   Summary: Owes ₹" + String.format("%.2f", owes) + 
                             ", Owed ₹" + String.format("%.2f", owed) + 
                             ", Net ₹" + String.format("%.2f", net));
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("VERIFICATION: All balances should sum to zero");
        System.out.println("=".repeat(70));

        double totalNet = 0;
        for (User user : trip.getMembers()) {
            double net = splitwise.getUserNetBalanceInGroup(user, trip.getId());
            totalNet += net;
        }
        System.out.println("\n✓ Total net balance: ₹" + String.format("%.2f", totalNet));
        System.out.println("  (Should be 0.00 - balanced!)");

        System.out.println("\n" + "=".repeat(70));
        System.out.println("EDGE CASE: User not in group");
        System.out.println("=".repeat(70));

        // Create a user not in the group
        User eve = splitwise.registerUser("Eve", "eve@example.com", "555");
        System.out.println("\n✓ Created user: Eve (not in the group)");

        try {
            splitwise.getUserBalancesInGroup(eve, trip.getId());
        } catch (IllegalArgumentException e) {
            System.out.println("❌ Error (expected): " + e.getMessage());
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("DEMO COMPLETED");
        System.out.println("=".repeat(70));
    }
}
