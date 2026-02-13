package lld.splitwise;

/**
 * Represents balance between two users
 */
public class Balance {
    private final User user1;
    private final User user2;
    private double amount; // Positive means user1 owes user2, negative means user2 owes user1

    public Balance(User user1, User user2, double amount) {
        this.user1 = user1;
        this.user2 = user2;
        this.amount = amount;
    }

    public User getUser1() {
        return user1;
    }

    public User getUser2() {
        return user2;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public void addAmount(double amount) {
        this.amount += amount;
    }

    @Override
    public String toString() {
        if (Math.abs(amount) < 0.01) {
            return user1.getName() + " and " + user2.getName() + " are settled up";
        } else if (amount > 0) {
            return user1.getName() + " owes " + user2.getName() + ": ₹" + String.format("%.2f", amount);
        } else {
            return user2.getName() + " owes " + user1.getName() + ": ₹" + String.format("%.2f", -amount);
        }
    }
}
