package lld.splitwise;

/**
 * Represents how much a user owes or is owed in an expense
 */
public class Split {
    private final User user;
    private double amount;

    public Split(User user, double amount) {
        this.user = user;
        this.amount = amount;
    }

    public User getUser() {
        return user;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    @Override
    public String toString() {
        return user.getName() + ": ₹" + String.format("%.2f", amount);
    }
}
