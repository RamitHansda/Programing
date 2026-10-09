package interview.rippling.expenseengine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Flags a trip when the sum of all expense amounts exceeds {@code maxTotal}. */
public final class TripTotalMaxRule implements ExpensePolicyRule {
    private final String id;
    private final String name;
    private final BigDecimal maxTotal;

    public TripTotalMaxRule(String id, String name, BigDecimal maxTotal) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.maxTotal = Objects.requireNonNull(maxTotal);
    }

    public static TripTotalMaxRule of(String id, BigDecimal maxTotal) {
        return new TripTotalMaxRule(id, "Trip total max $" + maxTotal.toPlainString(), maxTotal);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RuleScope scope() {
        return RuleScope.TRIP;
    }

    @Override
    public Optional<Violation> evaluateTrip(String tripId, List<Expense> tripExpenses) {
        BigDecimal total = BigDecimal.ZERO;
        for (Expense expense : tripExpenses) {
            total = total.add(expense.amountUsd());
        }
        if (total.compareTo(maxTotal) > 0) {
            return Optional.of(new Violation(
                    id,
                    name,
                    RuleScope.TRIP,
                    tripId,
                    "trip total $" + total.toPlainString() + " exceeds max $" + maxTotal.toPlainString()));
        }
        return Optional.empty();
    }
}
