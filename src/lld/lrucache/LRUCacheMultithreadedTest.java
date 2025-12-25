package lld.lrucache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheMultithreadedTest {
    
    private LRUCache<String, Integer> cache;
    
    @BeforeEach
    void setUp() {
        // Initialize a cache with capacity 100 for multithreaded tests
        cache = new LRUCache<>(100);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentPuts_shouldNotLoseData() throws InterruptedException {
        final int numThreads = 10;
        final int itemsPerThread = 20;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numThreads);
        final AtomicBoolean hasErrors = new AtomicBoolean(false);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Each thread adds its own set of items
                    for (int j = 0; j < itemsPerThread; j++) {
                        String key = "t" + threadId + "-k" + j;
                        cache.put(key, threadId * 1000 + j);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    hasErrors.set(true);
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        finishLatch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertFalse(hasErrors.get(), "Some threads encountered errors");
        
        // Verify all items were added
        int count = 0;
        for (int i = 0; i < numThreads; i++) {
            for (int j = 0; j < itemsPerThread; j++) {
                String key = "t" + i + "-k" + j;
                Integer value = cache.get(key);
                if (value != null) {
                    assertEquals(i * 1000 + j, value.intValue(), 
                            "Value for key " + key + " is incorrect");
                    count++;
                }
            }
        }
        
        // We might not get all items if cache capacity was exceeded
        // but we should have the cache filled to capacity
        assertTrue(count > 0, "No items were found in the cache");
        assertTrue(count <= 100, "Cache exceeded its capacity");
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentGetsAndPuts_shouldBeConsistent() throws InterruptedException {
        final int initialItems = 50;
        // Prefill the cache
        for (int i = 0; i < initialItems; i++) {
            cache.put("key" + i, i);
        }
        
        final int numThreads = 8;
        final int operationsPerThread = 1000;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numThreads);
        final AtomicBoolean hasErrors = new AtomicBoolean(false);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (j % 2 == 0) {
                            // Even operations: read a random value
                            int keyIndex = (threadId + j) % initialItems;
                            Integer value = cache.get("key" + keyIndex);
                            // Value should either be null or match the key
                            if (value != null && value != keyIndex) {
                                System.err.println("Thread " + threadId + " got incorrect value " 
                                        + value + " for key" + keyIndex);
                                hasErrors.set(true);
                            }
                        } else {
                            // Odd operations: write a new value
                            int keyIndex = (threadId + j) % initialItems;
                            cache.put("key" + keyIndex, keyIndex);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    hasErrors.set(true);
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        finishLatch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertFalse(hasErrors.get(), "Some threads encountered consistency errors");
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void concurrentRemoves_shouldRemoveAllKeys() throws InterruptedException {
        final Set<String> keys = new HashSet<>();
        final int itemsToAdd = 80;
        
        // Prefill the cache
        for (int i = 0; i < itemsToAdd; i++) {
            String key = "key" + i;
            cache.put(key, i);
            keys.add(key);
        }
        
        final int numThreads = 4;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numThreads);
        final List<String> keysPerThread = new ArrayList<>(keys);
        final AtomicBoolean hasErrors = new AtomicBoolean(false);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Each thread removes its portion of keys
                    int keysPerThreadCount = keysPerThread.size() / numThreads;
                    int startIndex = threadId * keysPerThreadCount;
                    int endIndex = (threadId == numThreads - 1) 
                            ? keysPerThread.size() : startIndex + keysPerThreadCount;
                    
                    for (int j = startIndex; j < endIndex; j++) {
                        String key = keysPerThread.get(j);
                        cache.remove(key);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    hasErrors.set(true);
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        finishLatch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertFalse(hasErrors.get(), "Some threads encountered errors");
        
        // Verify all keys were removed
        for (String key : keys) {
            assertNull(cache.get(key), "Key " + key + " should have been removed");
        }
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stressTest_shouldHandleHighConcurrency() throws InterruptedException {
        final int numThreads = 20;
        final int operationsPerThread = 5000;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numThreads);
        final AtomicInteger successfulOperations = new AtomicInteger(0);
        final AtomicBoolean hasErrors = new AtomicBoolean(false);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            int operation = j % 3;
                            String key = "key" + ((threadId * 1000 + j) % 150); // Use a limited key space
                            
                            switch (operation) {
                                case 0: // Put
                                    cache.put(key, threadId * 10000 + j);
                                    break;
                                case 1: // Get
                                    cache.get(key);
                                    break;
                                case 2: // Remove
                                    cache.remove(key);
                                    break;
                            }
                            successfulOperations.incrementAndGet();
                        } catch (Exception e) {
                            e.printStackTrace();
                            hasErrors.set(true);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    hasErrors.set(true);
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to finish
        finishLatch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertFalse(hasErrors.get(), "Some threads encountered errors");
        assertEquals(numThreads * operationsPerThread, successfulOperations.get(), 
                "Not all operations completed successfully");
    }
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deadlockTest_shouldNotDeadlock() throws InterruptedException {
        // Prefill the cache
        for (int i = 0; i < 50; i++) {
            cache.put("key" + i, i);
        }
        
        final int numThreads = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(numThreads);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Each thread performs a mix of operations on the same keys
                    for (int j = 0; j < 1000; j++) {
                        int keyIndex = j % 10;  // Focus on a small set of keys to increase contention
                        String key = "key" + keyIndex;
                        
                        switch (j % 3) {
                            case 0:
                                cache.get(key);
                                break;
                            case 1:
                                cache.put(key, threadId * 1000 + j);
                                break;
                            case 2:
                                cache.remove(key);
                                // Put it back to maintain keys in the cache
                                cache.put(key, threadId * 1000 + j);
                                break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // If there's a deadlock, this will timeout
        assertTrue(finishLatch.await(9, TimeUnit.SECONDS), 
                "Deadlock detected: not all threads completed in time");
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }
}