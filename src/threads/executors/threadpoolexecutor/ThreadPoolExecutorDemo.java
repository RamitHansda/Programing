package threads.executors.threadpoolexecutor;


import java.util.concurrent.*;
class ThreadPoolExecutorDemo {
    public static void main(String args[]) throws InterruptedException {
        // Create an instance of the ThreadPoolExecutor
        // Parameters: corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, rejectionHandler
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                1, 5, 1, TimeUnit.SECONDS,
                new SynchronousQueue<>(), // Fixed: Changed from LinkedBlockingDeque to LinkedBlockingQueue, SynchronousQueue makes queue grow
                new ThreadPoolExecutor.AbortPolicy());

        threadPoolExecutor.allowCoreThreadTimeOut(true);


        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch readyLatch = new CountDownLatch(3); // Fixed: Match with number of tasks (3)
        CountDownLatch completionLatch = new CountDownLatch(3); // Added: To track when all tasks complete

        try {
            // Submit three tasks
            for (int i = 0; i < 3; i++) {
                final int taskId = i + 1; // Added: Task identifier
                threadPoolExecutor.submit(() -> { // Using lambda instead of anonymous Runnable
                    try {
                        // Signal that the task is ready
                        System.out.println("Task " + taskId + " ready on thread " + Thread.currentThread().getName());
                        readyLatch.countDown();

                        // Wait for the signal to start execution
                        startLatch.await();

                        // Simulate actual work
                        System.out.println("Task " + taskId + " executing on thread " +
                                Thread.currentThread().getName() + " at " + System.currentTimeMillis());
                        Thread.sleep(1000); // Uncommented: To simulate work

                        System.out.println("Task " + taskId + " completed on thread " +
                                Thread.currentThread().getName());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // Fixed: Properly handle interruption
                        System.err.println("Task " + taskId + " was interrupted");
                    } finally {
                        completionLatch.countDown(); // Signal task completion
                    }
                });
            }

            // Wait for all tasks to be ready
            System.out.println("Waiting for all tasks to be ready...");
            readyLatch.await();
            System.out.println("All tasks ready, starting execution...");

            // Start all tasks simultaneously
            startLatch.countDown();

            // Wait for all tasks to complete
            System.out.println("Waiting for all tasks to complete...");
            completionLatch.await();
            System.out.println("All tasks completed");

        } finally {
            // Shutdown the executor properly
            threadPoolExecutor.shutdown();
            // Wait for termination or force shutdown after 5 seconds
            if (!threadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.println("Some tasks did not complete in time. Forcing shutdown.");
                threadPoolExecutor.shutdownNow();
            }
        }
    }
}