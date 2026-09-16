package interview.rippling.expenseengine;

import java.util.Objects;
import java.util.Optional;

/** Bans any expense whose {@code field} equals {@code bannedValue}. */
public final class BanFieldRule implements ExpensePolicyRule {
    private final String id;
    private final String name;
    private final String field;
    private final String bannedValue;

    public BanFieldRule(String id, String name, String field, String bannedValue) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.field = Objects.requireNonNull(field);
        this.bannedValue = Objects.requireNonNull(bannedValue);
    }

    public static BanFieldRule banExpenseType(String id, String expenseType) {
        return new BanFieldRule(id, "Ban " + expenseType, "expense_type", expenseType);
    }

    public static BanFieldRule banVendorType(String id, String vendorType) {
        return new BanFieldRule(id, "Ban vendor_type=" + vendorType, "vendor_type", vendorType);
    }

    public static BanFieldRule banVendorName(String id, String vendorName) {
        return new BanFieldRule(id, "Ban vendor " + vendorName, "vendor_name", vendorName);
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
        if (bannedValue.equalsIgnoreCase(expense.field(field))) {
            return Optional.of(new Violation(
                    id,
                    name,
                    RuleScope.EXPENSE,
                    expense.expenseId(),
                    field + "='" + expense.field(field) + "' is banned by policy"));
        }
        return Optional.empty();
    }
}
