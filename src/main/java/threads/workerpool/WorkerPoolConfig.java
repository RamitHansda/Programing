package threads.workerpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for {@link WorkerPoolManager}.
 * Use the builder to set pool size, queue capacity, and optional thread factory.
 */
public class WorkerPoolConfig {

    private final int poolSize;
    private final int queueCapacity;
    private final ThreadFactory threadFactory;
    private final RejectionPolicy rejectionPolicy;

    private WorkerPoolConfig(Builder builder) {
        this.poolSize = builder.poolSize;
        this.queueCapacity = builder.queueCapacity;
        this.threadFactory = builder.threadFactory != null ? builder.threadFactory : defaultThreadFactory();
        this.rejectionPolicy = builder.rejectionPolicy != null ? builder.rejectionPolicy : RejectionPolicy.ABORT;
    }

    private static ThreadFactory defaultThreadFactory() {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, "worker-pool-" + counter.incrementAndGet());
            t.setDaemon(false);
            return t;
        };
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public RejectionPolicy getRejectionPolicy() {
        return rejectionPolicy;
    }

    /**
     * Creates a blocking queue with the configured capacity.
     * Unbounded if queueCapacity <= 0.
     */
    public BlockingQueue<Runnable> createWorkQueue() {
        return queueCapacity > 0
                ? new LinkedBlockingQueue<>(queueCapacity)
                : new LinkedBlockingQueue<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int poolSize = Runtime.getRuntime().availableProcessors();
        private int queueCapacity = 100;
        private ThreadFactory threadFactory;
        private RejectionPolicy rejectionPolicy;

        public Builder poolSize(int poolSize) {
            if (poolSize <= 0) {
                throw new IllegalArgumentException("poolSize must be positive");
            }
            this.poolSize = poolSize;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("queueCapacity must be non-negative");
            }
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder threadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public Builder rejectionPolicy(RejectionPolicy rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
            return this;
        }

        public WorkerPoolConfig build() {
            return new WorkerPoolConfig(this);
        }
    }

    public enum RejectionPolicy {
        /** Throw RejectedExecutionException when queue is full. */
        ABORT,
        /** Block the caller until the queue has space. */
        CALLER_RUNS,
        /** Discard the task silently. */
        DISCARD
    }
}
