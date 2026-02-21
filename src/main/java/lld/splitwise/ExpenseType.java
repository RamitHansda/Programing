package lld.splitwise;

/**
 * Types of expenses supported
 */
public enum ExpenseType {
    EQUAL,      // Split equally among all participants
    EXACT,      // Exact amounts specified for each participant
    PERCENTAGE, // Percentage-based split
    SHARE       // Share-based split (e.g., 1:2:3 ratio)
}
