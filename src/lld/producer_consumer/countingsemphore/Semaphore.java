package lld.producer_consumer.countingsemphore;

public interface Semaphore {
    void acquire() throws InterruptedException;
    void release() throws InterruptedException;
}
