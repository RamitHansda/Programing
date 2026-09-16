package interview.rippling.expenseengine;

import java.util.List;
import java.util.Map;

/**
 * Runnable demo for the Rippling Travel Expense / Corporate Card Rules Engine interview.
 *
 * <pre>
 *   mvn -q -DskipTests compile
 *   java -cp target/classes interview.rippling.expenseengine.ExpenseRulesDemo
 * </pre>
 */
public final class ExpenseRulesDemo {
    public static void main(String[] args) {
        RuleEngine engine = new RuleEngine();
        List<ExpensePolicyRule> rules = RuleEngine.defaultTravelPolicies();
        List<Map<String, String>> expenses = SampleData.expenses();

        EvaluationResult result = engine.evaluateRules(rules, expenses);

        System.out.println("=== Rippling Travel Expense Rules Engine ===");
        System.out.println("Rules: " + rules.size() + " | Expenses: " + expenses.size());
        System.out.println();

        System.out.println("-- Expense violations --");
        if (result.expenseViolations().isEmpty()) {
            System.out.println("(none)");
        } else {
            result.expenseViolations().forEach((expenseId, violations) -> {
                System.out.println("Expense " + expenseId + ":");
                violations.forEach(v -> System.out.println("  - [" + v.ruleId() + "] " + v.message()));
            });
        }

        System.out.println();
        System.out.println("-- Trip violations --");
        if (result.tripViolations().isEmpty()) {
            System.out.println("(none)");
        } else {
            result.tripViolations().forEach((tripId, violations) -> {
                System.out.println("Trip " + tripId + ":");
                violations.forEach(v -> System.out.println("  - [" + v.ruleId() + "] " + v.message()));
            });
        }

        System.out.println();
        System.out.println("Expected: expenses 003,004,007 flagged; trip 002 flagged twice.");
    }
}
