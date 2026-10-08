package interview.coinbase.oa2026;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankWithFeesTest {

    @Test
    void depositWithdrawNoNegative() {
        BankWithFees bank = new BankWithFees();
        assertTrue(bank.createAccount(1, "a"));
        assertEquals(1000L, bank.deposit(2, "a", 1000));
        assertEquals(700L, bank.withdraw(3, "a", 300));
        assertNull(bank.withdraw(4, "a", 800)); // would go negative
        assertEquals(700L, bank.getBalance(5, "a"));
    }

    @Test
    void transferChargesOnePercentFee() {
        BankWithFees bank = new BankWithFees();
        bank.createAccount(1, "a");
        bank.createAccount(2, "b");
        bank.deposit(3, "a", 1000);
        // transfer 100 → fee 1 → debit 101
        assertEquals(899L, bank.transfer(4, "a", "b", 100));
        assertEquals(100L, bank.getBalance(5, "b"));
    }

    @Test
    void transferFailsInsufficientForFee() {
        BankWithFees bank = new BankWithFees();
        bank.createAccount(1, "a");
        bank.createAccount(2, "b");
        bank.deposit(3, "a", 100);
        // amount 100 + fee 1 = 101 > 100
        assertNull(bank.transfer(4, "a", "b", 100));
        assertEquals(100L, bank.getBalance(5, "a"));
    }

    @Test
    void listTransactionsWithFilter() {
        BankWithFees bank = new BankWithFees();
        bank.createAccount(1, "a");
        bank.deposit(2, "a", 500);
        bank.withdraw(3, "a", 100);
        bank.deposit(4, "a", 50);

        List<BankWithFees.Txn> all = bank.listTransactions("a", 10, null);
        assertEquals(3, all.size());

        List<BankWithFees.Txn> deposits = bank.listTransactions("a", 10, "DEPOSIT");
        assertEquals(2, deposits.size());
        assertEquals("DEPOSIT", deposits.get(0).type);

        List<BankWithFees.Txn> lastOne = bank.listTransactions("a", 1, null);
        assertEquals(1, lastOne.size());
        assertEquals(50L, lastOne.get(0).amount);
    }

    @Test
    void duplicateCreateFails() {
        BankWithFees bank = new BankWithFees();
        assertTrue(bank.createAccount(1, "a"));
        assertFalse(bank.createAccount(2, "a"));
    }
}
