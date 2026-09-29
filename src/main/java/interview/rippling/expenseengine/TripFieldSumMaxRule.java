package interview.rippling.expenseengine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Flags a trip when the sum of expenses matching {@code filterField=filterValue}
 * exceeds {@code maxTotal} (e.g. meals per trip &gt; $200).
 */
public final class TripFieldSumMaxRule implements ExpensePolicyRule {
    private final String id;
    private final String name;
    private final String filterField;
    private final String filterValue;
    private final BigDecimal maxTotal;

    public TripFieldSumMaxRule(
            String id,
            String name,
            String filterField,
            String filterValue,
            BigDecimal maxTotal) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.filterField = Objects.requireNonNull(filterField);
        this.filterValue = Objects.requireNonNull(filterValue);
        this.maxTotal = Objects.requireNonNull(maxTotal);
    }

    public static TripFieldSumMaxRule forExpenseType(String id, String expenseType, BigDecimal maxTotal) {
        return new TripFieldSumMaxRule(
                id,
                "Trip " + expenseType + " max $" + maxTotal.toPlainString(),
                "expense_type",
                expenseType,
                maxTotal);
    }

    public static TripFieldSumMaxRule forVendorType(String id, String vendorType, BigDecimal maxTotal) {
        return new TripFieldSumMaxRule(
                id,
                "Trip vendor_type=" + vendorType + " max $" + maxTotal.toPlainString(),
                "vendor_type",
                vendorType,
                maxTotal);
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
        int matched = 0;
        for (Expense expense : tripExpenses) {
            if (filterValue.equalsIgnoreCase(expense.field(filterField))) {
                total = total.add(expense.amountUsd());
                matched++;
            }
        }
        if (matched > 0 && total.compareTo(maxTotal) > 0) {
            return Optional.of(new Violation(
                    id,
                    name,
                    RuleScope.TRIP,
                    tripId,
                    "sum of " + filterField + "='" + filterValue + "' is $"
                            + total.toPlainString() + " (max $" + maxTotal.toPlainString() + ")"));
        }
        return Optional.empty();
    }
}
