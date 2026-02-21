package threads;

public class PrintSequenceRunnable implements Runnable{
    public int PRINT_NUMBER_LIMIT = 10;
    static int  number=1;
    int reminder;
    static Object lock = new Object();

    public PrintSequenceRunnable(int reminder) {
        this.reminder = reminder;
    }

    @Override
    public void run() {
        while (true) {
            synchronized (lock) {

                if (number > PRINT_NUMBER_LIMIT)
                    return;

                while (number % 3 != reminder) {
                    try { lock.wait(); } catch (InterruptedException ignored) {}
                    if (number > PRINT_NUMBER_LIMIT)
                        return;
                }

                System.out.println(Thread.currentThread().getName() + " " + number++);
                lock.notifyAll();
            }
        }
    }
}
