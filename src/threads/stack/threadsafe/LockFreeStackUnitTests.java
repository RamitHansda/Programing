package threads.stack.threadsafe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LockFreeStackUnitTests {
    
    private LockFreeStack<Integer> stack;
    
    @BeforeEach
    void setUp() {
        stack = new LockFreeStack<>();
    }
    
    @Test
    void testPushAndPop() {
        // Test basic push and pop functionality
        stack.push(1);
        stack.push(2);
        stack.push(3);
        
        Assertions.assertEquals(3, stack.pop(), "Should pop 3 first (LIFO)");
        Assertions.assertEquals(2, stack.pop(), "Should pop 2 second");
        Assertions.assertEquals(1, stack.pop(), "Should pop 1 last");
        Assertions.assertNull(stack.pop(), "Empty stack should return null");
    }
    
    @Test
    void testEmptyStack() {
        // Test behavior with empty stack
        Assertions.assertNull(stack.pop(), "Popping from empty stack should return null");
        
        // Push after empty and verify it works
        stack.push(42);
        Assertions.assertEquals(42, stack.pop(), "Should be able to push and pop after empty stack");
        Assertions.assertNull(stack.pop(), "Stack should be empty again");
    }
    
    @Test
    void testPushNull() {
        // The implementation allows null values, so test this behavior
        stack.push(null);
        Assertions.assertNull(stack.pop(), "Should pop null value");
        Assertions.assertNull(stack.pop(), "Stack should be empty now");
    }
    
    @Test
    void testLIFOOrdering() {
        // Test that stack maintains LIFO ordering with many elements
        int elementCount = 1000;
        
        // Push elements in ascending order
        for (int i = 0; i < elementCount; i++) {
            stack.push(i);
        }
        
        // Pop should return elements in descending order
        for (int i = elementCount - 1; i >= 0; i--) {
            Assertions.assertEquals(i, stack.pop(), "Elements should be popped in LIFO order");
        }
        
        Assertions.assertNull(stack.pop(), "Stack should be empty after all pops");
    }
    
    @Test
    void testMultipleOperations() {
        // Test mixing push and pop operations
        stack.push(1);
        stack.push(2);
        Assertions.assertEquals(2, stack.pop());
        
        stack.push(3);
        stack.push(4);
        Assertions.assertEquals(4, stack.pop());
        Assertions.assertEquals(3, stack.pop());
        Assertions.assertEquals(1, stack.pop());
        
        // Push more after emptying
        stack.push(5);
        Assertions.assertEquals(5, stack.pop());
        Assertions.assertNull(stack.pop());
    }
    
    @RepeatedTest(5)
    void testConcurrentPushes() throws InterruptedException {
        final int numThreads = 8;
        final int pushesPerThread = 1000;
        final int totalPushes = numThreads * pushesPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        
        // Have multiple threads push concurrently
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    for (int i = 0; i < pushesPerThread; i++) {
                        int value = threadId * pushesPerThread + i;
                        stack.push(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "Push operations timed out");
        
        // Now pop all items and ensure we get the correct total
        Set<Integer> poppedItems = new HashSet<>(totalPushes);
        Integer item;
        int count = 0;
        
        while ((item = stack.pop()) != null) {
            poppedItems.add(item);
            count++;
        }
        
        Assertions.assertEquals(totalPushes, count, "Should pop the same number of items as pushed");
        Assertions.assertEquals(totalPushes, poppedItems.size(), "All popped items should be unique");
        
        executor.shutdownNow();
    }

    @RepeatedTest(5)
    void testConcurrentPushPop() throws InterruptedException {
        final int numProducers = 4;
        final int numConsumers = 4;
        final int itemsPerProducer = 5000;
        final int totalItems = numProducers * itemsPerProducer;

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + numConsumers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersFinished = new CountDownLatch(numProducers);
        CountDownLatch consumersFinished = new CountDownLatch(numConsumers);

        AtomicInteger totalPopped = new AtomicInteger(0);
        List<Integer> poppedItems = Collections.synchronizedList(new ArrayList<>(totalItems));

        // Producer threads
        for (int t = 0; t < numProducers; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = threadId * itemsPerProducer + i;
                        stack.push(value);

                        // Small delay to increase interleaving
                        if (i % 100 == 0) {
                            Thread.yield();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersFinished.countDown();
                }
            });
        }

        // Consumer threads
        for (int t = 0; t < numConsumers; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    boolean done = false;
                    while (!done) {
                        // Try to get an item
                        Integer value = stack.pop();
                        if (value != null) {
                            poppedItems.add(value);
                            totalPopped.incrementAndGet();
                        } else {
                            // Check if we're done (producers finished and stack empty)
                            if (producersFinished.getCount() == 0) {
                                // All producers are done, but we need to make sure stack is empty
                                // We may need multiple checks because other consumers might still be working
                                boolean isEmpty = true;
                                for (int i = 0; i < 3; i++) {  // Multiple attempts to confirm emptiness
                                    Thread.yield();  // Give other threads a chance
                                    if (stack.pop() != null) {
                                        isEmpty = false;
                                        break;
                                    }
                                }
                                if (isEmpty) {
                                    done = true;
                                }
                            } else {
                                // Producers still running, yield to let them add more items
                                Thread.yield();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    consumersFinished.countDown();
                }
            });
        }

        // Start all threads
        startLatch.countDown();

        // Wait for all producers to finish
        boolean producersCompleted = producersFinished.await(10, TimeUnit.SECONDS);
        Assertions.assertTrue(producersCompleted, "Producer operations timed out");

        // Wait for all consumers to finish
        boolean consumersCompleted = consumersFinished.await(30, TimeUnit.SECONDS);  // Increased timeout
        Assertions.assertTrue(consumersCompleted, "Consumer operations timed out");

        // Drain any remaining items (in case consumers missed some)
        List<Integer> remainingItems = new ArrayList<>();
        Integer item;
        while ((item = stack.pop()) != null) {
            remainingItems.add(item);
        }

        if (!remainingItems.isEmpty()) {
            System.out.println("Warning: " + remainingItems.size() + " items remained in the stack after consumers finished");
            // Add remaining items to our result lists
            poppedItems.addAll(remainingItems);
            totalPopped.addAndGet(remainingItems.size());
        }

        // Verify all items were popped
        Assertions.assertEquals(totalItems, totalPopped.get(),
                "Should have popped same number of items as pushed");

        // Check that all expected items were popped
        Set<Integer> uniquePopped = new HashSet<>(poppedItems);
        Assertions.assertEquals(totalItems, uniquePopped.size(),
                "All items should have been popped exactly once");

        // Verify the expected range of values
        Set<Integer> expectedItems = IntStream.range(0, totalItems).boxed().collect(Collectors.toSet());
        Assertions.assertEquals(expectedItems, uniquePopped,
                "The set of popped items should match the set of pushed items");

        // Stack should be empty now
        Assertions.assertNull(stack.pop(), "Stack should be empty after test");

        executor.shutdownNow();
    }


    @Test
    void testHighContention() throws InterruptedException {
        final int threadCount = 16; // High thread count to increase contention
        final int operationsPerThread = 10000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        
        // Half the threads push, half pop
        for (int i = 0; i < threadCount; i++) {
            final boolean isPusher = i % 2 == 0;
            final int threadId = i / 2;
            
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    if (isPusher) {
                        // Pusher thread
                        for (int j = 0; j < operationsPerThread; j++) {
                            int value = threadId * operationsPerThread + j;
                            stack.push(value);
                        }
                    } else {
                        // Popper thread
                        int pops = 0;
                        while (pops < operationsPerThread) {
                            Integer value = stack.pop();
                            if (value != null) {
                                pops++;
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
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion with timeout
        boolean completed = finishLatch.await(30, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "High contention test timed out");
        
        executor.shutdownNow();
    }
    
    @Test
    void testStackElementsPreservation() {
        // Test that elements maintain their identity through push/pop cycles
        List<String> testObjects = List.of("alpha", "beta", "gamma", "delta");
        
        LockFreeStack<String> stringStack = new LockFreeStack<>();
        
        // Push all strings
        for (String s : testObjects) {
            stringStack.push(s);
        }
        
        // Pop in reverse and verify identity
        for (int i = testObjects.size() - 1; i >= 0; i--) {
            String expected = testObjects.get(i);
            String actual = stringStack.pop();
            
            Assertions.assertSame(expected, actual, "Stack should preserve object identity");
        }
    }
}