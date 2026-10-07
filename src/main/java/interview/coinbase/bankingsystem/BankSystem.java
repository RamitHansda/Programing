package interview.coinbase.bankingsystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Coinbase CodeSignal OA – In-memory Banking System (Levels 1–4).
 *
 * Level 1: createAccount, deposit, transfer
 * Level 2: topSpenders
 * Level 3: schedulePayment, cancelPayment (process due payments first)
 * Level 4: mergeAccounts, getBalance (historical)
 */
public class BankSystem {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Long> outgoing = new HashMap<>();

    private final Map<String, Long> createdAt = new HashMap<>();
    private final Map<String, Long> mergedAt = new HashMap<>();
    /** accountId -> chronological (timestamp, balance) snapshots */
    private final Map<String, List<BalanceSnapshot>> balanceHistory = new HashMap<>();

    private final PriorityQueue<ScheduledPayment> scheduled =
            new PriorityQueue<>((a, b) -> {
                int cmp = Long.compare(a.dueTime, b.dueTime);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(a.order, b.order);
            });

    private final Set<String> cancelled = new HashSet<>();
    private final Set<String> executed = new HashSet<>();
    private int paymentCounter = 0;

    // -------------------------------------------------------------------------
    // Level 1
    // -------------------------------------------------------------------------

    public boolean createAccount(long timestamp, String accountId) {
        processScheduled(timestamp);
        if (balances.containsKey(accountId)) {
            return false;
        }
        balances.put(accountId, 0L);
        outgoing.put(accountId, 0L);
        createdAt.put(accountId, timestamp);
        // Clear merge tombstone if this id is reused after a prior merge
        mergedAt.remove(accountId);
        recordBalance(accountId, timestamp);
        return true;
    }

    public boolean deposit(long timestamp, String accountId, long amount) {
        processScheduled(timestamp);
        if (!balances.containsKey(accountId) || amount <= 0) {
            return false;
        }
        balances.put(accountId, balances.get(accountId) + amount);
        recordBalance(accountId, timestamp);
        return true;
    }

    public boolean transfer(long timestamp, String sourceId, String targetId, long amount) {
        processScheduled(timestamp);
        if (!balances.containsKey(sourceId)
                || !balances.containsKey(targetId)
                || sourceId.equals(targetId)
                || amount <= 0
                || balances.get(sourceId) < amount) {
            return false;
        }
        balances.put(sourceId, balances.get(sourceId) - amount);
        balances.put(targetId, balances.get(targetId) + amount);
        outgoing.put(sourceId, outgoing.get(sourceId) + amount);
        recordBalance(sourceId, timestamp);
        recordBalance(targetId, timestamp);
        return true;
    }

    // -------------------------------------------------------------------------
    // Level 2
    // -------------------------------------------------------------------------

