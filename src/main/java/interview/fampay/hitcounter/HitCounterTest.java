package interview.fampay.hitcounter;


import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HitCounterTest {

    @Test
    public void testSingleThreadedHitCounter() {
        HitCounter counter = new HitCounter(60);

        // Add hits with different timestamps
        for (int i = 1; i <= 10; i++) {
            counter.hit(i);
        }

        // Add multiple hits at the same timestamp
        for (int i = 0; i < 5; i++) {
            counter.hit(15);
        }

        // Verify total hits within the last 60 seconds (all hits should be counted)
        assertEquals(15, counter.getHit(60));

        // Verify hits within a specific window
        assertEquals(5, counter.getHit(15) - counter.getHit(14));
    }

    @Test
    public void testConcurrentHits() throws InterruptedException {
        final int BUCKET_SIZE = 60;
        final int THREAD_COUNT = 10;
        final int HITS_PER_THREAD = 1000;
        final long TIMESTAMP = 100L;

        HitCounter counter = new HitCounter(BUCKET_SIZE);

        // CountDownLatch to ensure all threads start at roughly the same time
        CountDownLatch startLatch = new CountDownLatch(1);

        // CountDownLatch to wait for all threads to finish
        CountDownLatch finishLatch = new CountDownLatch(THREAD_COUNT);

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);

        // Create and start threads
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for the signal to start

                    // Each thread adds HITS_PER_THREAD hits at the same timestamp
                    for (int j = 0; j < HITS_PER_THREAD; j++) {
                        counter.hit(TIMESTAMP);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Signal all threads to start
        startLatch.countDown();

        // Wait for all threads to finish (with a timeout)
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        assertTrue(completed, "Not all threads completed within the timeout");

        // Verify the total hit count
        long expectedHits = THREAD_COUNT * HITS_PER_THREAD;
        long actualHits = counter.getHit(TIMESTAMP);
        assertEquals(expectedHits, actualHits, "Hit count mismatch under concurrent access");
    }

    @Test
    public void testConcurrentReadsAndWrites() throws InterruptedException {
        final int BUCKET_SIZE = 60;
        final int WRITER_THREADS = 5;
        final int READER_THREADS = 3;
        final int OPERATIONS_PER_THREAD = 1000;
        final long BASE_TIMESTAMP = 1000L;

        HitCounter counter = new HitCounter(BUCKET_SIZE);

        // Pre-populate with some hits
        for (int i = 0; i < 10; i++) {
            counter.hit(BASE_TIMESTAMP + i);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(WRITER_THREADS + READER_THREADS);

        ExecutorService executorService = Executors.newFixedThreadPool(WRITER_THREADS + READER_THREADS);

        // Writer threads
        for (int i = 0; i < WRITER_THREADS; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        long timestamp = BASE_TIMESTAMP + ((threadId * 10) + (j % 50));
                        counter.hit(timestamp);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Reader threads
        for (int i = 0; i < READER_THREADS; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        // Just read current hit count - we're not verifying the exact count
                        // but ensuring no exceptions are thrown during concurrent access
                        counter.getHit(BASE_TIMESTAMP + BUCKET_SIZE);

                        // Small sleep to make reads less aggressive
                        if (j % 100 == 0) {
                            Thread.sleep(1);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(20, TimeUnit.SECONDS);
        executorService.shutdown();

        assertTrue(completed, "Not all threads completed within the timeout");

        // The main assertion here is that we didn't throw any exceptions during concurrent access
        // We can also do a simple sanity check that we have some hits
        long hits = counter.getHit(BASE_TIMESTAMP + BUCKET_SIZE * 2);
        assertTrue(hits > 0, "Should have recorded some hits");
    }
}
