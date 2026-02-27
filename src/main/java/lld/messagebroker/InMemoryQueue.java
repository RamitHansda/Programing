package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory queue: unbounded FIFO; multiple consumers compete for messages (single delivery).
 */
final class InMemoryQueue<T> implements Queue<T> {

    private final String name;
    private final BlockingQueue<Message<T>> queue = new LinkedBlockingQueue<>();

    InMemoryQueue(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void enqueue(Message<T> message) throws InterruptedException {
        queue.put(message);
    }

    @Override
    public Message<T> dequeue() throws InterruptedException {
        return queue.take();
    }

    @Override
    public Optional<Message<T>> dequeue(long timeout, TimeUnit unit) throws InterruptedException {
        Message<T> m = queue.poll(timeout, unit);
        return Optional.ofNullable(m);
    }

    @Override
    public int size() {
        return queue.size();
    }
}
