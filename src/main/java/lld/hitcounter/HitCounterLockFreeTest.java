package lld.hitcounter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HitCounterLockFreeTest {

    // A test-friendly version of HitCounterLockFree with a fixed constructor
    private static class FixedHitCounter extends HitCounterAtomic {
        FixedHitCounter(int size) {
            super(size);
            // Initialize the atomic integers properly
        }
    }

    private FixedHitCounter hitCounter;
    
    @BeforeEach
    void setUp() {
        // Initialize the hit counter with a size of 300 for a 5-minute window
        hitCounter = new FixedHitCounter(300);
    }

    @Test
    @DisplayName("Basic functionality - Single hit")
    void testSingleHit() {
        // Arrange & Act
        long timestamp = 1000;
        hitCounter.hit(timestamp);
        
        // Assert
        assertEquals(1, hitCounter.getHits(timestamp));
    }

    @Test
    @DisplayName("Basic functionality - Multiple hits at same timestamp")
    void testMultipleHitsAtSameTimestamp() {
        // Arrange & Act
        long timestamp = 1000;
        int numberOfHits = 5;
        
        for (int i = 0; i < numberOfHits; i++) {
            hitCounter.hit(timestamp);
        }
        
        // Assert
        assertEquals(numberOfHits, hitCounter.getHits(timestamp));
    }

    @Test
    @DisplayName("Time window - Hits within window are counted")
    void testHitsWithinWindowAreCounted() {
        // Arrange
        long currentTime = 1000;
        
        // Add hits at various times within the window
        hitCounter.hit(currentTime - 150); // Within window
        hitCounter.hit(currentTime - 200); // Within window
        hitCounter.hit(currentTime - 299); // Within window
        hitCounter.hit(currentTime);       // Current time
        
        // Act & Assert
        assertEquals(4, hitCounter.getHits(currentTime));
    }

    @Test
    @DisplayName("Time window - Hits outside window are not counted")
    void testHitsOutsideWindowAreNotCounted() {
        // Arrange
        long currentTime = 1000;
        
        // Add hits at various times
        hitCounter.hit(currentTime - 150);    // Within window
        hitCounter.hit(currentTime - 300);    // Edge of window, should not be counted
        hitCounter.hit(currentTime - 301);    // Outside window, should not be counted
        hitCounter.hit(currentTime - 1000);   // Far outside window, should not be counted
        
        // Act & Assert
        assertEquals(1, hitCounter.getHits(currentTime));
    }

    @Test
    @DisplayName("Index calculation - Handles different timestamps correctly")
    void testIndexCalculation() {
        // Arrange
        int size = 10; // Small size for easier testing
        FixedHitCounter smallCounter = new FixedHitCounter(size);
        
        // Act - Hit timestamps that map to different indices
        smallCounter.hit(10); // Index 0
        smallCounter.hit(20); // Index 0
        smallCounter.hit(11); // Index 1
        smallCounter.hit(21); // Index 1
        
        // Assert - We should have 2 hits at each of these indices
        assertEquals(2, hitCounter.getHits(30)); // Using a timestamp that would include both
    }

    @Test
    @DisplayName("Edge case - Index wraparound")
    void testIndexWraparound() {
        // Arrange
        int size = 10;
        FixedHitCounter smallCounter = new FixedHitCounter(size);
        
        // Act - Hit timestamps that cause index wraparound
        smallCounter.hit(9);   // Index 9
        smallCounter.hit(19);  // Index 9
        smallCounter.hit(29);  // Index 9
        
        // Assert
        assertEquals(3, smallCounter.getHits(30)); // All three hits should be counted
    }

    @Test
    @DisplayName("Edge case - Zero timestamp")
    void testZeroTimestamp() {
        // Arrange & Act
        hitCounter.hit(0);
        
        // Assert

        assertEquals(1, hitCounter.getHits(300)); // Should be within the window of size 300
    }

    @Test
    @DisplayName("Edge case - Negative timestamp")
    void testNegativeTimestamp() {
        // Arrange & Act
        hitCounter.hit(-100);
        
        // Assert - We should still be able to get hits with a current timestamp
        assertEquals(0, hitCounter.getHits(200)); // Current time - size(300) < -100, so it should be counted
    }

    @Test
    @DisplayName("Concurrency - Multiple threads hitting same timestamp")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConcurrentHitsSameTimestamp() throws InterruptedException {
        // Arrange
        final int threadCount = 100;
        final long timestamp = 1000;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Act - Each thread hits the same timestamp once
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    hitCounter.hit(timestamp);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // Release all threads at once
        doneLatch.await();      // Wait for all threads to complete
        executor.shutdown();
        
        // Assert - We should have exactly threadCount hits
        assertEquals(threadCount, hitCounter.getHits(timestamp));
    }

    @Test
    @DisplayName("Concurrency - Multiple threads hitting different timestamps")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConcurrentHitsDifferentTimestamps() throws InterruptedException {
        // Arrange
        final int threadCount = 100;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final long baseTimestamp = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Act - Each thread hits a different timestamp
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    hitCounter.hit(baseTimestamp + index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // Release all threads at once
        doneLatch.await();      // Wait for all threads to complete
        executor.shutdown();
        
        // Assert - We should have exactly threadCount hits
        assertEquals(threadCount, hitCounter.getHits(baseTimestamp + threadCount));
    }

    @Test
    @DisplayName("Race condition - Multiple threads updating same slot with different timestamps")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRaceConditionDifferentTimestamps() throws InterruptedException {
        // This test specifically targets the race condition in the hit method
        // where two threads might try to update the same slot with different timestamps
        
        // Arrange
        int size = 10;
        FixedHitCounter smallCounter = new FixedHitCounter(size);
        
        final int threadCount = 1000;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threadCount);
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        
        // Create timestamps that will map to the same index but have different values
        // All these timestamps % 10 = 5, so they target the same index
        long[] timestamps = {5, 15, 25, 35, 45};
        
        // Act - Multiple threads hit different timestamps mapping to the same index
        for (int i = 0; i < threadCount; i++) {
            final long timestamp = timestamps[i % timestamps.length];
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    smallCounter.hit(timestamp);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        startLatch.countDown(); // Release all threads at once
        doneLatch.await();      // Wait for all threads to complete
        executor.shutdown();
        
        // Assert - Due to the race condition, we expect the count to be less than threadCount
        int hitsRecorded = smallCounter.getHits(50);
        System.out.println("Race condition test recorded " + hitsRecorded + " hits out of " + threadCount);
        assertNotEquals(threadCount, hitsRecorded);
    }

    @Test
    @DisplayName("Performance - High volume of hits")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPerformanceHighVolume() {
        // Arrange
        final int numHits = 1_000_000;
        final long baseTimestamp = 1000;
        
        // Act - Record timing for high volume of hits
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numHits; i++) {
            hitCounter.hit(baseTimestamp + (i % 300));
        }
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Assert - Just verify it completes within timeout
        // This also serves as a benchmark for future optimizations
        System.out.println("Time taken for " + numHits + " hits: " + durationMs + "ms");
        
        // We should have numHits spread across the slots
        assertEquals(numHits, hitCounter.getHits(baseTimestamp + 300));
    }

    @Test
    @DisplayName("Constructor initialization test")
    void testConstructorInitialization() {
        // Directly test the constructor initialization issue
        
        // Create a counter with the original buggy constructor
        HitCounterLockFree buggyCounter = new HitCounterLockFree(10);
        
        // Attempting to use it should throw NullPointerException
        Exception exception = assertThrows(NullPointerException.class, () -> {
            buggyCounter.hit(1000);
        });
        
        // Verify exception message relates to null AtomicInteger
        assertTrue(exception.getMessage() == null || 
                  exception.getMessage().isEmpty() || 
                  exception.getMessage().contains("null"));
    }
    
    @Test
    @DisplayName("Test boundary window cases")
    void testBoundaryWindowCases() {
        // Arrange
        long currentTime = 1000;
        
        // Act
        hitCounter.hit(currentTime - 299); // Just inside window
        hitCounter.hit(currentTime - 300); // Exactly at window boundary
        
        // Assert
        assertEquals(1, hitCounter.getHits(currentTime));
    }
    
    @Test
    @DisplayName("Test timestamp collisions with different values")
    void testTimestampCollisions() {
        // Arrange
        int size = 5;
        FixedHitCounter smallCounter = new FixedHitCounter(size);
        
        // Act - Hits with different timestamps that map to same index
        smallCounter.hit(0);   // Index 0
        smallCounter.hit(5);   // Index 0
        
        // The behavior is that when a new timestamp comes in at the same index,
        // it overwrites the old one and resets the counter
        
        // Assert
        assertEquals(1, smallCounter.getHits(10)); // Only the last hit should be counted
    }
    
    @Test
    @DisplayName("Test window sliding behavior")
    void testWindowSlidingBehavior() {
        // Arrange
        FixedHitCounter counter = new FixedHitCounter(5); // 5-second window
        
        // Add hits at sequential times
        counter.hit(10);
        counter.hit(11);
        counter.hit(12);
        counter.hit(13);
        counter.hit(14);
        
        // Assert all hits are counted at time 14
        assertEquals(5, counter.getHits(14));
        
        // Move time forward, window should slide
        assertEquals(4, counter.getHits(15)); // 10 falls out of window
        assertEquals(3, counter.getHits(16)); // 11 falls out of window
        assertEquals(2, counter.getHits(17)); // 12 falls out of window
        assertEquals(1, counter.getHits(18)); // 13 falls out of window
        assertEquals(0, counter.getHits(19)); // 14 falls out of window
    }
}