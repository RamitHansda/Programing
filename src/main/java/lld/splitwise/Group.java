package lld.splitwise;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a group of users who share expenses
 */
public class Group {
    private final String id;
    private final String name;
    private final List<User> members;
    private final List<Expense> expenses;
    private final LocalDateTime createdAt;
    private final User createdBy;

    public Group(String name, User createdBy) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.members = new ArrayList<>();
        this.expenses = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.createdBy = createdBy;
        this.members.add(createdBy);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<User> getMembers() {
        return new ArrayList<>(members);
    }

    public List<Expense> getExpenses() {
        return new ArrayList<>(expenses);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void addMember(User user) {
        if (!members.contains(user)) {
            members.add(user);
        }
    }

    public void removeMember(User user) {
        members.remove(user);
    }

    public void addExpense(Expense expense) {
        expenses.add(expense);
    }

    @Override
    public String toString() {
        return "Group{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", members=" + members.size() +
                ", expenses=" + expenses.size() +
                '}';
    }
}
