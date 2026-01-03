package threads.boundedbuffer.lockfree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BoundedBufferLockFreeTest {
    
    private BoundedBufferLockFree<Integer> buffer;
    private static final int DEFAULT_CAPACITY = 10;
    
    @BeforeEach
    void setUp() {
        buffer = new BoundedBufferLockFree<>(DEFAULT_CAPACITY);
    }
    
    @Test
    void testBasicEnqueueDequeue() {
        // Test single enqueue
        buffer.enqueue(42);
        
        // Test dequeue returns the same value
        Integer result = buffer.dequeue();
        Assertions.assertEquals(42, result);
        
        // Test buffer is now empty
        Assertions.assertNull(buffer.dequeue());
    }
    
    @Test
    void testMultipleEnqueueDequeue() {
        // Test multiple enqueue
        for (int i = 0; i < 5; i++) {
            buffer.enqueue(i);
        }
        
        // Test dequeue returns values in correct order
        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        
        // Test buffer is now empty
        Assertions.assertNull(buffer.dequeue());
    }
    
    @Test
    void testFullBuffer() {
        // Fill the buffer to capacity
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.enqueue(i);
        }
        
        // Attempt to enqueue when full should return
        buffer.enqueue(DEFAULT_CAPACITY);
        
        // Check that we can still dequeue DEFAULT_CAPACITY elements
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        
        // Check buffer is empty
        Assertions.assertNull(buffer.dequeue());
    }
    
    @Test
    void testNullRejection() {
        Assertions.assertThrows(NullPointerException.class, () -> buffer.enqueue(null));
    }
    
    @Test
    void testBufferWrapping() {
        // Fill buffer completely
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.enqueue(i);
        }
        
        // Dequeue half
        for (int i = 0; i < DEFAULT_CAPACITY / 2; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        
        // Enqueue more values to test wrapping
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            buffer.enqueue(i);
        }
        
        // Dequeue remaining values and verify order
        for (int i = DEFAULT_CAPACITY / 2; i < DEFAULT_CAPACITY; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
    }
    
    @Test
    void testHighContention() throws InterruptedException {
        final int smallBufferSize = 3;
        BoundedBufferLockFree<Integer> smallBuffer = new BoundedBufferLockFree<>(smallBufferSize);
        
        final int numThreads = 4;
        final int itemsPerThread = 5000;
        final int totalItems = numThreads * itemsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads * 2);
        
        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);
        
        // Create producer threads
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        int value = threadId * itemsPerThread + i;
                        smallBuffer.enqueue(value);
                        totalProduced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Create consumer threads
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int consumed = 0;
                    while (consumed < itemsPerThread) {
                        Integer value = smallBuffer.dequeue();
                        if (value != null) {
                            consumed++;
                            totalConsumed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads
        startLatch.countDown();
        
        // Wait for completion with timeout
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "High contention test timed out");
        
        // Verify all items were consumed
        Assertions.assertEquals(totalItems, totalProduced.get(), "Not all items were produced");
        Assertions.assertEquals(totalItems, totalConsumed.get(), "Not all items were consumed");
        
        // Check final buffer state - should be empty
        Assertions.assertNull(smallBuffer.dequeue());
        
        executor.shutdownNow();
    }

    @RepeatedTest(5)
    void testConcurrentEnqueueDequeue() throws InterruptedException {
        final int numThreads = 8;
        final int itemsPerThread = 1000;
        final int totalItems = numThreads * itemsPerThread;

        // Increase the buffer capacity to reduce contention
        final int testBufferCapacity = 100;
        BoundedBufferLockFree<Integer> testBuffer = new BoundedBufferLockFree<>(testBufferCapacity);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads * 2);

        // Create shared results list to track consumed values
        List<Integer> consumedValues = Collections.synchronizedList(new ArrayList<>());

        // Track progress for debugging
        AtomicInteger totalEnqueued = new AtomicInteger(0);
        AtomicInteger totalDequeued = new AtomicInteger(0);

        // Create threads that produce values
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        int value = threadId * itemsPerThread + i;
                        boolean enqueued = false;
                        int attempts = 0;

                        // Use exponential backoff for retries
                        long backoffTime = 1;

                        while (!enqueued && attempts < 1000) { // Add retry limit
                            enqueued = testBuffer.enqueue(value);
                            if (enqueued) {
                                totalEnqueued.incrementAndGet();
                            } else {
                                attempts++;
                                // Small backoff to reduce contention
                                Thread.yield();
                                if (attempts % 10 == 0) {
                                    try {
                                        Thread.sleep(backoffTime);
                                        backoffTime = Math.min(backoffTime * 2, 10); // Exponential up to 10ms
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }
                        }

                        if (!enqueued) {
                            System.err.println("Producer " + threadId + " failed to enqueue item " + i + " after maximum attempts");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Create threads that consume values
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int consumed = 0;
                    int attempts = 0;

                    while (consumed < itemsPerThread) {
                        Integer value = testBuffer.dequeue();
                        if (value != null) {
                            consumedValues.add(value);
                            consumed++;
                            totalDequeued.incrementAndGet();
                            attempts = 0; // Reset attempts on successful dequeue
                        } else {
                            attempts++;

                            // If we've made several unsuccessful attempts, check if we're done
                            if (attempts > 1000 && totalDequeued.get() >= totalItems - numThreads) {
                                // If we're close to the total, other threads might have consumed our share
                                break;
                            }

                            // Small backoff to reduce contention
                            Thread.yield();
                            if (attempts % 100 == 0) {
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();

        // Increase the timeout to accommodate more retries
        boolean completed = finishLatch.await(30, TimeUnit.SECONDS);

        // If not completed, output diagnostic information before failing
        if (!completed) {
            System.err.println("Test timeout: Enqueued=" + totalEnqueued.get() +
                    ", Dequeued=" + totalDequeued.get() +
                    " out of expected " + totalItems);
        }

        Assertions.assertTrue(completed, "Test timed out");

        // Verify all items were consumed exactly once
        Assertions.assertEquals(totalItems, consumedValues.size(),
                "Some items were not consumed. Expected: " + totalItems +
                        ", Actual: " + consumedValues.size());

        // Create expected set of items
        List<Integer> expectedValues = IntStream.range(0, totalItems).boxed().collect(Collectors.toList());

        // Sort and compare lists
        Collections.sort(consumedValues);
        Assertions.assertEquals(expectedValues, consumedValues, "Consumed values don't match expected values");

        executor.shutdownNow();
    }


}