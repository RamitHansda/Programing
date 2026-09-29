package interview.rippling.expenseengine;

import java.util.List;
import java.util.Optional;

/**
 * Policy rule. New rule types are added by implementing this interface —
 * the engine never hard-codes restaurant / airfare / trip logic.
 *
 * <p>Designed so an API can later materialize rule instances from JSON
 * without redeploying the evaluator for each company policy subset.</p>
 */
public interface ExpensePolicyRule {
    String id();

    String name();

    RuleScope scope();

    /**
     * Evaluate against a single expense. Trip-scoped rules return empty here;
     * the engine only calls this for {@link RuleScope#EXPENSE} rules.
     */
    default Optional<Violation> evaluateExpense(Expense expense) {
        return Optional.empty();
    }

    /**
     * Evaluate against all expenses belonging to one trip. Expense-scoped rules
     * return empty here.
     */
    default Optional<Violation> evaluateTrip(String tripId, List<Expense> tripExpenses) {
        return Optional.empty();
    }
}
