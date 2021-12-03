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
        while (number< PRINT_NUMBER_LIMIT){
            synchronized (lock){
                while (number %3 !=reminder){
                    try {
                        lock.wait();
                    }catch (InterruptedException ex){
                        ex.printStackTrace();
                    }
                }
                System.out.println(Thread.currentThread().getName() + " "+ number);
                number++;
                lock.notifyAll();
            }
        }
    }
}
