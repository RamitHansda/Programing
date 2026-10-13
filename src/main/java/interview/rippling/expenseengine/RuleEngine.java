package interview.rippling.expenseengine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Corporate-card travel expense rules engine (Rippling phone-screen classic).
 *
 * <p>{@code evaluateRules(rules, expenses)} flags expenses and trips for human
 * review. Rules are Strategy objects so managers can pass any subset and new
 * rule types can ship without rewriting the evaluator loop.</p>
 */
public final class RuleEngine {

    /**
     * Evaluate all rules against all expenses.
     *
     * <ul>
     *   <li>Every matching violation is kept (no short-circuit) — expense
     *       {@code 004} must surface both airfare and max-amount hits.</li>
     *   <li>Malformed amount strings throw {@link IllegalArgumentException}.</li>
     *   <li>Amount comparisons use {@link BigDecimal} numeric order.</li>
     * </ul>
     */
    public EvaluationResult evaluateRules(
            List<? extends ExpensePolicyRule> rules,
            List<Map<String, String>> rawExpenses) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(rawExpenses, "expenses");

        List<Expense> expenses = new ArrayList<>(rawExpenses.size());
        for (Map<String, String> raw : rawExpenses) {
            expenses.add(Expense.fromMap(raw));
        }

        Map<String, List<Violation>> expenseViolations = new LinkedHashMap<>();
        Map<String, List<Violation>> tripViolations = new LinkedHashMap<>();

        List<ExpensePolicyRule> expenseRules = new ArrayList<>();
        List<ExpensePolicyRule> tripRules = new ArrayList<>();
        for (ExpensePolicyRule rule : rules) {
            if (rule.scope() == RuleScope.EXPENSE) {
                expenseRules.add(rule);
            } else {
                tripRules.add(rule);
            }
        }

        for (Expense expense : expenses) {
            for (ExpensePolicyRule rule : expenseRules) {
                rule.evaluateExpense(expense).ifPresent(v ->
                        expenseViolations
                                .computeIfAbsent(expense.expenseId(), id -> new ArrayList<>())
                                .add(v));
            }
        }

        if (!tripRules.isEmpty()) {
            Map<String, List<Expense>> byTrip = groupByTrip(expenses);
            for (Map.Entry<String, List<Expense>> entry : byTrip.entrySet()) {
                String tripId = entry.getKey();
                List<Expense> tripExpenses = entry.getValue();
                for (ExpensePolicyRule rule : tripRules) {
                    rule.evaluateTrip(tripId, tripExpenses).ifPresent(v ->
                            tripViolations
                                    .computeIfAbsent(tripId, id -> new ArrayList<>())
                                    .add(v));
                }
            }
        }

        return new EvaluationResult(expenseViolations, tripViolations);
    }

    private static Map<String, List<Expense>> groupByTrip(List<Expense> expenses) {
        Map<String, List<Expense>> byTrip = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            byTrip.computeIfAbsent(expense.tripId(), id -> new ArrayList<>()).add(expense);
        }
        return byTrip;
    }

    /** Standard Part-1 + Part-2 Rippling sample policy set. */
    public static List<ExpensePolicyRule> defaultTravelPolicies() {
        return List.of(
                MaxAmountRule.forVendorType("R1", "restaurant", new BigDecimal("75")),
                BanFieldRule.banExpenseType("R2", "airfare"),
                BanFieldRule.banExpenseType("R3", "entertainment"),
                MaxAmountRule.anyExpense("R4", new BigDecimal("250")),
                TripTotalMaxRule.of("R5", new BigDecimal("2000")),
                TripFieldSumMaxRule.forExpenseType("R6", "meals", new BigDecimal("200")));
    }
}
