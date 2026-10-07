package interview.coinbase.bankingsystem;

/**
 * Quick manual walkthrough of the Coinbase CodeSignal Banking System.
 *
 * Run: mvn -q exec:java -Dexec.mainClass=interview.coinbase.bankingsystem.BankSystemDemo
 */
public class BankSystemDemo {

    public static void main(String[] args) {
        BankSystem bank = new BankSystem();

        System.out.println("=== Level 1 ===");
        System.out.println("create acc1: " + bank.createAccount(1, "acc1"));
        System.out.println("create acc2: " + bank.createAccount(2, "acc2"));
        System.out.println("deposit 1000: " + bank.deposit(3, "acc1", 1000));
        System.out.println("transfer 300: " + bank.transfer(4, "acc1", "acc2", 300));
        System.out.println("acc1=" + bank.getCurrentBalance("acc1") + " acc2=" + bank.getCurrentBalance("acc2"));

        System.out.println("\n=== Level 2 ===");
        bank.transfer(5, "acc1", "acc2", 200);
        System.out.println("topSpenders: " + bank.topSpenders(6, 2));

        System.out.println("\n=== Level 3 ===");
        String pid = bank.schedulePayment(7, "acc1", 100, 10); // due at 17
        System.out.println("scheduled: " + pid);
        System.out.println("cancel early: " + bank.cancelPayment(8, "acc1", pid));

        String pid2 = bank.schedulePayment(9, "acc1", 50, 5); // due at 14
        bank.deposit(14, "acc2", 1); // triggers execution of pid2
        System.out.println("after due payment acc1=" + bank.getCurrentBalance("acc1"));
        System.out.println("topSpenders: " + bank.topSpenders(15, 2));

        System.out.println("\n=== Level 4 ===");
        bank.createAccount(16, "acc3");
        bank.deposit(17, "acc3", 400);
        System.out.println("merge acc3 -> acc1: " + bank.mergeAccounts(18, "acc1", "acc3"));
        System.out.println("acc1=" + bank.getCurrentBalance("acc1"));
        System.out.println("acc3 history @17: " + bank.getBalance(19, "acc3", 17));
        System.out.println("acc3 history @18: " + bank.getBalance(20, "acc3", 18));
    }
}
