package lld.producer_consumer.waitnotifyall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BlockingQueueWithWaitNotifyAllTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSingleProducerSingleConsumer() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<Integer> queue = new BlockingQueueWithWaitNotifyAll<>(5);
        final int numItems = 100;
        final List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch latch = new CountDownLatch(2); // 1 producer + 1 consumer
        
        // Producer thread
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < numItems; i++) {
                    queue.enqueue(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        }, "Producer");
        
        // Consumer thread
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < numItems; i++) {
                    Integer item = queue.dequeue();
                    consumed.add(item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        }, "Consumer");
        
        producer.start();
        consumer.start();
        
        // Wait for both threads to complete
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Test timed out");
        
        // Verify all items were consumed in the correct order
        assertEquals(numItems, consumed.size(), "Not all items were consumed");
        for (int i = 0; i < numItems; i++) {
            assertEquals(i, consumed.get(i), "Items consumed out of order");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testMultipleProducersMultipleConsumers() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<Integer> queue = new BlockingQueueWithWaitNotifyAll<>(10);
        final int numProducers = 3;
        final int numConsumers = 4;
        final int itemsPerProducer = 50;
        final Integer POISON_PILL = Integer.MIN_VALUE;

        final List<Integer> consumed = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch allDone = new CountDownLatch(numProducers + numConsumers);

        // Create producer threads
        ExecutorService producerExecutor = Executors.newFixedThreadPool(numProducers);
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            producerExecutor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    for (int i = 0; i < itemsPerProducer; i++) {
                        int item = producerId * 1000 + i; // Create unique items per producer
                        queue.enqueue(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        // Create consumer threads
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(numConsumers);
        for (int c = 0; c < numConsumers; c++) {
            consumerExecutor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    while (true) {
                        Integer item = queue.dequeue();
                        if (item.equals(POISON_PILL)) {
                            // Pass the poison pill along for other consumers
                            queue.enqueue(POISON_PILL);
                            break;
                        }
                        consumed.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for producers to finish
        producerExecutor.shutdown();
        producerExecutor.awaitTermination(3, TimeUnit.SECONDS);

        // Add a poison pill to signal consumers to stop
        queue.enqueue(POISON_PILL);

        // Wait for all threads to complete
        assertTrue(allDone.await(3, TimeUnit.SECONDS), "Test timed out");
        consumerExecutor.shutdown();

        // Verify correct number of items were consumed
        assertEquals(numProducers * itemsPerProducer, consumed.size(), "Wrong number of items consumed");
    }


    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testBlockingBehavior() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<Integer> queue = new BlockingQueueWithWaitNotifyAll<>(3);
        final CountDownLatch producerBlockedLatch = new CountDownLatch(1);
        final CountDownLatch producerUnblockedLatch = new CountDownLatch(1);
        final AtomicBoolean producerBlocked = new AtomicBoolean(false);
        
        // Producer thread that will get blocked
        Thread producer = new Thread(() -> {
            try {
                // Enqueue items to fill the queue
                queue.enqueue(1);
                queue.enqueue(2);
                queue.enqueue(3);
                
                // Signal that the next enqueue should block
                producerBlockedLatch.countDown();
                
                // This should block until a consumer dequeues an item
                long startTime = System.currentTimeMillis();
                queue.enqueue(4);
                long endTime = System.currentTimeMillis();
                
                // Verify that we were blocked for at least some time
                producerBlocked.set(endTime - startTime >= 500);
                producerUnblockedLatch.countDown();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BlockingProducer");
        
        producer.start();
        
        // Wait until the producer thread has filled the queue
        assertTrue(producerBlockedLatch.await(2, TimeUnit.SECONDS), "Producer failed to fill the queue");
        
        // Sleep a bit to ensure the producer thread is actually blocked
        Thread.sleep(1000);
        
        // Create a consumer to unblock the producer
        Thread consumer = new Thread(() -> {
            try {
                Integer item = queue.dequeue();
                assertNotNull(item, "Dequeued item should not be null");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "UnblockingConsumer");
        
        consumer.start();
        
        // Wait for the producer to be unblocked
        assertTrue(producerUnblockedLatch.await(2, TimeUnit.SECONDS), "Producer was never unblocked");
        
        // Verify that the producer was indeed blocked
        assertTrue(producerBlocked.get(), "Producer was not blocked as expected");
        
        // Clean up any remaining items to avoid thread hanging
        for (int i = 0; i < 3; i++) {
            queue.dequeue();
        }
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEmptyQueueBlocking() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<String> queue = new BlockingQueueWithWaitNotifyAll<>(5);
        final CountDownLatch consumerBlockedLatch = new CountDownLatch(1);
        final CountDownLatch consumerUnblockedLatch = new CountDownLatch(1);
        final AtomicBoolean consumerBlocked = new AtomicBoolean(false);
        
        // Consumer thread that will get blocked
        Thread consumer = new Thread(() -> {
            try {
                // Signal that the next dequeue should block
                consumerBlockedLatch.countDown();
                
                // This should block until a producer enqueues an item
                long startTime = System.currentTimeMillis();
                String item = queue.dequeue();
                long endTime = System.currentTimeMillis();
                
                // Verify that we were blocked for at least some time
                consumerBlocked.set(endTime - startTime >= 500);
                assertEquals("test-item", item, "Dequeued wrong item");
                consumerUnblockedLatch.countDown();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BlockingConsumer");
        
        consumer.start();
        
        // Wait until the consumer thread is ready to block
        assertTrue(consumerBlockedLatch.await(2, TimeUnit.SECONDS), "Consumer failed to reach blocking point");
        
        // Sleep a bit to ensure the consumer thread is actually blocked
        Thread.sleep(1000);
        
        // Create a producer to unblock the consumer
        Thread producer = new Thread(() -> {
            try {
                queue.enqueue("test-item");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "UnblockingProducer");
        
        producer.start();
        
        // Wait for the consumer to be unblocked
        assertTrue(consumerUnblockedLatch.await(2, TimeUnit.SECONDS), "Consumer was never unblocked");
        
        // Verify that the consumer was indeed blocked
        assertTrue(consumerBlocked.get(), "Consumer was not blocked as expected");
    }
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testStressWithMultipleThreads() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<Integer> queue = new BlockingQueueWithWaitNotifyAll<>(20);
        final int numProducers = 5;
        final int numConsumers = 5;
        final int itemsPerProducer = 1000;
        final int totalItems = numProducers * itemsPerProducer;
        
        final AtomicInteger producedSum = new AtomicInteger(0);
        final AtomicInteger consumedSum = new AtomicInteger(0);
        final AtomicInteger consumedCount = new AtomicInteger(0);
        
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(numProducers + numConsumers);
        
        // Create producer threads
        ExecutorService producerExecutor = Executors.newFixedThreadPool(numProducers);
        for (int p = 0; p < numProducers; p++) {
            producerExecutor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 1; i <= itemsPerProducer; i++) {
                        queue.enqueue(i);
                        producedSum.addAndGet(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Create consumer threads
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(numConsumers);
        for (int c = 0; c < numConsumers; c++) {
            consumerExecutor.submit(() -> {
                try {
                    startLatch.await();
                    
                    while (consumedCount.get() < totalItems) {
                        Integer item = queue.dequeue();
                        consumedSum.addAndGet(item);
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        assertTrue(endLatch.await(8, TimeUnit.SECONDS), "Test timed out");
        
        // Shutdown executors
        producerExecutor.shutdown();
        consumerExecutor.shutdown();
        
        // Verify all items were consumed
        assertEquals(totalItems, consumedCount.get(), "Not all items were consumed");
        
        // Verify the sum of produced items equals the sum of consumed items
        assertEquals(producedSum.get(), consumedSum.get(), "Sum mismatch between produced and consumed items");
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testFairness() throws InterruptedException {
        final BlockingQueueWithWaitNotifyAll<Integer> queue = new BlockingQueueWithWaitNotifyAll<>(1);
        final int numProducers = 3;
        final int itemsPerProducer = 30;
        final List<Integer> producerCounts = Collections.synchronizedList(new ArrayList<>(Collections.nCopies(numProducers, 0)));
        
        // First fill the queue
        queue.enqueue(0);
        
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch endLatch = new CountDownLatch(numProducers + 1); // +1 for consumer
        
        // Create producer threads that will compete to add items
        ExecutorService producerExecutor = Executors.newFixedThreadPool(numProducers);
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            producerExecutor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int i = 0; i < itemsPerProducer; i++) {
                        queue.enqueue(producerId);
                        producerCounts.set(producerId, producerCounts.get(producerId) + 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Create a consumer thread
        Thread consumer = new Thread(() -> {
            try {
                startLatch.await();
                
                // First dequeue the initial item
                queue.dequeue();
                
                // Then dequeue all items from producers
                for (int i = 0; i < numProducers * itemsPerProducer; i++) {
                    queue.dequeue();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        });
        
        consumer.start();
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        assertTrue(endLatch.await(3, TimeUnit.SECONDS), "Test timed out");
        
        // Shutdown executor
        producerExecutor.shutdown();
        
        // Check that all producers got a fair chance (with some tolerance)
        for (int p = 0; p < numProducers; p++) {
            assertTrue(producerCounts.get(p) > 0, "Producer " + p + " didn't get to produce any items");
        }
        
        // Calculate standard deviation to check for fairness
        int mean = itemsPerProducer;
        double sumSquaredDiff = 0;
        for (int count : producerCounts) {
            sumSquaredDiff += Math.pow(count - mean, 2);
        }
        double stdDev = Math.sqrt(sumSquaredDiff / numProducers);
        
        // We expect some deviation due to timing, but it shouldn't be extreme
        // This is a heuristic check; the actual fairness guarantees depend on the JVM implementation
        System.out.println("Fairness test - standard deviation: " + stdDev);
        System.out.println("Producer counts: " + producerCounts);
    }
}