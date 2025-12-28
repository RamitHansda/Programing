package lld.counter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConcurrentCounterTest {
    private ConcurrentCounter counter;

    @BeforeEach
    void setUp(){
        counter = new ConcurrentCounter();
    }

    @Test
    @DisplayName("concurrent tests")
    void testConcurrentIncrements() throws InterruptedException{
        final int THREAD_COUNT = 10;
        final int INCREMENTS_PER_THREAD =1000;
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);

        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        for (int i=0;i<THREAD_COUNT;i++){
            executor.submit(()->{
                try{
                    readyLatch.countDown();
                    startLatch.await();

                    for (int j=0;j<INCREMENTS_PER_THREAD;j++){
                        counter.increment();
                    }
                } catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();

        executor.shutdown();
        if(!executor.awaitTermination(5, TimeUnit.SECONDS)){
            executor.shutdownNow();
        }

        int expectedValue = THREAD_COUNT * INCREMENTS_PER_THREAD;
        assertEquals(expectedValue, counter.get());

    }
}
