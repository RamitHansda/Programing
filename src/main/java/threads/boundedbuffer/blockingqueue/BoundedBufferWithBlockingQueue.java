package threads.boundedbuffer.blockingqueue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded buffer implemented by delegating to {@link BlockingQueue}.
 * No explicit synchronization is needed—the queue handles blocking and thread safety.
 *
 * Use this in real code when you just need a bounded producer-consumer buffer.
 * Use hand-written wait/notify or locks when the goal is to learn/implement the sync yourself.
 */
public class BoundedBufferWithBlockingQueue<T> {

    private final BlockingQueue<T> queue;

    public BoundedBufferWithBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Blocks until space is available. */
    public void put(T item) throws InterruptedException {
        queue.put(item);
    }

    /** Blocks until an item is available. */
    public T take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public int getCapacity() {
        return queue.remainingCapacity() + queue.size();
    }
}
