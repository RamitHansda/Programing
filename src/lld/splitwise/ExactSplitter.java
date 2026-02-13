package lld.splitwise;

import java.util.List;

/**
 * Splits expense based on exact amounts specified for each participant
 */
public class ExactSplitter implements ExpenseSplitter {

    @Override
    public boolean validate(double totalAmount, List<Split> splits) {
        if (splits == null || splits.isEmpty()) {
            return false;
        }

        double sum = 0;
        for (Split split : splits) {
            if (split.getAmount() < 0) {
                return false;
            }
            sum += split.getAmount();
        }

        // Allow small rounding differences (1 paisa)
        return Math.abs(sum - totalAmount) < 0.01;
    }

    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        if (!validate(totalAmount, splits)) {
            double sum = splits.stream().mapToDouble(Split::getAmount).sum();
            throw new IllegalArgumentException(
                "Split amounts don't match total. Total: " + totalAmount + ", Sum: " + sum
            );
        }
        // Amounts are already set by the user, no calculation needed
    }
}
