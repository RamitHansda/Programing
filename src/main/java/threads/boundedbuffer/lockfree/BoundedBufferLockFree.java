package threads.boundedbuffer.lockfree;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class BoundedBufferLockFree<T> {
    private final AtomicReferenceArray<T> buffer;
    private final AtomicInteger head = new AtomicInteger(0);
    private final AtomicInteger tail = new AtomicInteger(0);
    private final int capacity;

    public BoundedBufferLockFree(int capacity){
        this.buffer = new AtomicReferenceArray<>(capacity);
        this.capacity = capacity;
    }

    public boolean enqueue(T item){
        if (item == null) throw new NullPointerException();
        while (true){
            int currentTail = tail.get();
            int currentHead = head.get();
            if (currentTail - currentHead >= capacity) {
                return false;
            }

            if (tail.compareAndSet(currentTail, currentTail + 1)) {
                int index = currentTail % capacity;

                // Spin until slot is free
                while (!buffer.compareAndSet(index, null, item)) {
                    // another consumer not done yet
                }
                return true;
            }
        }
    }


    public T dequeue() {
        while (true) {
            int currHead = head.get();
            int currTail = tail.get();

            // Queue empty check
            if (currHead >= currTail) {
                return null;
            }

            // Try to reserve a slot
            if (head.compareAndSet(currHead, currHead + 1)) {
                int index = currHead % capacity;

                // Spin until producer publishes value
                T value;
                while ((value = buffer.get(index)) == null) {
                    // wait until item is visible
                }

                buffer.set(index, null); // clear slot
                return value;
            }
        }
    }
}
