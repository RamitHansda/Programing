package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded in-memory queue: producers block when full (backpressure).
 */
final class InMemoryBoundedQueue<T> implements BoundedQueue<T> {

    private final String name;
    private final int capacity;
    private final BlockingQueue<Message<T>> queue;

    InMemoryBoundedQueue(String name, int capacity) {
        this.name = name;
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public void enqueue(Message<T> message) throws InterruptedException {
        queue.put(message);
    }

    @Override
    public boolean tryEnqueue(Message<T> message) {
        return queue.offer(message);
    }

    @Override
    public Message<T> dequeue() throws InterruptedException {
        return queue.take();
    }

    @Override
    public Optional<Message<T>> dequeue(long timeout, TimeUnit unit) throws InterruptedException {
        return Optional.ofNullable(queue.poll(timeout, unit));
    }

    @Override
    public int size() {
        return queue.size();
    }
}