    /**
     * Top N accounts by total outgoing transfers (successful transfers + executed scheduled payments).
     * Format: "accountId(spent)". Sorted by spent desc, then accountId asc.
     */
    public List<String> topSpenders(long timestamp, int n) {
        processScheduled(timestamp);
        List<Map.Entry<String, Long>> spenders = new ArrayList<>();
        for (Map.Entry<String, Long> e : outgoing.entrySet()) {
            if (e.getValue() > 0 && balances.containsKey(e.getKey())) {
                spenders.add(e);
            }
        }
        spenders.sort((a, b) -> {
            int cmp = Long.compare(b.getValue(), a.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return a.getKey().compareTo(b.getKey());
        });
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(n, spenders.size()); i++) {
            Map.Entry<String, Long> e = spenders.get(i);
            result.add(e.getKey() + "(" + e.getValue() + ")");
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Level 3
    // -------------------------------------------------------------------------

    /**
     * Schedule a payment due at timestamp + delay. Returns "payment1", "payment2", ...
     * or "" if the account does not exist.
     */
    public String schedulePayment(long timestamp, String accountId, long amount, long delay) {
        processScheduled(timestamp);
        if (!balances.containsKey(accountId) || amount <= 0 || delay < 0) {
            return "";
        }
        paymentCounter++;
        String paymentId = "payment" + paymentCounter;
        long dueTime = timestamp + delay;
        scheduled.offer(new ScheduledPayment(dueTime, paymentCounter, paymentId, accountId, amount));
        return paymentId;
    }

    /**
     * Cancel a pending payment owned by accountId.
     * Fails if already executed/cancelled, wrong owner, or already due (processScheduled runs first).
     */
    public boolean cancelPayment(long timestamp, String accountId, String paymentId) {
        processScheduled(timestamp);
        if (cancelled.contains(paymentId) || executed.contains(paymentId)) {
            return false;
        }
        for (ScheduledPayment p : scheduled) {
            if (p.paymentId.equals(paymentId) && p.accountId.equals(accountId)) {
                cancelled.add(paymentId);
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Level 4
    // -------------------------------------------------------------------------

    /**
     * Merge accountId2 into accountId1: move balance, outgoing, and pending payments; delete id2.
     */
    public boolean mergeAccounts(long timestamp, String accountId1, String accountId2) {
        processScheduled(timestamp);
        if (!balances.containsKey(accountId1)
                || !balances.containsKey(accountId2)
                || accountId1.equals(accountId2)) {
            return false;
        }

        balances.put(accountId1, balances.get(accountId1) + balances.get(accountId2));
        outgoing.put(accountId1, outgoing.get(accountId1) + outgoing.getOrDefault(accountId2, 0L));

        recordBalance(accountId2, timestamp);
        mergedAt.put(accountId2, timestamp);

        // Rewire pending scheduled payments from id2 -> id1
        List<ScheduledPayment> rewired = new ArrayList<>();
        while (!scheduled.isEmpty()) {
            ScheduledPayment p = scheduled.poll();
            if (p.accountId.equals(accountId2) && !cancelled.contains(p.paymentId) && !executed.contains(p.paymentId)) {
                rewired.add(new ScheduledPayment(p.dueTime, p.order, p.paymentId, accountId1, p.amount));
            } else {
                rewired.add(p);
            }
        }
        scheduled.addAll(rewired);

        balances.remove(accountId2);
        outgoing.remove(accountId2);
        recordBalance(accountId1, timestamp);
        return true;
    }

    /**
     * Balance of accountId at timeAt.
     * Returns -1 if the account did not exist at that time (or was already merged away).
     */
    public long getBalance(long timestamp, String accountId, long timeAt) {
        processScheduled(timestamp);

        List<BalanceSnapshot> history = balanceHistory.get(accountId);
        if (history == null || history.isEmpty()) {
            return -1;
        }

        Long created = createdAt.get(accountId);
        if (created != null && created > timeAt) {
            return -1;
        }

        Long merged = mergedAt.get(accountId);
        if (merged != null && merged <= timeAt) {
            return -1;
        }

        // Latest snapshot with time <= timeAt
        int idx = Collections.binarySearch(
                history,
                new BalanceSnapshot(timeAt, 0),
                (a, b) -> Long.compare(a.time, b.time));
        if (idx < 0) {
            idx = -idx - 2; // insertion point - 1
        }
        if (idx < 0) {
            return -1;
        }
        return history.get(idx).balance;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Execute all pending payments with dueTime <= timestamp, oldest first.
     * Must run before every public operation.
     */
    private void processScheduled(long timestamp) {
        while (!scheduled.isEmpty() && scheduled.peek().dueTime <= timestamp) {
            ScheduledPayment p = scheduled.poll();
            if (cancelled.contains(p.paymentId)) {
                continue;
            }
            executed.add(p.paymentId);
            if (!balances.containsKey(p.accountId)) {
                continue;
            }
            if (balances.get(p.accountId) >= p.amount) {
                balances.put(p.accountId, balances.get(p.accountId) - p.amount);
                outgoing.put(p.accountId, outgoing.getOrDefault(p.accountId, 0L) + p.amount);
                recordBalance(p.accountId, p.dueTime);
            }
            // else: insufficient funds — skip permanently
        }
    }

    private void recordBalance(String accountId, long timestamp) {
        if (!balances.containsKey(accountId) && !balanceHistory.containsKey(accountId)) {
            return;
        }
        long bal = balances.getOrDefault(accountId, 0L);
        // For deleted (merged) accounts we still append a final snapshot before remove
        balanceHistory
                .computeIfAbsent(accountId, k -> new ArrayList<>())
                .add(new BalanceSnapshot(timestamp, bal));
    }

    /** Convenience: current balance, or -1 if missing. */
    public long getCurrentBalance(String accountId) {
        return balances.getOrDefault(accountId, -1L);
    }

    private static final class ScheduledPayment {
        final long dueTime;
        final int order;
        final String paymentId;
        final String accountId;
        final long amount;

        ScheduledPayment(long dueTime, int order, String paymentId, String accountId, long amount) {
            this.dueTime = dueTime;
            this.order = order;
            this.paymentId = paymentId;
            this.accountId = accountId;
            this.amount = amount;
        }
    }

    private static final class BalanceSnapshot {
        final long time;
        final long balance;

        BalanceSnapshot(long time, long balance) {
            this.time = time;
            this.balance = balance;
        }
    }
}
