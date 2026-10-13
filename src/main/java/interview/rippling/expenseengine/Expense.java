package interview.rippling.expenseengine;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed expense record. Interview input arrives as {@code Map<String, String>};
 * money is stored as {@link BigDecimal} so lexicographic string compares cannot
 * invert thresholds (e.g. {@code "999"} vs {@code "1000"}).
 */
public final class Expense {
    private final String expenseId;
    private final String tripId;
    private final BigDecimal amountUsd;
    private final String expenseType;
    private final String vendorType;
    private final String vendorName;
    private final Map<String, String> raw;

    public Expense(
            String expenseId,
            String tripId,
            BigDecimal amountUsd,
            String expenseType,
            String vendorType,
            String vendorName,
            Map<String, String> raw) {
        this.expenseId = Objects.requireNonNull(expenseId, "expenseId");
        this.tripId = Objects.requireNonNull(tripId, "tripId");
        this.amountUsd = Objects.requireNonNull(amountUsd, "amountUsd");
        this.expenseType = expenseType == null ? "" : expenseType;
        this.vendorType = vendorType == null ? "" : vendorType;
        this.vendorName = vendorName == null ? "" : vendorName;
        this.raw = Map.copyOf(raw);
    }

    public static Expense fromMap(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        String expenseId = required(fields, "expense_id");
        String tripId = required(fields, "trip_id");
        String amountRaw = required(fields, "amount_usd");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountRaw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "amount_usd is not a decimal for expense_id=" + expenseId + ": " + amountRaw, ex);
        }
        return new Expense(
                expenseId,
                tripId,
                amount,
                fields.getOrDefault("expense_type", ""),
                fields.getOrDefault("vendor_type", ""),
                fields.getOrDefault("vendor_name", ""),
                fields);
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    public String expenseId() {
        return expenseId;
    }

    public String tripId() {
        return tripId;
    }

    public BigDecimal amountUsd() {
        return amountUsd;
    }

    public String expenseType() {
        return expenseType;
    }

    public String vendorType() {
        return vendorType;
    }

    public String vendorName() {
        return vendorName;
    }

    public Map<String, String> raw() {
        return raw;
    }

    public String field(String name) {
        return switch (name) {
            case "expense_id" -> expenseId;
            case "trip_id" -> tripId;
            case "amount_usd" -> amountUsd.toPlainString();
            case "expense_type" -> expenseType;
            case "vendor_type" -> vendorType;
            case "vendor_name" -> vendorName;
            default -> raw.getOrDefault(name, "");
        };
    }

    @Override
    public String toString() {
        return "Expense{id=" + expenseId + ", trip=" + tripId + ", amount=" + amountUsd
                + ", type=" + expenseType + ", vendorType=" + vendorType + "}";
    }
}
