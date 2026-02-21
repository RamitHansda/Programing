package lld.splitwise;

/**
 * Factory to get appropriate expense splitter based on type
 */
public class ExpenseSplitterFactory {
    
    public static ExpenseSplitter getSplitter(ExpenseType type) {
        switch (type) {
            case EQUAL:
                return new EqualSplitter();
            case EXACT:
                return new ExactSplitter();
            case PERCENTAGE:
                return new PercentageSplitter();
            case SHARE:
                return new ShareSplitter();
            default:
                throw new IllegalArgumentException("Unknown expense type: " + type);
        }
    }
}
