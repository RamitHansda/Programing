package threads.boundedbuffer.waitnotify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedBufferWaitNotifyTest {

    private static final int DEFAULT_CAPACITY = 4;

    private BoundedBufferWaitNotify<Integer> buffer;

    @BeforeEach
    void setUp() {
        buffer = new BoundedBufferWaitNotify<>(DEFAULT_CAPACITY);
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBufferWaitNotify<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBufferWaitNotify<>(-1));
    }

    @Test
    void putThenTakeReturnsSameItem() throws InterruptedException {
        buffer.put(42);
        assertEquals(42, buffer.take());
        assertEquals(0, buffer.size());
    }

    @Test
    void fifoOrderIsPreserved() throws InterruptedException {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.put(i);
        }
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            assertEquals(i, buffer.take());
        }
        assertEquals(0, buffer.size());
    }

    @Test
    void sizeAndCapacity() throws InterruptedException {
        assertEquals(0, buffer.size());
        assertEquals(DEFAULT_CAPACITY, buffer.getCapacity());

        buffer.put(1);
        buffer.put(2);
        assertEquals(2, buffer.size());

        buffer.take();
        assertEquals(1, buffer.size());
        assertEquals(DEFAULT_CAPACITY, buffer.getCapacity());
    }

    @Test
    void circularBufferWrapsAround() throws InterruptedException {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.put(i);
        }
        for (int i = 0; i < DEFAULT_CAPACITY / 2; i++) {
            assertEquals(i, buffer.take());
        }
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            buffer.put(i);
        }
        for (int i = DEFAULT_CAPACITY / 2; i < DEFAULT_CAPACITY; i++) {
            assertEquals(i, buffer.take());
        }
        for (int i = DEFAULT_CAPACITY; i < DEFAULT_CAPACITY + DEFAULT_CAPACITY / 2; i++) {
            assertEquals(i, buffer.take());
        }
        assertEquals(0, buffer.size());
    }

    @Test
    void takeBlocksUntilItemIsAvailable() throws Exception {
        CountDownLatch consumerStarted = new CountDownLatch(1);
        AtomicInteger taken = new AtomicInteger(-1);

        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                taken.set(buffer.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        assertTrue(consumerStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(consumer.isAlive(), "consumer should still be blocked on empty buffer");

        buffer.put(7);
        consumer.join(2000);
        assertFalse(consumer.isAlive());
        assertEquals(7, taken.get());
    }

    @Test
    void putBlocksUntilSpaceIsAvailable() throws Exception {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.put(i);
        }

        CountDownLatch producerStarted = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                producerStarted.countDown();
                buffer.put(99);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        assertTrue(producerStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(producer.isAlive(), "producer should still be blocked on full buffer");
        assertEquals(DEFAULT_CAPACITY, buffer.size());

        assertEquals(0, buffer.take());
        producer.join(2000);
        assertFalse(producer.isAlive());
        assertEquals(DEFAULT_CAPACITY, buffer.size());
    }

    @Test
    void takeThrowsWhenInterruptedWhileWaiting() throws Exception {
        Thread consumer = new Thread(() -> {
            assertThrows(InterruptedException.class, () -> buffer.take());
        });
        consumer.start();
        Thread.sleep(50);
        consumer.interrupt();
        consumer.join(2000);
        assertFalse(consumer.isAlive());
    }

    @Test
    void putThrowsWhenInterruptedWhileWaiting() throws Exception {
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            buffer.put(i);
        }

        Thread producer = new Thread(() -> {
            assertThrows(InterruptedException.class, () -> buffer.put(99));
        });
        producer.start();
        Thread.sleep(50);
        producer.interrupt();
        producer.join(2000);
        assertFalse(producer.isAlive());
        assertEquals(DEFAULT_CAPACITY, buffer.size());
    }

    @Test
    void singleProducerSingleConsumerPreservesOrder() throws InterruptedException {
        final int totalItems = 1000;
        BoundedBufferWaitNotify<Integer> testBuffer = new BoundedBufferWaitNotify<>(8);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    testBuffer.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    consumed.add(testBuffer.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start();
        consumer.start();
        producer.join(10_000);
        consumer.join(10_000);

        assertFalse(producer.isAlive(), "producer timed out");
        assertFalse(consumer.isAlive(), "consumer timed out");
        assertEquals(IntStream.range(0, totalItems).boxed().collect(Collectors.toList()), consumed);
        assertEquals(0, testBuffer.size());
    }

    @Test
    void concurrentProducersAndConsumersDeliverEveryItemOnce() throws Exception {
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 250;
        final int totalItems = numProducers * itemsPerProducer;
        BoundedBufferWaitNotify<Integer> testBuffer = new BoundedBufferWaitNotify<>(16);

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < numProducers; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < itemsPerProducer; i++) {
                    testBuffer.put(threadId * itemsPerProducer + i);
                }
                return null;
            }));
        }
        for (int t = 0; t < numConsumers; t++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < itemsPerProducer; i++) {
                    consumed.add(testBuffer.take());
                }
                return null;
            }));
        }

        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertEquals(totalItems, consumed.size());
        Set<Integer> unique = new HashSet<>(consumed);
        assertEquals(totalItems, unique.size(), "duplicate or missing values");
        assertTrue(unique.containsAll(IntStream.range(0, totalItems).boxed().collect(Collectors.toSet())));
        assertEquals(0, testBuffer.size());

        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void moreProducersThanCapacityStillComplete() throws Exception {
        final int capacity = 2;
        final int producers = 8;
        BoundedBufferWaitNotify<Integer> smallBuffer = new BoundedBufferWaitNotify<>(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(producers + 1);
        CountDownLatch allProduced = new CountDownLatch(producers);
        List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < producers; i++) {
            final int value = i;
            executor.submit(() -> {
                try {
                    smallBuffer.put(value);
                    allProduced.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        executor.submit(() -> {
            try {
                for (int i = 0; i < producers; i++) {
                    consumed.add(smallBuffer.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(allProduced.await(10, TimeUnit.SECONDS), "producers did not finish");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(producers, consumed.size());
        assertEquals(producers, new HashSet<>(consumed).size());
        assertEquals(0, smallBuffer.size());
    }
}
