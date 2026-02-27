package threads.workerpool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A worker pool manager that maintains a fixed number of worker threads
 * and a shared task queue. Tasks are submitted via {@link #execute(Runnable)}
 * or {@link #submit(Runnable)} and executed by available workers.
 * <p>
 * Supports graceful {@link #shutdown()} and forceful {@link #shutdownNow()},
 * plus {@link #awaitTermination(long, TimeUnit)}.
 */
public class WorkerPoolManager {

    private static final Runnable POISON = () -> {};

    private final WorkerPoolConfig config;
    private final BlockingQueue<Runnable> workQueue;
    private final List<Thread> workers;
    private volatile boolean shutdown;
    private volatile boolean shutdownNow;
    private final AtomicInteger completedTaskCount;

    public WorkerPoolManager(WorkerPoolConfig config) {
        this.config = config;
        this.workQueue = config.createWorkQueue();
        this.workers = new ArrayList<>(config.getPoolSize());
        this.shutdown = false;
        this.shutdownNow = false;
        this.completedTaskCount = new AtomicInteger(0);

        for (int i = 0; i < config.getPoolSize(); i++) {
            Thread worker = config.getThreadFactory().newThread(this::runWorker);
            workers.add(worker);
            worker.start();
        }
    }

    private void runWorker() {
        try {
            while (!shutdownNow) {
                Runnable task;
                try {
                    task = shutdown ? workQueue.poll(100, TimeUnit.MILLISECONDS) : workQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    if (shutdown && workQueue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (task == POISON) {
                    break;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
                    if (h != null) {
                        h.uncaughtException(Thread.currentThread(), t);
                    } else {
                        t.printStackTrace();
                    }
                } finally {
                    completedTaskCount.incrementAndGet();
                }
            }
        } finally {
            drainRemainingTasks();
        }
    }

    private void drainRemainingTasks() {
        Runnable task;
        while ((task = workQueue.poll()) != null && task != POISON) {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                completedTaskCount.incrementAndGet();
            }
        }
    }

    /**
     * Submits a task for execution. Returns when the task has been accepted
     * (and possibly queued); does not wait for the task to complete.
     *
     * @throws RejectedExecutionException if the pool is shut down or the task cannot be accepted
     */
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task is null");
        }
        if (shutdown || shutdownNow) {
            throw new RejectedExecutionException("Worker pool is shut down");
        }

        switch (config.getRejectionPolicy()) {
            case ABORT:
                if (!workQueue.offer(task)) {
                    throw new RejectedExecutionException("Work queue is full");
                }
                break;
            case CALLER_RUNS:
                try {
                    workQueue.put(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Interrupted while submitting task", e);
                }
                break;
            case DISCARD:
                workQueue.offer(task);
                break;
        }
    }

    /**
     * Alias for {@link #execute(Runnable)} for API compatibility.
     */
    public void submit(Runnable task) {
        execute(task);
    }

    /**
     * Initiates graceful shutdown. No new tasks should be submitted; existing
     * tasks in the queue will be processed. Workers exit after draining the queue.
     */
    public void shutdown() {
        shutdown = true;
        for (int i = 0; i < config.getPoolSize(); i++) {
            workQueue.offer(POISON);
        }
    }

    /**
     * Attempts to stop all workers immediately. Tasks in the queue are not
     * executed. Returns a list of tasks that were never run.
     */
    public List<Runnable> shutdownNow() {
        shutdownNow = true;
        shutdown = true;
        for (Thread w : workers) {
            w.interrupt();
        }
        List<Runnable> remaining = new ArrayList<>();
        workQueue.drainTo(remaining);
        remaining.removeIf(t -> t == POISON);
        return remaining;
    }

    /**
     * Blocks until all workers have terminated after shutdown, or the timeout occurs.
     *
     * @return true if all workers terminated, false if timeout elapsed
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread w : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            w.join(TimeUnit.NANOSECONDS.toMillis(remaining));
        }
        return true;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public boolean isTerminated() {
        for (Thread w : workers) {
            if (w.isAlive()) {
                return false;
            }
        }
        return true;
    }

    /** Number of tasks completed by workers so far. */
    public int getCompletedTaskCount() {
        return completedTaskCount.get();
    }

    /** Current number of tasks waiting in the queue. */
    public int getQueueSize() {
        return workQueue.size();
    }

    /** Number of worker threads. */
    public int getPoolSize() {
        return config.getPoolSize();
    }
}
