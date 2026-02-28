package interview.fampay.ratelimiter;


import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RateLimiterTest {

    RateLimiter rateLimiter;
    @BeforeEach
    void setUp() {
        // Use long refill period so no refill happens during the test (avoids flakiness)
        rateLimiter = new TokenBucketRateLimiter(3, TimeUnit.HOURS.toMillis(1));
    }


    @Test
    void testConcurrent() {
        int threadCount = 10;
        CyclicBarrier cyclicBarrier = new CyclicBarrier(threadCount);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // Use an AtomicInteger to count the number of successful requests
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    cyclicBarrier.await();
                    boolean result = rateLimiter.request("userId");

                    // If request was successful, increment counter
                    if (result) {
                        successCount.incrementAndGet();
                    }

                    System.out.println("Time: " + System.currentTimeMillis() + " - Request allowed: " + result);
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new RuntimeException(e);
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        try {
            // Wait for all threads to complete
            completionLatch.await(10, TimeUnit.SECONDS);

            // Verify that exactly 3 requests were allowed
            System.out.println("Total successful requests: " + successCount.get());
            assertEquals(3, successCount.get(),
                    "Expected exactly 3 successful requests with token bucket size 3");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
