package interview.coinbase.bankingsystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankSystemTest {

    @Test
    void level1_createDepositTransfer() {
        BankSystem bank = new BankSystem();
        assertTrue(bank.createAccount(1, "acc1"));
        assertTrue(bank.createAccount(2, "acc2"));
        assertFalse(bank.createAccount(3, "acc1"));

        assertTrue(bank.deposit(4, "acc1", 1000));
        assertFalse(bank.deposit(5, "acc3", 500));

        assertTrue(bank.transfer(6, "acc1", "acc2", 300));
        assertEquals(700, bank.getCurrentBalance("acc1"));
        assertEquals(300, bank.getCurrentBalance("acc2"));

        assertFalse(bank.transfer(7, "acc1", "acc2", 800));
        assertFalse(bank.transfer(8, "acc1", "acc1", 100));
    }

    @Test
    void level2_topSpenders() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.createAccount(2, "acc2");
        bank.createAccount(3, "acc3");
        bank.deposit(4, "acc1", 2000);
        bank.deposit(5, "acc2", 1000);
        bank.deposit(6, "acc3", 500);

        bank.transfer(7, "acc1", "acc2", 500);
        bank.transfer(8, "acc2", "acc3", 300);
        bank.transfer(9, "acc1", "acc3", 200);

        List<String> top = bank.topSpenders(10, 2);
        assertEquals(List.of("acc1(700)", "acc2(300)"), top);
    }

    @Test
    void level3_scheduleAndCancel() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.deposit(2, "acc1", 1000);

        String pid = bank.schedulePayment(3, "acc1", 500, 100);
        assertEquals("payment1", pid);
        assertTrue(bank.cancelPayment(50, "acc1", pid));

        // At due time 103 nothing should debit
        bank.deposit(103, "acc1", 0); // still triggers processScheduled
        // deposit with 0 fails; use a no-op that still advances time via another op
        bank.createAccount(104, "acc2");
        assertEquals(1000, bank.getCurrentBalance("acc1"));
    }

    @Test
    void level3_paymentExecutesBeforeCancelAtDueTime() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.deposit(2, "acc1", 1000);

        String pid = bank.schedulePayment(3, "acc1", 400, 10); // due at 13
        // Cancel at exact due time — payment runs first, cancel fails
        assertFalse(bank.cancelPayment(13, "acc1", pid));
        assertEquals(600, bank.getCurrentBalance("acc1"));
    }

    @Test
    void level3_insufficientFundsSkipsPermanently() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.deposit(2, "acc1", 100);

        bank.schedulePayment(3, "acc1", 200, 5); // due 8
        bank.createAccount(8, "acc2"); // triggers process
        assertEquals(100, bank.getCurrentBalance("acc1"));

        bank.deposit(9, "acc1", 200); // now has 300, but payment already skipped
        assertEquals(300, bank.getCurrentBalance("acc1"));
    }

    @Test
    void level4_mergeAndHistoricalBalance() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.createAccount(2, "acc2");
        bank.deposit(3, "acc1", 1000);
        bank.deposit(4, "acc2", 500);

        assertTrue(bank.mergeAccounts(6, "acc1", "acc2"));
        assertEquals(1500, bank.getCurrentBalance("acc1"));
        assertEquals(-1, bank.getCurrentBalance("acc2"));

        // History before merge
        assertEquals(500, bank.getBalance(7, "acc2", 5));
        // After merge timestamp, acc2 does not exist
        assertEquals(-1, bank.getBalance(8, "acc2", 6));
        // Surviving account at time after merge
        assertEquals(1500, bank.getBalance(9, "acc1", 6));
    }

    @Test
    void level4_pendingPaymentsRewiredOnMerge() {
        BankSystem bank = new BankSystem();
        bank.createAccount(1, "acc1");
        bank.createAccount(2, "acc2");
        bank.deposit(3, "acc2", 500);

        bank.schedulePayment(4, "acc2", 200, 10); // due 14, owned by acc2
        bank.mergeAccounts(5, "acc1", "acc2");

        bank.createAccount(14, "acc3"); // trigger due payment on acc1
        assertEquals(300, bank.getCurrentBalance("acc1"));
    }
}
