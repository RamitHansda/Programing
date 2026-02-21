package threads;
import java.util.concurrent.Semaphore;

public class ThreeThreadPrinter {
    static int number = 1;
    static final int LIMIT = 10;

    static Semaphore s1 = new Semaphore(1);  // T1 starts first
    static Semaphore s2 = new Semaphore(0);
    static Semaphore s3 = new Semaphore(0);

    public static void main(String[] args) {
        new Thread(() -> print(s1, s2), "T1").start();
        new Thread(() -> print(s2, s3), "T2").start();
        new Thread(() -> print(s3, s1), "T3").start();
    }

    static void print(Semaphore current, Semaphore next) {
        while (number <= LIMIT) {
            try {
                current.acquire();
                if (number <= LIMIT) {
                    System.out.println(Thread.currentThread().getName() + " : " + number);
                    number++;
                }
                next.release();
            } catch (Exception ignored) {}
        }
    }
}
