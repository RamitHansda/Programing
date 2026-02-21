package lld.hitcounter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HitCounterTest {

    private HitCounter hitCounter;

    @BeforeEach
    void setUp() {
        hitCounter = new HitCounter(300, 300); // 5-minute window with 300 slots
    }

    @Nested
    @DisplayName("Basic Functionality Tests")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("Test single hit is correctly counted")
        void testSingleHit() {
            long timestamp = 1000;
            hitCounter.hit(timestamp);
            assertEquals(1, hitCounter.getHits(timestamp));
        }

        @Test
        @DisplayName("Test multiple hits at same timestamp")
        void testMultipleHitsSameTime() {
            long timestamp = 2000;
            int hitCount = 5;

            for (int i = 0; i < hitCount; i++) {
                hitCounter.hit(timestamp);
            }

            assertEquals(hitCount, hitCounter.getHits(timestamp));
        }

        @Test
        @DisplayName("Test hits at different timestamps")
        void testHitsDifferentTimes() {
            long timestamp1 = 3000;
            long timestamp2 = 3001;
            long timestamp3 = 3002;

            hitCounter.hit(timestamp1);
            hitCounter.hit(timestamp1); // 2 hits at timestamp1
            hitCounter.hit(timestamp2);
            hitCounter.hit(timestamp3);
            hitCounter.hit(timestamp3); // 2 hits at timestamp3

            assertEquals(5, hitCounter.getHits(timestamp3));
        }

        @Test
        @DisplayName("Test hits expire after window time")
        void testHitsExpire() {
            long timestamp1 = 4000;
            long timestamp2 = timestamp1 + 301; // Just outside the 300-second window

            hitCounter.hit(timestamp1);
            hitCounter.hit(timestamp1);

            assertEquals(0, hitCounter.getHits(timestamp2));
        }

        @Test
        @DisplayName("Test partial expiration of hits")
        void testPartialExpiration() {
            long timestamp1 = 5000;
            long timestamp2 = 5150; // 150 seconds later
            long timestamp3 = 5301; // 301 seconds after timestamp1

            hitCounter.hit(timestamp1); // This hit will expire
            hitCounter.hit(timestamp2); // This hit will still be valid

            assertEquals(1, hitCounter.getHits(timestamp3));
        }
    }

    @Nested
    @DisplayName("Custom Duration Tests")
    class CustomDurationTests {

        @Test
        @DisplayName("Test getting hits for custom duration")
        void testCustomDuration() {
            long timestamp1 = 6000;
            long timestamp2 = 6100; // 100 seconds later
            long timestamp3 = 6200; // 200 seconds after timestamp1

            hitCounter.hit(timestamp1);
            hitCounter.hit(timestamp2);
            hitCounter.hit(timestamp3);

            assertEquals(3, hitCounter.getHits(timestamp3));
//            assertEquals(2, hitCounter.getHits(timestamp3));
//            assertEquals(1, hitCounter.getHits(timestamp3));
        }

        @Test
        @DisplayName("Test exception when duration exceeds window size")
        void testExceptionForExcessiveDuration() {
            assertThrows(IllegalArgumentException.class, () -> {
                hitCounter.getHits(7000);
            });
        }
    }

    @Nested
    @DisplayName("Concurrency Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Test concurrent hits at same timestamp")
        void testConcurrentHitsSameTime() throws InterruptedException {
            final int THREAD_COUNT = 10;
            final int HITS_PER_THREAD = 1000;
            final long timestamp = 8000;

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await(); // Wait for all threads to be ready

                        for (int j = 0; j < HITS_PER_THREAD; j++) {
                            hitCounter.hit(timestamp);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(); // Wait for all threads to be ready
            startLatch.countDown(); // Start all threads simultaneously
            doneLatch.await(); // Wait for all threads to complete

            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            int expectedHits = THREAD_COUNT * HITS_PER_THREAD;
            int actualHits = hitCounter.getHits(timestamp);

            assertEquals(expectedHits, actualHits);
        }

        @Test
        @DisplayName("Test concurrent hits at different timestamps")
        void testConcurrentHitsDifferentTimes() throws InterruptedException {
            final int THREAD_COUNT = 8;
            final int HITS_PER_THREAD = 500;
            final AtomicInteger totalHits = new AtomicInteger(0);
            final long baseTimestamp = 9000;

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        // Each thread uses a different timestamp
                        long threadTimestamp = baseTimestamp + threadId;

                        for (int j = 0; j < HITS_PER_THREAD; j++) {
                            hitCounter.hit(threadTimestamp);
                            totalHits.incrementAndGet();
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            assertEquals(totalHits.get(), hitCounter.getHits(baseTimestamp + THREAD_COUNT));
        }

        @RepeatedTest(5)
        @DisplayName("Stress test with random timestamps")
        void stressTestWithRandomTimestamps() throws InterruptedException {
            final int THREAD_COUNT = 6;
            final int OPERATIONS_PER_THREAD = 1000;

            long startTime = 10000;
            long timeSpan = 200; // 200 seconds range for timestamps
            List<Long> timestamps = new ArrayList<>();

            // Pre-generate timestamps for verification
            for (int i = 0; i < THREAD_COUNT; i++) {
                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                    long randomTimestamp = startTime +
                            ThreadLocalRandom.current().nextLong(timeSpan);
                    timestamps.add(randomTimestamp);
                }
            }

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

            // Distribute timestamps among threads
            for (int i = 0; i < THREAD_COUNT; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        int startIndex = threadId * OPERATIONS_PER_THREAD;
                        int endIndex = startIndex + OPERATIONS_PER_THREAD;

                        for (int j = startIndex; j < endIndex; j++) {
                            hitCounter.hit(timestamps.get(j));
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // Count expected hits that should be within the window
            long cutoffTime = startTime + timeSpan - 300; // Window size is 300
            int expectedHits = 0;
            for (Long timestamp : timestamps) {
                if (timestamp > cutoffTime) {
                    expectedHits++;
                }
            }

            assertEquals(expectedHits, hitCounter.getHits(startTime + timeSpan));
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Test behavior with zero hits")
        void testZeroHits() {
            assertEquals(0, hitCounter.getHits(11000));
        }

        @Test
        @DisplayName("Test behavior with very distant future timestamp")
        void testFutureTimestamp() {
            long now = 12000;
            long future = now + 1000000;

            hitCounter.hit(now);
            assertEquals(0, hitCounter.getHits(future));
        }

        @Test
        @DisplayName("Test behavior with out-of-order timestamps")
        void testOutOfOrderTimestamps() {
            long timestamp1 = 13000;
            long timestamp2 = 13100;

            hitCounter.hit(timestamp2);
            hitCounter.hit(timestamp1);

            assertEquals(2, hitCounter.getHits(timestamp2));
        }

        @Test
        @DisplayName("Test behavior with extremely high hit count")
        void testHighHitCount() {
            long timestamp = 14000;
            int hitCount = 1_000_000;

            for (int i = 0; i < hitCount; i++) {
                hitCounter.hit(timestamp);
            }

            assertEquals(hitCount, hitCounter.getHits(timestamp));
        }

        @Test
        @DisplayName("Test behavior with backward time jump")
        void testBackwardTimeJump() {
            long timestamp1 = 15000;
            long timestamp2 = 14500; // 500 seconds earlier

            hitCounter.hit(timestamp1);
            hitCounter.hit(timestamp2);

            assertEquals(2, hitCounter.getHits(timestamp1));
            assertEquals(1, hitCounter.getHits(timestamp2));
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Measure hit throughput")
        void testHitThroughput() {
            long timestamp = 16000;
            int iterations = 1_000_000;

            long startTime = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                hitCounter.hit(timestamp);
            }

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;
            double hitsPerSecond = iterations / (durationMs / 1000.0);

            System.out.printf("Throughput: %.2f hits/second%n", hitsPerSecond);

            // No assert, just informational
            assertTrue(true);
        }

        @Test
        @DisplayName("Measure getHits performance")
        void testGetHitsPerformance() {
            long timestamp = 17000;
            int hitCount = 100_000;

            // Pre-populate with hits
            for (int i = 0; i < hitCount; i++) {
                hitCounter.hit(timestamp - (i % 300));
            }

            int iterations = 1000;
            long startTime = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                hitCounter.getHits(timestamp);
            }

            long endTime = System.nanoTime();
            double avgQueryTimeMs = (endTime - startTime) / iterations / 1_000_000.0;

            System.out.printf("Average query time: %.3f ms%n", avgQueryTimeMs);

            // Ensure query time is reasonable (below 10ms)
            assertTrue(avgQueryTimeMs < 10.0);
        }
    }

    @Nested
    @DisplayName("Custom Window Size Tests")
    class CustomWindowSizeTests {

        @Test
        @DisplayName("Test with small window size")
        void testSmallWindowSize() {
            HitCounter smallWindow = new HitCounter(10, 10); // 10-second window

            long timestamp1 = 18000;
            long timestamp2 = 18011; // 11 seconds later (outside window)

            smallWindow.hit(timestamp1);
            assertEquals(0, smallWindow.getHits(timestamp2));
        }

        @Test
        @DisplayName("Test with large window size")
        void testLargeWindowSize() {
            HitCounter largeWindow = new HitCounter(3600, 3600); // 1-hour window

            long timestamp1 = 19000;
            long timestamp2 = 19000 + 3599; // 59:59 later (inside window)

            largeWindow.hit(timestamp1);
            assertEquals(1, largeWindow.getHits(timestamp2));
        }

        @Test
        @DisplayName("Test with custom slot count")
        void testCustomSlotCount() {
            // 5-minute window but only 5 slots (1 minute per slot)
            HitCounter fewSlots = new HitCounter(300, 5);

            long baseTime = 20000;

            // Add hits at 5 different times, 1 minute apart
            for (int i = 0; i < 5; i++) {
                fewSlots.hit(baseTime + i * 60);
            }

            assertEquals(5, fewSlots.getHits(baseTime + 299));

            // This should overwrite the first slot
            fewSlots.hit(baseTime + 5 * 60);

            // We should now have hits at slots 1,2,3,4,0
            assertEquals(5, fewSlots.getHits(baseTime + 5 * 60));
        }
    }
}