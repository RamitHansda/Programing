package threads.boundedbuffer.waitnotify;

/**
 * Bounded buffer (producer-consumer) implementation using the monitor pattern:
 * synchronized + wait() + notifyAll().
 *
 * - Producers block when the buffer is full.
 * - Consumers block when the buffer is empty.
 * - FIFO order is maintained with a circular array.
 */
public class BoundedBufferWaitNotify<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    public BoundedBufferWaitNotify(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    /**
     * Adds an item to the buffer. Blocks until space is available.
     */
    public synchronized void put(T item) throws InterruptedException {
        while (count == capacity) {
            wait();  // buffer full: release monitor and wait
        }
        buffer[tail] = item;
        tail = (tail + 1) % capacity;
        count++;
        notifyAll();  // wake any waiting consumer (or producer when we later add "full" logic)
    }

    /**
     * Removes and returns an item from the buffer. Blocks until an item is available.
     */
    @SuppressWarnings("unchecked")
    public synchronized T take() throws InterruptedException {
        while (count == 0) {
            wait();  // buffer empty: release monitor and wait
        }
        T item = (T) buffer[head];
        buffer[head] = null;
        head = (head + 1) % capacity;
        count--;
        notifyAll();  // wake any waiting producer
        return item;
    }

    public synchronized int size() {
        return count;
    }

    public int getCapacity() {
        return capacity;
    }
}
