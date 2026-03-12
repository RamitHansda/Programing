package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A consumer that guarantees at-least-once processing with idempotency protection.
 *
 * <h3>Processing flow per message</h3>
 * <pre>
 *  poll()
 *    │
 *    ▼
 *  idempotencyStore.isProcessed(msgId)?
 *    │ yes → ack() and skip          ← duplicate re-delivery, skip safely
 *    │ no
 *    ▼
 *  handler.accept(payload)
 *    │ success
 *    │   markProcessed(msgId)         ← record BEFORE ack so crash here re-processes (safe)
 *    │   ack()                        ← permanently remove from queue
 *    │
 *    │ throws exception
 *    └── nack()                       ← re-enqueue for retry / DLQ
 * </pre>
 *
 * <p>Run this on a dedicated thread (or thread-pool) — it loops until {@link #close()} is called.
 *
 * @param <T> payload type
 */
public final class ReliableConsumer<T> implements Runnable, AutoCloseable {

    private static final long POLL_TIMEOUT_SECONDS = 2L;

    private final String name;
    private final ReliableQueue<T> queue;
    private final Consumer<T> handler;
    private final IdempotencyStore idempotencyStore;
    private volatile boolean running = true;

    public ReliableConsumer(String name,
                            ReliableQueue<T> queue,
                            Consumer<T> handler,
                            IdempotencyStore idempotencyStore) {
        this.name = name;
        this.queue = queue;
        this.handler = handler;
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public void run() {
        System.out.printf("[%s] started%n", name);
        while (running) {
            try {
                Optional<Delivery<T>> opt = queue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (opt.isEmpty()) {
                    continue; // no message yet, loop back
                }
                process(opt.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.printf("[%s] stopped%n", name);
    }

    private void process(Delivery<T> delivery) {
        String msgId = delivery.getMessage().getId();

        // Guard: skip messages already processed (handles re-deliveries after crash)
        if (idempotencyStore.isProcessed(msgId)) {
            System.out.printf("[%s] Skipping duplicate message %s%n", name, msgId);
            delivery.ack(); // still ack so broker removes it from in-flight
            return;
        }

        try {
            handler.accept(delivery.getMessage().getPayload());

            // Mark BEFORE ack: if JVM crashes here, next re-delivery will be skipped by
            // idempotency check rather than double-processed.
            idempotencyStore.markProcessed(msgId);
            delivery.ack();
            System.out.printf("[%s] ACK  message %s%n", name, msgId);

        } catch (Exception e) {
            // Handler threw — do NOT mark as processed; nack for retry
            delivery.nack();
            System.out.printf("[%s] NACK message %s — %s%n", name, msgId, e.getMessage());
        }
    }

    /** Signal the consumer loop to stop after the current poll times out. */
    @Override
    public void close() {
        running = false;
    }
}
