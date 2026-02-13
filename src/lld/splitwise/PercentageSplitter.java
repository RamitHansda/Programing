package lld.splitwise;

import java.util.List;

/**
 * Splits expense based on percentage specified for each participant
 * The percentages should be stored in the Split amounts initially
 */
public class PercentageSplitter implements ExpenseSplitter {

    @Override
    public boolean validate(double totalAmount, List<Split> splits) {
        if (splits == null || splits.isEmpty()) {
            return false;
        }

        double totalPercentage = 0;
        for (Split split : splits) {
            if (split.getAmount() < 0 || split.getAmount() > 100) {
                return false;
            }
            totalPercentage += split.getAmount();
        }

        // Total percentage should be 100 (allow 0.1% tolerance)
        return Math.abs(totalPercentage - 100) < 0.1;
    }

    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        if (!validate(totalAmount, splits)) {
            throw new IllegalArgumentException("Percentages must add up to 100");
        }

        // Convert percentages to actual amounts
        double sum = 0;
        for (int i = 0; i < splits.size(); i++) {
            Split split = splits.get(i);
            if (i == splits.size() - 1) {
                // Last person gets the remainder to handle rounding
                split.setAmount(totalAmount - sum);
            } else {
                double percentage = split.getAmount();
                double amount = Math.round((totalAmount * percentage / 100.0) * 100.0) / 100.0;
                split.setAmount(amount);
                sum += amount;
            }
        }
    }
}
