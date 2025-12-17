package lld.producer_consumer.countingsemphore;


public class BlockingQueueWithSemaphore<T> {

    private final T[] array;
    private final int capacity;

    private int head = 0;
    private int tail = 0;

    private final CountingSemaphore semLock = new CountingSemaphore(1, 1);
    private final CountingSemaphore semProducer;
    private final CountingSemaphore semConsumer;

    @SuppressWarnings("unchecked")
    public BlockingQueueWithSemaphore(int capacity) {
        this.capacity = capacity;
        this.array = (T[]) new Object[capacity];
        this.semProducer = new CountingSemaphore(capacity, capacity); // all space available
        this.semConsumer = new CountingSemaphore(capacity, 0);        // no items available
    }

    public T dequeue() throws InterruptedException {

        semConsumer.acquire();      // wait if no items
        semLock.acquire();          // critical section

        T item = array[head];
        array[head] = null;
        head = (head + 1) % capacity;

        semLock.release();
        semProducer.release();      // signal space available

        return item;
    }

    public void enqueue(T item) throws InterruptedException {

        semProducer.acquire();      // wait if no space
        semLock.acquire();          // critical section

        array[tail] = item;
        tail = (tail + 1) % capacity;

        semLock.release();
        semConsumer.release();      // signal item available
    }
}