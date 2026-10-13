package interview.rippling.expenseengine;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Flags an expense whose amount is strictly greater than {@code maxAmount}.
 * Optional filter restricts the rule to a field/value (e.g. vendor_type=restaurant).
 *
 * <p>Boundary: amount == maxAmount is allowed (strict {@code >}).</p>
 */
public final class MaxAmountRule implements ExpensePolicyRule {
    private final String id;
    private final String name;
    private final BigDecimal maxAmount;
    private final String filterField;
    private final String filterValue;

    public MaxAmountRule(String id, String name, BigDecimal maxAmount) {
        this(id, name, maxAmount, null, null);
    }

    public MaxAmountRule(
            String id,
            String name,
            BigDecimal maxAmount,
            String filterField,
            String filterValue) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.maxAmount = Objects.requireNonNull(maxAmount);
        this.filterField = filterField;
        this.filterValue = filterValue;
        if ((filterField == null) != (filterValue == null)) {
            throw new IllegalArgumentException("filterField and filterValue must both be set or both null");
        }
    }

    public static MaxAmountRule anyExpense(String id, BigDecimal maxAmount) {
        return new MaxAmountRule(id, "Max expense $" + maxAmount.toPlainString(), maxAmount);
    }

    public static MaxAmountRule forVendorType(String id, String vendorType, BigDecimal maxAmount) {
        return new MaxAmountRule(
                id,
                "Max " + vendorType + " expense $" + maxAmount.toPlainString(),
                maxAmount,
                "vendor_type",
                vendorType);
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
        return RuleScope.EXPENSE;
    }

    @Override
    public Optional<Violation> evaluateExpense(Expense expense) {
        if (filterField != null && !filterValue.equalsIgnoreCase(expense.field(filterField))) {
            return Optional.empty();
        }
        if (expense.amountUsd().compareTo(maxAmount) > 0) {
            String detail = filterField == null
                    ? "amount $" + expense.amountUsd().toPlainString()
                    : filterField + "='" + filterValue + "' amount $" + expense.amountUsd().toPlainString();
            return Optional.of(new Violation(
                    id,
                    name,
                    RuleScope.EXPENSE,
                    expense.expenseId(),
                    detail + " exceeds max $" + maxAmount.toPlainString()));
        }
        return Optional.empty();
    }
}
