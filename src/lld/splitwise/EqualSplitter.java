package lld.splitwise;

import java.util.List;

/**
 * Splits expense equally among all participants
 */
public class EqualSplitter implements ExpenseSplitter {

    @Override
    public boolean validate(double totalAmount, List<Split> splits) {
        if (splits == null || splits.isEmpty()) {
            return false;
        }
        return totalAmount > 0;
    }

    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        if (!validate(totalAmount, splits)) {
            throw new IllegalArgumentException("Invalid split configuration");
        }

        int numPeople = splits.size();
        double splitAmount = totalAmount / numPeople;

        // Handle rounding - give the extra cents to the first person
        double sum = 0;
        for (int i = 0; i < splits.size(); i++) {
            if (i == splits.size() - 1) {
                // Last person gets the remainder to handle rounding
                splits.get(i).setAmount(totalAmount - sum);
            } else {
                double amount = Math.round(splitAmount * 100.0) / 100.0;
                splits.get(i).setAmount(amount);
                sum += amount;
            }
        }
    }
}
