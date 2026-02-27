package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Optional: queue with a fixed capacity for backpressure. Producers block when full.
 * Use via broker config or a dedicated factory if you need bounded queues.
 */
public interface BoundedQueue<T> extends Queue<T> {

    int getCapacity();

    /**
     * Try to enqueue without blocking. Returns false if queue is full.
     */
    boolean tryEnqueue(Message<T> message);
}
