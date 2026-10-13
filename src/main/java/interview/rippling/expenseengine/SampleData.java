package interview.rippling.expenseengine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical Rippling travel-expense sample used across phone screens. */
public final class SampleData {
    private SampleData() {}

    public static List<Map<String, String>> expenses() {
        return List.of(
                expense("001", "001", "49.99", "supplies", "restaurant", "Outback Roadhouse"),
                expense("002", "001", "125.00", "supplies", "retailer", "Staples"),
                expense("003", "002", "153.00", "meals", "restaurant", "Olive Yurt"),
                expense("004", "002", "1996.00", "airfare", "transportation", "Southeast Airlines"),
                expense("005", "002", "34.68", "meals", "restaurant", "The Great Grill"),
                expense("006", "002", "22.40", "meals", "restaurant", "The Great Grill"),
                expense("007", "003", "59.50", "entertainment", "theater", "Silver Screen"));
    }

    public static Map<String, String> expense(
            String expenseId,
            String tripId,
            String amountUsd,
            String expenseType,
            String vendorType,
            String vendorName) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("expense_id", expenseId);
        row.put("trip_id", tripId);
        row.put("amount_usd", amountUsd);
        row.put("expense_type", expenseType);
        row.put("vendor_type", vendorType);
        row.put("vendor_name", vendorName);
        return row;
    }
}
