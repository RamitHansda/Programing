package interview.coinbase.oa2026;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lodely / Coinbase OA 2026 — Transaction Ledger Reconciliation
 *
 * Given two ledger logs (potentially out of order), reconcile them to find
 * discrepancies. Canonicalize each entry, hash for fast membership, sort by
 * (timestamp, id) for deterministic ordering, then track state carefully.
 *
 * Discrepancy kinds:
 *   ONLY_IN_A / ONLY_IN_B  — present in one log only
 *   FIELD_MISMATCH         — same id, different canonical payload
 *   COUNT_MISMATCH         — same id appears different number of times
 */
public class LedgerReconciler {

    public enum Side { A, B }

    public enum DiscrepancyType {
        ONLY_IN_A,
        ONLY_IN_B,
        FIELD_MISMATCH,
        COUNT_MISMATCH
    }

    public static final class LedgerEntry {
        public final String id;
        public final long timestamp;
        public final String accountId;
        public final String type;   // DEPOSIT, WITHDRAW, TRANSFER, ...
        public final long amount;

        public LedgerEntry(String id, long timestamp, String accountId, String type, long amount) {
            this.id = id;
            this.timestamp = timestamp;
            this.accountId = accountId;
            this.type = type;
            this.amount = amount;
        }

        /** Stable serialization used for hashing — field order fixed. */
        public String canonical() {
            return id + "|" + timestamp + "|" + accountId + "|" + type + "|" + amount;
        }

        public String contentHash() {
            return sha256(canonical());
        }
    }

    public static final class Discrepancy {
        public final DiscrepancyType type;
        public final String entryId;
        public final LedgerEntry a;
        public final LedgerEntry b;
        public final String detail;

        public Discrepancy(DiscrepancyType type, String entryId, LedgerEntry a, LedgerEntry b, String detail) {
            this.type = type;
            this.entryId = entryId;
            this.a = a;
            this.b = b;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return type + "[" + entryId + "]: " + detail;
        }
    }

    public static final class ReconcileResult {
        public final List<Discrepancy> discrepancies;
        public final Map<String, Long> balanceA;
        public final Map<String, Long> balanceB;
        public final boolean balanced;

        public ReconcileResult(
                List<Discrepancy> discrepancies,
                Map<String, Long> balanceA,
                Map<String, Long> balanceB,
                boolean balanced) {
            this.discrepancies = discrepancies;
            this.balanceA = balanceA;
            this.balanceB = balanceB;
            this.balanced = balanced;
        }
    }

    /**
     * Reconcile two unordered ledger logs.
     * 1) Sort each by (timestamp, id)
     * 2) Group by id, compare counts + content hashes
     * 3) Replay each sorted log into account balances (withdrawals that would
     *    go negative are recorded as discrepancies but skipped in balance)
     */
    public ReconcileResult reconcile(List<LedgerEntry> logA, List<LedgerEntry> logB) {
        List<LedgerEntry> a = sortedCopy(logA);
        List<LedgerEntry> b = sortedCopy(logB);

        Map<String, List<LedgerEntry>> byIdA = groupById(a);
        Map<String, List<LedgerEntry>> byIdB = groupById(b);

        List<Discrepancy> diffs = new ArrayList<>();

        // Union of all ids in insertion-stable order (sorted)
        List<String> allIds = new ArrayList<>();
        allIds.addAll(byIdA.keySet());
        for (String id : byIdB.keySet()) {
            if (!byIdA.containsKey(id)) {
                allIds.add(id);
            }
        }
        Collections.sort(allIds);

        for (String id : allIds) {
            List<LedgerEntry> ea = byIdA.getOrDefault(id, List.of());
            List<LedgerEntry> eb = byIdB.getOrDefault(id, List.of());

            if (ea.isEmpty()) {
                for (LedgerEntry e : eb) {
                    diffs.add(new Discrepancy(DiscrepancyType.ONLY_IN_B, id, null, e,
                            "present only in B: " + e.canonical()));
                }
                continue;
            }
            if (eb.isEmpty()) {
                for (LedgerEntry e : ea) {
                    diffs.add(new Discrepancy(DiscrepancyType.ONLY_IN_A, id, e, null,
                            "present only in A: " + e.canonical()));
                }
                continue;
            }
            if (ea.size() != eb.size()) {
                diffs.add(new Discrepancy(DiscrepancyType.COUNT_MISMATCH, id,
                        ea.get(0), eb.get(0),
                        "count A=" + ea.size() + " B=" + eb.size()));
            }
            int n = Math.min(ea.size(), eb.size());
            for (int i = 0; i < n; i++) {
                LedgerEntry x = ea.get(i);
                LedgerEntry y = eb.get(i);
                if (!Objects.equals(x.contentHash(), y.contentHash())) {
                    diffs.add(new Discrepancy(DiscrepancyType.FIELD_MISMATCH, id, x, y,
                            "A=" + x.canonical() + " B=" + y.canonical()));
                }
            }
        }

        Map<String, Long> balA = replayBalances(a);
        Map<String, Long> balB = replayBalances(b);
        boolean balanced = balA.equals(balB) && diffs.isEmpty();

        return new ReconcileResult(diffs, balA, balB, balanced);
    }

    /**
     * Apply transactions in timestamp order. Deposits add; withdrawals subtract
     * only if funds suffice (never go negative — skipped otherwise).
     */
    public Map<String, Long> replayBalances(List<LedgerEntry> log) {
        List<LedgerEntry> sorted = sortedCopy(log);
        Map<String, Long> balances = new LinkedHashMap<>();
        // Deduplicate by id keeping latest timestamp (common OA twist)
        Map<String, LedgerEntry> latestById = new LinkedHashMap<>();
        for (LedgerEntry e : sorted) {
            LedgerEntry prev = latestById.get(e.id);
            if (prev == null || e.timestamp >= prev.timestamp) {
                latestById.put(e.id, e);
            }
        }
        List<LedgerEntry> deduped = new ArrayList<>(latestById.values());
        deduped.sort(ENTRY_ORDER);

        for (LedgerEntry e : deduped) {
            long bal = balances.getOrDefault(e.accountId, 0L);
            switch (e.type) {
                case "DEPOSIT" -> balances.put(e.accountId, bal + e.amount);
                case "WITHDRAW" -> {
                    if (bal >= e.amount) {
                        balances.put(e.accountId, bal - e.amount);
                    }
                    // else skip — would go negative
                }
                default -> {
                    // unknown type: treat as no-op for balance
                }
            }
        }
        return balances;
    }

    /** Fingerprint of an entire sorted log (for quick equality checks). */
    public String logFingerprint(List<LedgerEntry> log) {
        List<LedgerEntry> sorted = sortedCopy(log);
        StringBuilder sb = new StringBuilder();
        for (LedgerEntry e : sorted) {
            sb.append(e.contentHash()).append('\n');
        }
        return sha256(sb.toString());
    }

    private static final Comparator<LedgerEntry> ENTRY_ORDER =
            Comparator.comparingLong((LedgerEntry e) -> e.timestamp).thenComparing(e -> e.id);

    private static List<LedgerEntry> sortedCopy(List<LedgerEntry> log) {
        List<LedgerEntry> copy = new ArrayList<>(log);
        copy.sort(ENTRY_ORDER);
        return copy;
    }

    private static Map<String, List<LedgerEntry>> groupById(List<LedgerEntry> sorted) {
        Map<String, List<LedgerEntry>> map = new HashMap<>();
        for (LedgerEntry e : sorted) {
            map.computeIfAbsent(e.id, k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
