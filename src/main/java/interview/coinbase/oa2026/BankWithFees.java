package interview.coinbase.oa2026;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Lodely / Coinbase OA 2026 — In-Memory Banking System
 *
 * Deposits, withdrawals, transfers with fees, and balance queries.
 * Commands arrive in strict timestamp order. HashMap for account state;
 * priority queue for deferred fee / interest events when needed.
 *
 * Transfer fee: 1% of amount (integer math, floored), charged to sender
 * in addition to the transfer amount.
 */
public class BankWithFees {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, List<Txn>> history = new HashMap<>();

    /** Deferred events (e.g. scheduled fee refunds) ordered by due time. */
    private final PriorityQueue<DeferredEvent> deferred =
            new PriorityQueue<>((a, b) -> {
                int c = Long.compare(a.dueTime, b.dueTime);
                return c != 0 ? c : Integer.compare(a.order, b.order);
            });
    private int eventOrder = 0;

    public boolean createAccount(long timestamp, String accountId) {
        processDeferred(timestamp);
        if (balances.containsKey(accountId)) {
            return false;
        }
        balances.put(accountId, 0L);
        history.put(accountId, new ArrayList<>());
        return true;
    }

    /**
     * Deposit amount. Returns new balance, or null if account missing / amount &lt;= 0.
     */
    public Long deposit(long timestamp, String accountId, long amount) {
        processDeferred(timestamp);
        if (!balances.containsKey(accountId) || amount <= 0) {
            return null;
        }
        long bal = balances.get(accountId) + amount;
        balances.put(accountId, bal);
        history.get(accountId).add(new Txn(timestamp, "DEPOSIT", amount, bal));
        return bal;
    }

    /**
     * Withdraw amount. Rejects if it would make balance negative.
     * Returns new balance, or null on failure.
     */
    public Long withdraw(long timestamp, String accountId, long amount) {
        processDeferred(timestamp);
        if (!balances.containsKey(accountId) || amount <= 0) {
            return null;
        }
        long bal = balances.get(accountId);
        if (bal < amount) {
            return null; // never allow negative
        }
        bal -= amount;
        balances.put(accountId, bal);
        history.get(accountId).add(new Txn(timestamp, "WITHDRAW", amount, bal));
        return bal;
    }

    /**
     * Transfer amount from source to target.
     * Fee = amount / 100 (1%, floored). Sender pays amount + fee.
     * Returns source new balance, or null on failure.
     */
    public Long transfer(long timestamp, String sourceId, String targetId, long amount) {
        processDeferred(timestamp);
        if (!balances.containsKey(sourceId)
                || !balances.containsKey(targetId)
                || sourceId.equals(targetId)
                || amount <= 0) {
            return null;
        }
        long fee = amount / 100;
        long totalDebit = amount + fee;
        if (balances.get(sourceId) < totalDebit) {
            return null;
        }
        long srcBal = balances.get(sourceId) - totalDebit;
        long tgtBal = balances.get(targetId) + amount;
        balances.put(sourceId, srcBal);
        balances.put(targetId, tgtBal);
        history.get(sourceId).add(new Txn(timestamp, "TRANSFER_OUT", amount, srcBal));
        if (fee > 0) {
            history.get(sourceId).add(new Txn(timestamp, "FEE", fee, srcBal));
        }
        history.get(targetId).add(new Txn(timestamp, "TRANSFER_IN", amount, tgtBal));
        return srcBal;
    }

    /** Current balance, or null if account does not exist. */
    public Long getBalance(long timestamp, String accountId) {
        processDeferred(timestamp);
        return balances.get(accountId);
    }

    /**
     * Last n transactions for an account (chronological), or null if missing.
     * Filter type: null = all; otherwise match Txn.type.
     */
    public List<Txn> listTransactions(String accountId, int n, String typeFilter) {
        List<Txn> all = history.get(accountId);
        if (all == null) {
            return null;
        }
        List<Txn> filtered = new ArrayList<>();
        for (Txn t : all) {
            if (typeFilter == null || typeFilter.equals(t.type)) {
                filtered.add(t);
            }
        }
        int from = Math.max(0, filtered.size() - Math.max(n, 0));
        return new ArrayList<>(filtered.subList(from, filtered.size()));
    }

    private void processDeferred(long timestamp) {
        while (!deferred.isEmpty() && deferred.peek().dueTime <= timestamp) {
            DeferredEvent e = deferred.poll();
            e.run.accept(this);
        }
    }

    /** Exposed for tests / extensions that schedule deferred work. */
    void schedule(long dueTime, java.util.function.Consumer<BankWithFees> action) {
        deferred.offer(new DeferredEvent(dueTime, eventOrder++, action));
    }

    public static final class Txn {
        public final long timestamp;
        public final String type;
        public final long amount;
        public final long balanceAfter;

        public Txn(long timestamp, String type, long amount, long balanceAfter) {
            this.timestamp = timestamp;
            this.type = type;
            this.amount = amount;
            this.balanceAfter = balanceAfter;
        }

        @Override
        public String toString() {
            return type + "(" + amount + ")->" + balanceAfter + "@" + timestamp;
        }
    }

    private static final class DeferredEvent {
        final long dueTime;
        final int order;
        final java.util.function.Consumer<BankWithFees> run;

        DeferredEvent(long dueTime, int order, java.util.function.Consumer<BankWithFees> run) {
            this.dueTime = dueTime;
            this.order = order;
            this.run = run;
        }
    }
}
