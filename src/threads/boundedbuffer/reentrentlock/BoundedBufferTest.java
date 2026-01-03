package threads.boundedbuffer.reentrentlock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BoundedBufferTest {

    BoundedBuffer<Integer> boundedBuffer;
    int capacity;

    @BeforeEach
    void setUp(){
        capacity =4;
        boundedBuffer = new BoundedBuffer<Integer>(capacity);
    }

    @Test
    void testConcurrentUse(){
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CyclicBarrier cyclicBarrier = new CyclicBarrier(10);
        for (int i=0;i<5;i++){
            int finalI = i;
            executorService.submit(()->{
                try {
                    cyclicBarrier.await();
                    System.out.println("pushing item "+ finalI);
                    boundedBuffer.enqueue(finalI);
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new RuntimeException(e);
                }
            });
            executorService.submit(()->{
                try {
                    cyclicBarrier.await();
                    System.out.println(boundedBuffer.dequeue());
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        executorService.shutdown();

    }

    @Test
    void testFIFOBehavior() throws InterruptedException {
        // Test FIFO behavior sequentially
        for (int i = 0; i < capacity; i++) {
            boundedBuffer.enqueue(i);
        }

        for (int i = 0; i < capacity; i++) {
            assertEquals(i, boundedBuffer.dequeue(),
                    "Items should be dequeued in the same order they were enqueued");
        }
    }

    @Test
    void testConcurrentFIFOBehavior() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        final int numItems = 4; // Using more items to test concurrency better

        // Enqueue all items first
        CountDownLatch enqueueLatch = new CountDownLatch(numItems);
        for (int i = 0; i < numItems; i++) {
            final int item = i;
            executorService.submit(() -> {
                try {
                    boundedBuffer.enqueue(item);
                    enqueueLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all enqueues to complete
        assertTrue(enqueueLatch.await(5, TimeUnit.SECONDS));

        // Create a set to track dequeued items
        Set<Integer> dequeued = ConcurrentHashMap.newKeySet();
        CountDownLatch dequeueLatch = new CountDownLatch(numItems);

        // Dequeue all items concurrently
        for (int i = 0; i < numItems; i++) {
            executorService.submit(() -> {
                try {
                    Integer item = (Integer) boundedBuffer.dequeue();
                    dequeued.add(item);
                    dequeueLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for all dequeues to complete
        assertTrue(dequeueLatch.await(5, TimeUnit.SECONDS));

        // Verify all items were dequeued
        assertEquals(numItems, dequeued.size());
        for (int i = 0; i < numItems; i++) {
            assertTrue(dequeued.contains(i), "Item " + i + " should have been dequeued");
        }

        executorService.shutdown();
        assertTrue(executorService.awaitTermination(1, TimeUnit.SECONDS));
    }


}
