package lld.splitwise;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main service class for Splitwise operations
 * Manages expenses, balances, groups, and settlements
 */
public class SplitwiseService {
    private final Map<String, User> users;
    private final Map<String, Group> groups;
    private final List<Expense> expenses;
    private final List<Transaction> transactions;
    private final Map<String, Map<String, Double>> balanceSheet;

    public SplitwiseService() {
        this.users = new HashMap<>();
        this.groups = new HashMap<>();
        this.expenses = new ArrayList<>();
        this.transactions = new ArrayList<>();
        this.balanceSheet = new HashMap<>();
    }

    /**
     * Register a new user
     */
    public User registerUser(String name, String email, String phoneNumber) {
        User user = new User(name, email, phoneNumber);
        users.put(user.getId(), user);
        balanceSheet.put(user.getId(), new HashMap<>());
        return user;
    }

    /**
     * Create a new group
     */
    public Group createGroup(String name, User creator) {
        if (!users.containsKey(creator.getId())) {
            throw new IllegalArgumentException("Creator must be a registered user");
        }
        Group group = new Group(name, creator);
        groups.put(group.getId(), group);
        return group;
    }

    /**
     * Add a member to a group
     */
    public void addMemberToGroup(String groupId, User user) {
        Group group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group not found");
        }
        if (!users.containsKey(user.getId())) {
            throw new IllegalArgumentException("User must be registered");
        }
        group.addMember(user);
    }

    /**
     * Add an expense
     */
    public Expense addExpense(String description, double totalAmount, User paidBy,
                              List<Split> splits, ExpenseType type, String groupId) {
        // Validate payer is registered
        if (!users.containsKey(paidBy.getId())) {
            throw new IllegalArgumentException("Payer must be a registered user");
        }

        // Validate all split users are registered
        for (Split split : splits) {
            if (!users.containsKey(split.getUser().getId())) {
                throw new IllegalArgumentException("All split users must be registered");
            }
        }

        // Calculate split amounts using appropriate strategy
        ExpenseSplitter splitter = ExpenseSplitterFactory.getSplitter(type);
        splitter.calculateSplits(totalAmount, splits);

        // Create expense
        Expense expense = new Expense(description, totalAmount, paidBy, splits, type, groupId);
        expenses.add(expense);

        // Add to group if applicable
        if (groupId != null && groups.containsKey(groupId)) {
            groups.get(groupId).addExpense(expense);
        }

        // Update balance sheet
        updateBalances(expense);

        return expense;
    }

    /**
     * Update balance sheet after an expense
     */
    private void updateBalances(Expense expense) {
        User paidBy = expense.getPaidBy();
        
        for (Split split : expense.getSplits()) {
            User user = split.getUser();
            double amount = split.getAmount();

            // Skip if user paid for themselves
            if (user.getId().equals(paidBy.getId())) {
                continue;
            }

            // Update balance: user owes paidBy
            updateBalance(user.getId(), paidBy.getId(), amount);
        }
    }

    /**
     * Update balance between two users
     */
    private void updateBalance(String userId1, String userId2, double amount) {
        Map<String, Double> user1Balances = balanceSheet.get(userId1);
        user1Balances.put(userId2, user1Balances.getOrDefault(userId2, 0.0) + amount);

        Map<String, Double> user2Balances = balanceSheet.get(userId2);
        user2Balances.put(userId1, user2Balances.getOrDefault(userId1, 0.0) - amount);
    }

    /**
     * Record a settlement between two users
     */
    public Transaction recordSettlement(User paidBy, User paidTo, double amount, String description) {
        if (!users.containsKey(paidBy.getId()) || !users.containsKey(paidTo.getId())) {
            throw new IllegalArgumentException("Both users must be registered");
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("Settlement amount must be positive");
        }

        Transaction transaction = new Transaction(paidBy, paidTo, amount, description);
        transactions.add(transaction);

        // Update balances
        updateBalance(paidBy.getId(), paidTo.getId(), -amount);

        return transaction;
    }

    /**
     * Get balance between two users
     */
    public double getBalance(User user1, User user2) {
        Map<String, Double> user1Balances = balanceSheet.get(user1.getId());
        if (user1Balances == null) {
            return 0.0;
        }
        return user1Balances.getOrDefault(user2.getId(), 0.0);
    }

    /**
     * Get all balances for a user
     */
    public List<Balance> getUserBalances(User user) {
        List<Balance> balances = new ArrayList<>();
        Map<String, Double> userBalances = balanceSheet.get(user.getId());
        
        if (userBalances == null) {
            return balances;
        }

        for (Map.Entry<String, Double> entry : userBalances.entrySet()) {
            double amount = entry.getValue();
            if (Math.abs(amount) > 0.01) { // Ignore negligible amounts
                User otherUser = users.get(entry.getKey());
                balances.add(new Balance(user, otherUser, amount));
            }
        }

        return balances;
    }

    /**
     * Get all balances in the system
     */
    public List<Balance> getAllBalances() {
        List<Balance> balances = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (Map.Entry<String, Map<String, Double>> entry : balanceSheet.entrySet()) {
            String userId1 = entry.getKey();
            User user1 = users.get(userId1);

            for (Map.Entry<String, Double> balanceEntry : entry.getValue().entrySet()) {
                String userId2 = balanceEntry.getKey();
                double amount = balanceEntry.getValue();

                // Only add if amount is significant and not already processed
                String key = userId1.compareTo(userId2) < 0 ? userId1 + "-" + userId2 : userId2 + "-" + userId1;
                
                if (Math.abs(amount) > 0.01 && !processed.contains(key)) {
                    User user2 = users.get(userId2);
                    balances.add(new Balance(user1, user2, amount));
                    processed.add(key);
                }
            }
        }

        return balances;
    }

    /**
     * Get group balances (simplified view of who owes whom in a group)
     */
    public List<Balance> getGroupBalances(String groupId) {
        Group group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group not found");
        }

        Set<String> memberIds = group.getMembers().stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        List<Balance> balances = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (User user1 : group.getMembers()) {
            Map<String, Double> user1Balances = balanceSheet.get(user1.getId());
            if (user1Balances == null) continue;

            for (Map.Entry<String, Double> entry : user1Balances.entrySet()) {
                String userId2 = entry.getKey();
                
                // Only include if other user is in the group
                if (!memberIds.contains(userId2)) continue;

                double amount = entry.getValue();
                String key = user1.getId().compareTo(userId2) < 0 ? 
                            user1.getId() + "-" + userId2 : 
                            userId2 + "-" + user1.getId();

                if (Math.abs(amount) > 0.01 && !processed.contains(key)) {
                    User user2 = users.get(userId2);
                    balances.add(new Balance(user1, user2, amount));
                    processed.add(key);
                }
            }
        }

        return balances;
    }

    /**
     * Get a specific user's balances within a group
     * Shows what this user owes to or is owed by other group members
     */
    public List<Balance> getUserBalancesInGroup(User user, String groupId) {
        Group group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("Group not found");
        }

        if (!group.getMembers().contains(user)) {
            throw new IllegalArgumentException("User is not a member of this group");
        }

        List<Balance> balances = new ArrayList<>();
        Map<String, Double> userBalances = balanceSheet.get(user.getId());
        
        if (userBalances == null) {
            return balances;
        }

        // Get all group member IDs
        Set<String> memberIds = group.getMembers().stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        // Filter balances to only include group members
        for (Map.Entry<String, Double> entry : userBalances.entrySet()) {
            String otherUserId = entry.getKey();
            
            // Only include if other user is in the group
            if (!memberIds.contains(otherUserId)) continue;
            
            double amount = entry.getValue();
            if (Math.abs(amount) > 0.01) { // Ignore negligible amounts
                User otherUser = users.get(otherUserId);
                balances.add(new Balance(user, otherUser, amount));
            }
        }

        return balances;
    }

    /**
     * Get total amount a user owes in a group (sum of all positive balances)
     */
    public double getTotalUserOwesInGroup(User user, String groupId) {
        List<Balance> balances = getUserBalancesInGroup(user, groupId);
        
        double totalOwes = 0.0;
        for (Balance balance : balances) {
            if (balance.getAmount() > 0) {
                totalOwes += balance.getAmount();
            }
        }
        
        return totalOwes;
    }

    /**
     * Get total amount a user is owed in a group (sum of all negative balances)
     */
    public double getTotalUserOwedInGroup(User user, String groupId) {
        List<Balance> balances = getUserBalancesInGroup(user, groupId);
        
        double totalOwed = 0.0;
        for (Balance balance : balances) {
            if (balance.getAmount() < 0) {
                totalOwed += Math.abs(balance.getAmount());
            }
        }
        
        return totalOwed;
    }

    /**
     * Get net balance for a user in a group (positive = owes, negative = is owed)
     */
    public double getUserNetBalanceInGroup(User user, String groupId) {
        double owes = getTotalUserOwesInGroup(user, groupId);
        double owed = getTotalUserOwedInGroup(user, groupId);
        return owes - owed;
    }

    /**
     * Simplify debts - calculate minimum number of transactions needed to settle all debts
     * Uses a greedy algorithm to minimize transactions
     */
    public List<Transaction> simplifyDebts(List<User> participants) {
        // Calculate net balance for each user
        Map<String, Double> netBalance = new HashMap<>();
        
        for (User user : participants) {
            double balance = 0;
            Map<String, Double> userBalances = balanceSheet.get(user.getId());
            if (userBalances != null) {
                for (String otherUserId : participants.stream()
                        .map(User::getId)
                        .collect(Collectors.toList())) {
                    balance += userBalances.getOrDefault(otherUserId, 0.0);
                }
            }
            netBalance.put(user.getId(), balance);
        }

        // Separate creditors and debtors
        List<Map.Entry<String, Double>> creditors = new ArrayList<>();
        List<Map.Entry<String, Double>> debtors = new ArrayList<>();

        for (Map.Entry<String, Double> entry : netBalance.entrySet()) {
            double amount = entry.getValue();
            if (amount > 0.01) {
                debtors.add(entry); // Positive balance means they owe money
            } else if (amount < -0.01) {
                creditors.add(entry); // Negative balance means they are owed money
            }
        }

        // Sort in descending order of absolute value
        creditors.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
        debtors.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));

        // Generate simplified transactions
        List<Transaction> simplifiedTransactions = new ArrayList<>();
        int i = 0, j = 0;

        while (i < debtors.size() && j < creditors.size()) {
            String debtorId = debtors.get(i).getKey();
            String creditorId = creditors.get(j).getKey();
            double debtAmount = debtors.get(i).getValue();
            double creditAmount = Math.abs(creditors.get(j).getValue());

            double settleAmount = Math.min(debtAmount, creditAmount);

            User debtor = users.get(debtorId);
            User creditor = users.get(creditorId);

            simplifiedTransactions.add(
                new Transaction(debtor, creditor, settleAmount, "Simplified settlement")
            );

            debtors.get(i).setValue(debtAmount - settleAmount);
            creditors.get(j).setValue(-(creditAmount - settleAmount));

            if (Math.abs(debtors.get(i).getValue()) < 0.01) {
                i++;
            }
            if (Math.abs(creditors.get(j).getValue()) < 0.01) {
                j++;
            }
        }

        return simplifiedTransactions;
    }

    // Getters
    public Map<String, User> getUsers() {
        return new HashMap<>(users);
    }

    public Map<String, Group> getGroups() {
        return new HashMap<>(groups);
    }

    public List<Expense> getExpenses() {
        return new ArrayList<>(expenses);
    }

    public List<Transaction> getTransactions() {
        return new ArrayList<>(transactions);
    }

    public User getUser(String userId) {
        return users.get(userId);
    }

    public Group getGroup(String groupId) {
        return groups.get(groupId);
    }
}
