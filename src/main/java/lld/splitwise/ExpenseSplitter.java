package lld.splitwise;

import java.util.List;

/**
 * Interface for different expense splitting strategies
 */
public interface ExpenseSplitter {
    /**
     * Validates if the split configuration is valid for the total amount
     */
    boolean validate(double totalAmount, List<Split> splits);

    /**
     * Calculates and updates the split amounts
     */
    void calculateSplits(double totalAmount, List<Split> splits);
}
