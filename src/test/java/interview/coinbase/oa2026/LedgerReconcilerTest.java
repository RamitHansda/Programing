package interview.coinbase.oa2026;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static interview.coinbase.oa2026.LedgerReconciler.DiscrepancyType;
import static interview.coinbase.oa2026.LedgerReconciler.LedgerEntry;

class LedgerReconcilerTest {

    private final LedgerReconciler reconciler = new LedgerReconciler();

    @Test
    void matchingLogsReconcileClean() {
        List<LedgerEntry> a = List.of(
                new LedgerEntry("tx1", 100, "acc1", "DEPOSIT", 500),
                new LedgerEntry("tx2", 50, "acc1", "DEPOSIT", 200)  // out of order
        );
        List<LedgerEntry> b = List.of(
                new LedgerEntry("tx2", 50, "acc1", "DEPOSIT", 200),
                new LedgerEntry("tx1", 100, "acc1", "DEPOSIT", 500)
        );

        var result = reconciler.reconcile(a, b);
        assertTrue(result.balanced);
        assertTrue(result.discrepancies.isEmpty());
        assertEquals(700L, result.balanceA.get("acc1"));
        assertEquals(700L, result.balanceB.get("acc1"));
    }

    @Test
    void onlyInOneSideDetected() {
        List<LedgerEntry> a = List.of(
                new LedgerEntry("tx1", 1, "acc1", "DEPOSIT", 100),
                new LedgerEntry("tx2", 2, "acc1", "DEPOSIT", 50)
        );
        List<LedgerEntry> b = List.of(
                new LedgerEntry("tx1", 1, "acc1", "DEPOSIT", 100)
        );

        var result = reconciler.reconcile(a, b);
        assertFalse(result.balanced);
        assertEquals(1, result.discrepancies.size());
        assertEquals(DiscrepancyType.ONLY_IN_A, result.discrepancies.get(0).type);
        assertEquals("tx2", result.discrepancies.get(0).entryId);
    }

    @Test
    void fieldMismatchDetectedByHash() {
        List<LedgerEntry> a = List.of(
                new LedgerEntry("tx1", 1, "acc1", "DEPOSIT", 100)
        );
        List<LedgerEntry> b = List.of(
                new LedgerEntry("tx1", 1, "acc1", "DEPOSIT", 999) // same id, different amount
        );

        var result = reconciler.reconcile(a, b);
        assertFalse(result.balanced);
        assertEquals(DiscrepancyType.FIELD_MISMATCH, result.discrepancies.get(0).type);
    }

    @Test
    void withdrawSkippedWhenWouldGoNegative() {
        List<LedgerEntry> log = List.of(
                new LedgerEntry("w1", 1, "acc1", "WITHDRAW", 50), // no funds — skip
                new LedgerEntry("d1", 2, "acc1", "DEPOSIT", 100),
                new LedgerEntry("w2", 3, "acc1", "WITHDRAW", 40)
        );
        Map<String, Long> bal = reconciler.replayBalances(log);
        assertEquals(60L, bal.get("acc1"));
    }

    @Test
    void outOfOrderReplaySortsByTimestampThenId() {
        List<LedgerEntry> log = new ArrayList<>();
        log.add(new LedgerEntry("b", 10, "acc1", "DEPOSIT", 30));
        log.add(new LedgerEntry("a", 10, "acc1", "DEPOSIT", 20)); // same ts, id 'a' first
        log.add(new LedgerEntry("c", 5, "acc1", "DEPOSIT", 10));

        Map<String, Long> bal = reconciler.replayBalances(log);
        assertEquals(60L, bal.get("acc1"));
    }

    @Test
    void fingerprintEqualForPermutedMatchingLogs() {
        List<LedgerEntry> a = List.of(
                new LedgerEntry("tx1", 1, "x", "DEPOSIT", 1),
                new LedgerEntry("tx2", 2, "x", "DEPOSIT", 2)
        );
        List<LedgerEntry> b = List.of(
                new LedgerEntry("tx2", 2, "x", "DEPOSIT", 2),
                new LedgerEntry("tx1", 1, "x", "DEPOSIT", 1)
        );
        assertEquals(reconciler.logFingerprint(a), reconciler.logFingerprint(b));
    }

    @Test
    void fingerprintDiffersWhenContentDiffers() {
        List<LedgerEntry> a = List.of(new LedgerEntry("tx1", 1, "x", "DEPOSIT", 1));
        List<LedgerEntry> b = List.of(new LedgerEntry("tx1", 1, "x", "DEPOSIT", 2));
        assertFalse(reconciler.logFingerprint(a).equals(reconciler.logFingerprint(b)));
    }
}
