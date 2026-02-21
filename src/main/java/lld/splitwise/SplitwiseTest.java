package lld.splitwise;

import java.util.Arrays;
import java.util.List;

/**
 * Simple test class to verify Splitwise functionality
 * Run this to quickly test if everything works
 */
public class SplitwiseTest {

    public static void main(String[] args) {
        System.out.println("🧪 Running Splitwise Tests...\n");

        int passed = 0;
        int failed = 0;

        // Test 1: User Registration
        if (testUserRegistration()) {
            System.out.println("✅ Test 1: User Registration - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 1: User Registration - FAILED");
            failed++;
        }

        // Test 2: Equal Split
        if (testEqualSplit()) {
            System.out.println("✅ Test 2: Equal Split - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 2: Equal Split - FAILED");
            failed++;
        }

        // Test 3: Exact Split
        if (testExactSplit()) {
            System.out.println("✅ Test 3: Exact Split - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 3: Exact Split - FAILED");
            failed++;
        }

        // Test 4: Percentage Split
        if (testPercentageSplit()) {
            System.out.println("✅ Test 4: Percentage Split - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 4: Percentage Split - FAILED");
            failed++;
        }

        // Test 5: Share Split
        if (testShareSplit()) {
            System.out.println("✅ Test 5: Share Split - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 5: Share Split - FAILED");
            failed++;
        }

        // Test 6: Group Management
        if (testGroupManagement()) {
            System.out.println("✅ Test 6: Group Management - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 6: Group Management - FAILED");
            failed++;
        }

        // Test 7: Settlement
        if (testSettlement()) {
            System.out.println("✅ Test 7: Settlement - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 7: Settlement - FAILED");
            failed++;
        }

        // Test 8: Balance Tracking
        if (testBalanceTracking()) {
            System.out.println("✅ Test 8: Balance Tracking - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 8: Balance Tracking - FAILED");
            failed++;
        }

        // Test 9: Debt Simplification
        if (testDebtSimplification()) {
            System.out.println("✅ Test 9: Debt Simplification - PASSED");
            passed++;
        } else {
            System.out.println("❌ Test 9: Debt Simplification - FAILED");
            failed++;
        }

        // Summary
        System.out.println("\n" + "=".repeat(50));
        System.out.println("📊 Test Results:");
        System.out.println("   Passed: " + passed);
        System.out.println("   Failed: " + failed);
        System.out.println("   Total:  " + (passed + failed));
        System.out.println("=".repeat(50));

        if (failed == 0) {
            System.out.println("🎉 All tests passed! System is working correctly.");
        } else {
            System.out.println("⚠️  Some tests failed. Please review the implementation.");
        }
    }

    private static boolean testUserRegistration() {
        try {
            SplitwiseService service = new SplitwiseService();
            User user = service.registerUser("Test User", "test@test.com", "1234567890");
            return user != null && user.getName().equals("Test User");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testEqualSplit() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            List<Split> splits = Arrays.asList(
                new Split(alice, 0),
                new Split(bob, 0)
            );

            Expense expense = service.addExpense("Test", 1000.0, alice, splits, ExpenseType.EQUAL, null);
            
            // Bob should owe Alice 500
            double balance = service.getBalance(bob, alice);
            return Math.abs(balance - 500.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testExactSplit() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            List<Split> splits = Arrays.asList(
                new Split(alice, 300.0),
                new Split(bob, 700.0)
            );

            Expense expense = service.addExpense("Test", 1000.0, alice, splits, ExpenseType.EXACT, null);
            
            // Bob should owe Alice 700
            double balance = service.getBalance(bob, alice);
            return Math.abs(balance - 700.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testPercentageSplit() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            List<Split> splits = Arrays.asList(
                new Split(alice, 40.0),  // 40%
                new Split(bob, 60.0)     // 60%
            );

            Expense expense = service.addExpense("Test", 1000.0, alice, splits, ExpenseType.PERCENTAGE, null);
            
            // Bob should owe Alice 600
            double balance = service.getBalance(bob, alice);
            return Math.abs(balance - 600.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testShareSplit() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            List<Split> splits = Arrays.asList(
                new Split(alice, 1),  // 1 share
                new Split(bob, 3)     // 3 shares (75%)
            );

            Expense expense = service.addExpense("Test", 1000.0, alice, splits, ExpenseType.SHARE, null);
            
            // Bob should owe Alice 750 (3/4 of 1000)
            double balance = service.getBalance(bob, alice);
            return Math.abs(balance - 750.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testGroupManagement() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            Group group = service.createGroup("Test Group", alice);
            service.addMemberToGroup(group.getId(), bob);

            Group retrievedGroup = service.getGroup(group.getId());
            return retrievedGroup != null && 
                   retrievedGroup.getMembers().size() == 2 &&
                   retrievedGroup.getName().equals("Test Group");
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testSettlement() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            // Create expense
            List<Split> splits = Arrays.asList(
                new Split(alice, 0),
                new Split(bob, 0)
            );
            service.addExpense("Test", 1000.0, alice, splits, ExpenseType.EQUAL, null);

            // Bob owes Alice 500
            double beforeBalance = service.getBalance(bob, alice);

            // Bob pays Alice 300
            service.recordSettlement(bob, alice, 300.0, "Test payment");

            // Bob should now owe Alice 200
            double afterBalance = service.getBalance(bob, alice);
            return Math.abs(beforeBalance - 500.0) < 0.01 && 
                   Math.abs(afterBalance - 200.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testBalanceTracking() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");

            // Multiple expenses
            List<Split> splits1 = Arrays.asList(new Split(alice, 0), new Split(bob, 0));
            service.addExpense("Expense 1", 1000.0, alice, splits1, ExpenseType.EQUAL, null);

            List<Split> splits2 = Arrays.asList(new Split(alice, 0), new Split(bob, 0));
            service.addExpense("Expense 2", 500.0, bob, splits2, ExpenseType.EQUAL, null);

            // Bob owes Alice: 500 (from expense 1)
            // Alice owes Bob: 250 (from expense 2)
            // Net: Bob owes Alice 250
            double balance = service.getBalance(bob, alice);
            return Math.abs(balance - 250.0) < 0.01;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testDebtSimplification() {
        try {
            SplitwiseService service = new SplitwiseService();
            User alice = service.registerUser("Alice", "alice@test.com", "111");
            User bob = service.registerUser("Bob", "bob@test.com", "222");
            User charlie = service.registerUser("Charlie", "charlie@test.com", "333");

            // Create circular debt scenario
            List<Split> splits1 = Arrays.asList(new Split(alice, 0), new Split(bob, 0));
            service.addExpense("Expense 1", 900.0, alice, splits1, ExpenseType.EQUAL, null);

            List<Split> splits2 = Arrays.asList(new Split(bob, 0), new Split(charlie, 0));
            service.addExpense("Expense 2", 900.0, bob, splits2, ExpenseType.EQUAL, null);

            List<Split> splits3 = Arrays.asList(new Split(alice, 0), new Split(charlie, 0));
            service.addExpense("Expense 3", 900.0, charlie, splits3, ExpenseType.EQUAL, null);

            List<Transaction> simplified = service.simplifyDebts(Arrays.asList(alice, bob, charlie));
            
            // Should simplify the circular debts into fewer transactions
            return simplified != null && simplified.size() <= 2;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
