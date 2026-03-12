package lld.messagebroker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates the three scenarios that a reliable queue handles:
 *
 *  Scenario 1 — Happy path
 *    Producer enqueues 3 messages. Consumer processes all successfully.
 *    Each message is ack'd and permanently removed.
 *
 *  Scenario 2 — Retry on failure + DLQ
 *    Producer enqueues 2 messages. Consumer intentionally throws on the first
 *    two attempts. On the third attempt it succeeds. The second message fails
 *    every time and ends up in the Dead Letter Queue after maxRetries.
 *
 *  Scenario 3 — Idempotency (duplicate re-delivery)
 *    The same message is enqueued twice (simulating a broker re-delivery).
 *    The consumer processes it once and skips the duplicate.
 */
public class ReliableQueueDemo {

    public static void main(String[] args) throws InterruptedException {
        InMemoryMessageBroker broker = new InMemoryMessageBroker();

        System.out.println("══════════════════════════════════════════════");
        System.out.println(" Scenario 1: Happy path");
        System.out.println("══════════════════════════════════════════════");
        demoHappyPath(broker);

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println(" Scenario 2: Retry + Dead Letter Queue");
        System.out.println("══════════════════════════════════════════════");
        demoRetryAndDlq(broker);

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println(" Scenario 3: Idempotency (duplicate re-delivery)");
        System.out.println("══════════════════════════════════════════════");
        demoIdempotency(broker);

        broker.shutdown();
    }

    // ── Scenario 1 ───────────────────────────────────────────────────────────

    private static void demoHappyPath(InMemoryMessageBroker broker) throws InterruptedException {
        ReliableQueue<String> queue = broker.reliableQueue("happy-queue");
        IdempotencyStore idempotency = new InMemoryIdempotencyStore();

        // Producer
        for (int i = 1; i <= 3; i++) {
            queue.enqueue("order-" + i);
            System.out.println("[Producer] enqueued order-" + i);
        }

        // Consumer
        ReliableConsumer<String> consumer = new ReliableConsumer<>(
                "Consumer-1",
                queue,
                payload -> System.out.println("[Handler ] processing " + payload),
                idempotency
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(consumer);

        Thread.sleep(500);
        consumer.close();
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);
        System.out.println("[Status  ] pending=" + queue.pendingSize() + " inflight=" + queue.inflightSize());
    }

    // ── Scenario 2 ───────────────────────────────────────────────────────────

    private static void demoRetryAndDlq(InMemoryMessageBroker broker) throws InterruptedException {
        // 3 max retries, 1-second visibility timeout (short for demo)
        ReliableQueue<String> queue = broker.reliableQueue("retry-queue", 3, 1_000);
        IdempotencyStore idempotency = new InMemoryIdempotencyStore();

        queue.enqueue("resilient-order");   // will eventually succeed on attempt 3
        queue.enqueue("poison-message");    // will always fail → goes to DLQ
        System.out.println("[Producer] enqueued 2 messages");

        AtomicInteger resilientAttempts = new AtomicInteger(0);

        ReliableConsumer<String> consumer = new ReliableConsumer<>(
                "Consumer-2",
                queue,
                payload -> {
                    if ("resilient-order".equals(payload)) {
                        int attempt = resilientAttempts.incrementAndGet();
                        if (attempt < 3) {
                            throw new RuntimeException("transient error (attempt " + attempt + ")");
                        }
                        System.out.println("[Handler ] resilient-order succeeded on attempt " + attempt);
                    } else {
                        throw new RuntimeException("permanent error — always fails");
                    }
                },
                idempotency
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(consumer);

        Thread.sleep(6_000); // wait for retries + visibility timeout cycles
        consumer.close();
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);

        Queue<String> dlq = queue.deadLetterQueue();
        System.out.println("[DLQ size] " + dlq.size() + " message(s) in dead-letter queue");
        if (dlq.size() > 0) {
            Message<String> dead = dlq.dequeue();
            System.out.println("[DLQ msg ] " + dead.getPayload());
        }
    }

    // ── Scenario 3 ───────────────────────────────────────────────────────────

    private static void demoIdempotency(InMemoryMessageBroker broker) throws InterruptedException {
        ReliableQueue<String> queue = broker.reliableQueue("idempotent-queue");
        IdempotencyStore idempotency = new InMemoryIdempotencyStore();

        // Simulate broker re-delivering the same message by using the same ID
        String fixedId = "msg-fixed-id-001";
        queue.enqueue(new Message<>(fixedId, "duplicate-order", java.util.Collections.emptyMap()));
        queue.enqueue(new Message<>(fixedId, "duplicate-order", java.util.Collections.emptyMap()));
        System.out.println("[Producer] enqueued same message twice (id=" + fixedId + ")");

        AtomicInteger processedCount = new AtomicInteger(0);

        ReliableConsumer<String> consumer = new ReliableConsumer<>(
                "Consumer-3",
                queue,
                payload -> {
                    processedCount.incrementAndGet();
                    System.out.println("[Handler ] processing " + payload);
                },
                idempotency
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(consumer);

        Thread.sleep(500);
        consumer.close();
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);
        System.out.println("[Result  ] handler invoked " + processedCount.get()
                + " time(s) — expected 1 (duplicate was skipped)");
    }
}
