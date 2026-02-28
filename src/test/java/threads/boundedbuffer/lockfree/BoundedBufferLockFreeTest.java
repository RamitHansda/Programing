package threads.boundedbuffer.lockfree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        buffer.enqueue(42);
        Assertions.assertEquals(42, buffer.dequeue());
        Assertions.assertNull(buffer.dequeue());
    }

    @Test
    void testMultipleEnqueueDequeue() {
        for (int i = 0; i < 5; i++) {
            buffer.enqueue(i);
        }
        for (int i = 0; i < 5; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        Assertions.assertNull(buffer.dequeue());
    }

    @Test
    void testFullBuffer() {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            Assertions.assertTrue(buffer.enqueue(i), "enqueue should succeed when not full");
        }
        // Enqueue when full must return false (non-blocking API)
        Assertions.assertFalse(buffer.enqueue(DEFAULT_CAPACITY), "enqueue when full should return false");
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        Assertions.assertNull(buffer.dequeue());
    }

    @Test
    void testNullRejection() {
        Assertions.assertThrows(NullPointerException.class, () -> buffer.enqueue(null));
    }

    @Test
    void testBufferWrapping() {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.enqueue(i);
        }
        for (int i = 0; i < DEFAULT_CAPACITY / 2; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            buffer.enqueue(i);
        }
        for (int i = DEFAULT_CAPACITY / 2; i < DEFAULT_CAPACITY; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            Assertions.assertEquals(i, buffer.dequeue());
        }
    }

    @Test
    void testSizeAndCapacity() {
        Assertions.assertEquals(0, buffer.size());
        Assertions.assertEquals(DEFAULT_CAPACITY, buffer.getCapacity());
        buffer.enqueue(1);
        buffer.enqueue(2);
        Assertions.assertEquals(2, buffer.size());
        Assertions.assertEquals(DEFAULT_CAPACITY, buffer.getCapacity());
        buffer.dequeue();
        Assertions.assertEquals(1, buffer.size());
    }

    @Test
    void testHighContention() throws InterruptedException {
        final int bufferSize = 16;
        BoundedBufferLockFree<Integer> smallBuffer = new BoundedBufferLockFree<>(bufferSize);

        final int numThreads = 4;
        final int itemsPerThread = 1000;
        final int totalItems = numThreads * itemsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads * 2);

        AtomicInteger totalProduced = new AtomicInteger(0);
        AtomicInteger totalConsumed = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        int value = threadId * itemsPerThread + i;
                        while (!smallBuffer.enqueue(value)) {
                            Thread.onSpinWait();
                        }
                        totalProduced.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

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
                        } else {
                            Thread.onSpinWait();
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
        boolean completed = finishLatch.await(60, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "High contention test timed out");
        Assertions.assertEquals(totalItems, totalProduced.get(), "Not all items were produced");
        Assertions.assertEquals(totalItems, totalConsumed.get(), "Not all items were consumed");
        Assertions.assertNull(smallBuffer.dequeue());

        executor.shutdownNow();
    }

    @Test
    void testConcurrentEnqueueDequeue() throws InterruptedException {
        final int numThreads = 4;
        final int itemsPerThread = 500;
        final int totalItems = numThreads * itemsPerThread;
        final int testBufferCapacity = 64;
        BoundedBufferLockFree<Integer> testBuffer = new BoundedBufferLockFree<>(testBufferCapacity);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads * 2);
        List<Integer> consumedValues = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        int value = threadId * itemsPerThread + i;
                        while (!testBuffer.enqueue(value)) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int consumed = 0;
                    while (consumed < itemsPerThread) {
                        Integer value = testBuffer.dequeue();
                        if (value != null) {
                            consumedValues.add(value);
                            consumed++;
                        } else {
                            Thread.onSpinWait();
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
        boolean completed = finishLatch.await(60, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "Test timed out");
        Assertions.assertEquals(totalItems, consumedValues.size(),
                "Expected " + totalItems + " consumed, got " + consumedValues.size());

        // All values must be in valid range; no lost or out-of-range items
        Set<Integer> consumedSet = new HashSet<>(consumedValues);
        for (Integer v : consumedValues) {
            Assertions.assertTrue(v >= 0 && v < totalItems, "Value out of range: " + v);
        }
        Assertions.assertEquals(totalItems, consumedSet.size(),
                "Duplicate or missing values: expected " + totalItems + " unique, got " + consumedSet.size());
        Assertions.assertTrue(consumedSet.containsAll(IntStream.range(0, totalItems).boxed().collect(Collectors.toSet())),
                "All expected values 0.." + (totalItems - 1) + " must appear exactly once");

        executor.shutdownNow();
    }

    @Test
    void testSingleProducerSingleConsumer() throws InterruptedException {
        final int totalItems = 5000;
        BoundedBufferLockFree<Integer> testBuffer = new BoundedBufferLockFree<>(32);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        Thread producer = new Thread(() -> {
            for (int i = 0; i < totalItems; i++) {
                while (!testBuffer.enqueue(i)) {
                    Thread.onSpinWait();
                }
            }
        });
        Thread consumer = new Thread(() -> {
            for (int i = 0; i < totalItems; i++) {
                Integer v;
                while ((v = testBuffer.dequeue()) == null) {
                    Thread.onSpinWait();
                }
                consumed.add(v);
            }
        });
        producer.start();
        consumer.start();
        producer.join(10000);
        consumer.join(10000);
        Assertions.assertEquals(totalItems, consumed.size());
        Assertions.assertEquals(IntStream.range(0, totalItems).boxed().collect(Collectors.toList()), consumed);
    }
}
