package lld.messagebroker;

import java.util.Optional;
import java.util.concurrent.*;

/**
 * In-memory reliable queue with at-least-once delivery guarantees.
 *
 * <h3>How it works</h3>
 * <pre>
 *  Producer                 Broker                      Consumer
 *    │                        │                             │
 *    │── enqueue(msg) ───────►│  pendingQueue.put(env)      │
 *    │                        │                             │
 *    │                        │◄── poll(timeout) ───────────│
 *    │                        │  pendingQueue → inflightMap │
 *    │                        │── Delivery{msg, ackHandle}─►│
 *    │                        │                             │
 *    │                        │   [consumer processes]      │
 *    │                        │                             │
 *    │                        │◄── delivery.ack() ──────────│  (success)
 *    │                        │  inflightMap.remove(msgId)  │
 *    │                        │                             │
 *    │                        │◄── delivery.nack() ─────────│  (failure)
 *    │                        │  retry < maxRetries?        │
 *    │                        │    yes → pendingQueue again │
 *    │                        │    no  → dlq.enqueue(msg)   │
 *    │                        │                             │
 *    │           [visibility timeout elapsed, no ack/nack]  │
 *    │                        │  redelivery thread fires    │
 *    │                        │  same retry/dlq logic       │
 * </pre>
 *
 * @param <T> payload type
 */
final class InMemoryReliableQueue<T> implements ReliableQueue<T> {

    static final int DEFAULT_MAX_RETRIES = 3;
    static final long DEFAULT_VISIBILITY_TIMEOUT_MS = 30_000L;

    private final String name;
    private final int maxRetries;
    private final long visibilityTimeoutMs;

    /** Messages waiting to be delivered to a consumer. */
    private final BlockingQueue<Envelope<T>> pendingQueue = new LinkedBlockingQueue<>();

    /**
     * Messages currently being processed by a consumer.
     * Keyed by messageId for O(1) removal on ack/nack.
     */
    private final ConcurrentHashMap<String, InflightEntry<T>> inflightMap = new ConcurrentHashMap<>();

    /** Messages that exceeded maxRetries. */
    private final Queue<T> dlq;

    /** Periodically scans inflightMap for timed-out entries and re-enqueues them. */
    private final ScheduledExecutorService redeliveryScheduler;

    // ── Inner types ──────────────────────────────────────────────────────────

    /** Wraps a message with the number of delivery attempts so far. */
    private static final class Envelope<T> {
        final Message<T> message;
        final int attemptNumber; // 0 = first attempt

        Envelope(Message<T> message, int attemptNumber) {
            this.message = message;
            this.attemptNumber = attemptNumber;
        }
    }

    /** Tracks an in-flight message together with the time it was delivered. */
    private static final class InflightEntry<T> {
        final Envelope<T> envelope;
        final long deliveredAtMs;

        InflightEntry(Envelope<T> envelope, long deliveredAtMs) {
            this.envelope = envelope;
            this.deliveredAtMs = deliveredAtMs;
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    InMemoryReliableQueue(String name, int maxRetries, long visibilityTimeoutMs) {
        this.name = name;
        this.maxRetries = maxRetries;
        this.visibilityTimeoutMs = visibilityTimeoutMs;
        this.dlq = new InMemoryQueue<>(name + ".dlq");

        this.redeliveryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reliable-queue-redelivery[" + name + "]");
            t.setDaemon(true);
            return t;
        });
        // Check every second for messages whose visibility window has expired
        redeliveryScheduler.scheduleAtFixedRate(
                this::redeliverTimedOutMessages, 1, 1, TimeUnit.SECONDS);
    }

    // ── ReliableQueue API ────────────────────────────────────────────────────

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void enqueue(Message<T> message) throws InterruptedException {
        pendingQueue.put(new Envelope<>(message, 0));
    }

    /**
     * Poll a message from the queue.
     *
     * The message moves from {@code pendingQueue} → {@code inflightMap}.
     * The caller MUST call {@code ack()} or {@code nack()} on the returned {@link Delivery}.
     */
    @Override
    public Optional<Delivery<T>> poll(long timeout, TimeUnit unit) throws InterruptedException {
        Envelope<T> envelope = pendingQueue.poll(timeout, unit);
        if (envelope == null) {
            return Optional.empty();
        }

        String msgId = envelope.message.getId();
        inflightMap.put(msgId, new InflightEntry<>(envelope, System.currentTimeMillis()));

        return Optional.of(buildDelivery(envelope, msgId));
    }

    @Override
    public Queue<T> deadLetterQueue() {
        return dlq;
    }

    @Override
    public int pendingSize() {
        return pendingQueue.size();
    }

    @Override
    public int inflightSize() {
        return inflightMap.size();
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private Delivery<T> buildDelivery(Envelope<T> envelope, String msgId) {
        return new Delivery<T>() {

            private volatile boolean settled = false;

            @Override
            public Message<T> getMessage() {
                return envelope.message;
            }

            /** Permanently remove from the queue — processing was successful. */
            @Override
            public void ack() {
                if (settled) return;
                settled = true;
                inflightMap.remove(msgId);
            }

            /**
             * Re-enqueue for retry or send to DLQ if retries are exhausted.
             * Safe to call multiple times (idempotent after first call).
             */
            @Override
            public void nack() {
                if (settled) return;
                settled = true;
                inflightMap.remove(msgId);
                scheduleRetryOrDlq(envelope);
            }
        };
    }

    /** Re-enqueue messages that were delivered but never ack'd within the visibility window. */
    private void redeliverTimedOutMessages() {
        long now = System.currentTimeMillis();
        inflightMap.forEach((msgId, entry) -> {
            if (now - entry.deliveredAtMs > visibilityTimeoutMs) {
                // CAS-remove: only act if this exact entry is still present (prevents double redeliver)
                if (inflightMap.remove(msgId, entry)) {
                    scheduleRetryOrDlq(entry.envelope);
                }
            }
        });
    }

    /**
     * Either re-enqueue with an incremented attempt counter or send to DLQ.
     * A message where {@code attemptNumber + 1 >= maxRetries} goes straight to DLQ.
     */
    private void scheduleRetryOrDlq(Envelope<T> envelope) {
        int nextAttempt = envelope.attemptNumber + 1;
        if (nextAttempt >= maxRetries) {
            try {
                dlq.enqueue(envelope.message);
                System.out.printf("[DLQ] Message %s sent to dead-letter queue after %d attempts%n",
                        envelope.message.getId(), nextAttempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            pendingQueue.offer(new Envelope<>(envelope.message, nextAttempt));
            System.out.printf("[RETRY] Message %s re-enqueued (attempt %d/%d)%n",
                    envelope.message.getId(), nextAttempt + 1, maxRetries);
        }
    }
}
