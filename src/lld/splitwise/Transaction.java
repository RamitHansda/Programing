package lld.splitwise;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a settlement transaction between two users
 */
public class Transaction {
    private final String id;
    private final User paidBy;
    private final User paidTo;
    private final double amount;
    private final LocalDateTime timestamp;
    private final String description;

    public Transaction(User paidBy, User paidTo, double amount, String description) {
        this.id = UUID.randomUUID().toString();
        this.paidBy = paidBy;
        this.paidTo = paidTo;
        this.amount = amount;
        this.timestamp = LocalDateTime.now();
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public User getPaidTo() {
        return paidTo;
    }

    public double getAmount() {
        return amount;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return paidBy.getName() + " paid " + paidTo.getName() + " ₹" + 
               String.format("%.2f", amount) + " - " + description;
    }
}
