package lld.taskscheduler;


import java.util.UUID;
import java.util.concurrent.*;
        import java.util.concurrent.atomic.AtomicBoolean;

public class TaskScheduler implements AutoCloseable {

    /**
     * Tasks are ordered based on when they should execute.
     *
     * DelayQueue blocks until the head task's delay expires.
     */
    private final DelayQueue<ScheduledTask> taskQueue =
            new DelayQueue<>();

    /**
     * Allows cancellation by task ID.
     */
    private final ConcurrentHashMap<String, ScheduledTask> tasks =
            new ConcurrentHashMap<>();

    /**
     * Actual execution of tasks.
     *
     * Keeping scheduling and execution separate is important:
     * a slow task should not block the scheduler from picking
     * up other ready tasks.
     */
    private final ExecutorService executor;

    /**
     * Scheduler workers.
     */
    private final ExecutorService schedulerWorkers;

    private final AtomicBoolean running =
            new AtomicBoolean(true);


    public TaskScheduler(int workerCount) {

        if (workerCount <= 0) {
            throw new IllegalArgumentException(
                    "workerCount must be greater than 0"
            );
        }

        this.executor =
                Executors.newFixedThreadPool(workerCount);

        this.schedulerWorkers =
                Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            schedulerWorkers.submit(this::workerLoop);
        }
    }


    // -------------------------------------------------------
    // Schedule One-Time Task
    // -------------------------------------------------------

    public String schedule(
            Runnable task,
            long delay,
            TimeUnit unit
    ) {

        if (task == null) {
            throw new IllegalArgumentException(
                    "task cannot be null"
            );
        }

        if (delay < 0) {
            throw new IllegalArgumentException(
                    "delay cannot be negative"
            );
        }

        String taskId = UUID.randomUUID().toString();

        long executionTime =
                System.nanoTime()
                        + unit.toNanos(delay);

        ScheduledTask scheduledTask =
                new ScheduledTask(
                        taskId,
                        task,
                        executionTime,
                        0
                );

        tasks.put(taskId, scheduledTask);
        taskQueue.offer(scheduledTask);

        return taskId;
    }


    // -------------------------------------------------------
    // Schedule Periodic Task
    // -------------------------------------------------------

    public String scheduleAtFixedRate(
            Runnable task,
            long initialDelay,
            long period,
            TimeUnit unit
    ) {

        if (task == null) {
            throw new IllegalArgumentException(
                    "task cannot be null"
            );
        }

        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    "initialDelay cannot be negative"
            );
        }

        if (period <= 0) {
            throw new IllegalArgumentException(
                    "period must be greater than zero"
            );
        }

        String taskId = UUID.randomUUID().toString();

        long executionTime =
                System.nanoTime()
                        + unit.toNanos(initialDelay);

        ScheduledTask scheduledTask =
                new ScheduledTask(
                        taskId,
                        task,
                        executionTime,
                        unit.toNanos(period)
                );

        tasks.put(taskId, scheduledTask);
        taskQueue.offer(scheduledTask);

        return taskId;
    }


    // -------------------------------------------------------
    // Cancel
    // -------------------------------------------------------

    public boolean cancel(String taskId) {

        ScheduledTask task =
                tasks.remove(taskId);

        if (task == null) {
            return false;
        }

        task.cancelled.set(true);

        /*
         * DelayQueue.remove() is O(n).
         *
         * We don't strictly need to remove it here.
         * The worker will skip it when it reaches the head.
         *
         * Removing eagerly is useful to avoid retaining
         * cancelled tasks unnecessarily.
         */
        taskQueue.remove(task);

        return true;
    }


    // -------------------------------------------------------
    // Worker Loop
    // -------------------------------------------------------

    private void workerLoop() {

        while (running.get()) {

            try {

                /*
                 * DelayQueue.take() blocks until the task
                 * is ready for execution.
                 */
                ScheduledTask task =
                        taskQueue.take();

                if (task.cancelled.get()) {
                    continue;
                }

                /*
                 * Submit actual business execution to
                 * executor.
                 */
                executor.submit(() -> execute(task));

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                break;
            }
        }
    }


    // -------------------------------------------------------
    // Execute
    // -------------------------------------------------------

    private void execute(ScheduledTask task) {

        if (task.cancelled.get()) {
            return;
        }

        try {

            task.runnable.run();

        } catch (Exception e) {

            System.err.println(
                    "Task failed: "
                            + task.id
                            + ", error="
                            + e.getMessage()
            );
        }

        /*
         * If periodic, schedule next execution.
         */
        if (
                task.periodNanos > 0
                        && !task.cancelled.get()
                        && running.get()
        ) {

            long nextExecutionTime =
                    task.executionTime
                            + task.periodNanos;

            task.executionTime =
                    nextExecutionTime;

            taskQueue.offer(task);

        } else {

            /*
             * One-time task is finished.
             */
            tasks.remove(task.id, task);
        }
    }


    // -------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        schedulerWorkers.shutdownNow();
        executor.shutdown();
    }


    // =======================================================
    // Scheduled Task
    // =======================================================

    private static class ScheduledTask
            implements Delayed {

        private final String id;

        private final Runnable runnable;

        private volatile long executionTime;

        private final long periodNanos;

        private final AtomicBoolean cancelled =
                new AtomicBoolean(false);


        ScheduledTask(
                String id,
                Runnable runnable,
                long executionTime,
                long periodNanos
        ) {
            this.id = id;
            this.runnable = runnable;
            this.executionTime = executionTime;
            this.periodNanos = periodNanos;
        }


        @Override
        public long getDelay(TimeUnit unit) {

            long remaining =
                    executionTime
                            - System.nanoTime();

            return unit.convert(
                    remaining,
                    TimeUnit.NANOSECONDS
            );
        }


        @Override
        public int compareTo(Delayed other) {

            ScheduledTask otherTask =
                    (ScheduledTask) other;

            return Long.compare(
                    this.executionTime,
                    otherTask.executionTime
            );
        }

        @Override
        public boolean equals(Object o) {

            if (this == o) {
                return true;
            }

            if (!(o instanceof ScheduledTask that)) {
                return false;
            }

            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}