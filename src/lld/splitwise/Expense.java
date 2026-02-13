package lld.splitwise;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Represents an expense in Splitwise
 */
public class Expense {
    private final String id;
    private final String description;
    private final double totalAmount;
    private final User paidBy;
    private final List<Split> splits;
    private final ExpenseType expenseType;
    private final LocalDateTime createdAt;
    private final String groupId; // null if not part of a group

    public Expense(String description, double totalAmount, User paidBy, 
                   List<Split> splits, ExpenseType expenseType, String groupId) {
        this.id = UUID.randomUUID().toString();
        this.description = description;
        this.totalAmount = totalAmount;
        this.paidBy = paidBy;
        this.splits = splits;
        this.expenseType = expenseType;
        this.createdAt = LocalDateTime.now();
        this.groupId = groupId;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public List<Split> getSplits() {
        return splits;
    }

    public ExpenseType getExpenseType() {
        return expenseType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getGroupId() {
        return groupId;
    }

    @Override
    public String toString() {
        return "Expense{" +
                "id='" + id + '\'' +
                ", description='" + description + '\'' +
                ", totalAmount=" + totalAmount +
                ", paidBy=" + paidBy.getName() +
                ", expenseType=" + expenseType +
                ", createdAt=" + createdAt +
                '}';
    }
}
