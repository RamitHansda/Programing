package threads.workerpool;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo for {@link WorkerPoolManager}: submit tasks, graceful shutdown, and stats.
 */
public class WorkerPoolDemo {

    public static void main(String[] args) throws InterruptedException {
        WorkerPoolConfig config = WorkerPoolConfig.builder()
                .poolSize(3)
                .queueCapacity(10)
                .rejectionPolicy(WorkerPoolConfig.RejectionPolicy.ABORT)
                .build();

        WorkerPoolManager pool = new WorkerPoolManager(config);
        AtomicInteger completed = new AtomicInteger(0);

        try {
            for (int i = 0; i < 8; i++) {
                final int taskId = i + 1;
                pool.execute(() -> {
                    System.out.println("Task " + taskId + " running on " + Thread.currentThread().getName());
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    completed.incrementAndGet();
                });
            }

            System.out.println("Submitted 8 tasks. Queue size: " + pool.getQueueSize());
            Thread.sleep(500);
            System.out.println("Completed so far: " + pool.getCompletedTaskCount());

            pool.shutdown();
            boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("Pool terminated: " + terminated + ", completed: " + pool.getCompletedTaskCount());
        } finally {
            if (!pool.isTerminated()) {
                List<Runnable> remaining = pool.shutdownNow();
                System.out.println("Remaining tasks not run: " + remaining.size());
            }
        }
    }
}
