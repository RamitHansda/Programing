package lld.splitwise;

import java.util.List;

/**
 * Splits expense based on share ratio (e.g., 1:2:3)
 * The shares should be stored in the Split amounts initially
 */
public class ShareSplitter implements ExpenseSplitter {

    @Override
    public boolean validate(double totalAmount, List<Split> splits) {
        if (splits == null || splits.isEmpty()) {
            return false;
        }

        for (Split split : splits) {
            if (split.getAmount() <= 0) {
                return false;
            }
        }

        return totalAmount > 0;
    }

    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        if (!validate(totalAmount, splits)) {
            throw new IllegalArgumentException("Invalid share configuration");
        }

        // Calculate total shares
        double totalShares = splits.stream()
                .mapToDouble(Split::getAmount)
                .sum();

        // Convert shares to actual amounts
        double sum = 0;
        for (int i = 0; i < splits.size(); i++) {
            Split split = splits.get(i);
            if (i == splits.size() - 1) {
                // Last person gets the remainder to handle rounding
                split.setAmount(totalAmount - sum);
            } else {
                double shares = split.getAmount();
                double amount = Math.round((totalAmount * shares / totalShares) * 100.0) / 100.0;
                split.setAmount(amount);
                sum += amount;
            }
        }
    }
}
