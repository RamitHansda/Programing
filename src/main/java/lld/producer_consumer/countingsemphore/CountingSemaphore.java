package lld.producer_consumer.countingsemphore;

public class CountingSemaphore implements Semaphore {

    private int permits;
    private final int maxPermits;

    public CountingSemaphore(int maxPermits, int initialPermits) {
        this.maxPermits = maxPermits;
        this.permits = initialPermits;
    }

    public synchronized void acquire() throws InterruptedException {
        while (permits == 0) {
            wait();
        }
        permits--;
        notifyAll();
    }

    public synchronized void release() {
        if (permits < maxPermits) {
            permits++;
            notifyAll();
        }
    }
}
