package threads.atomicInteger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class AtomicIntegerDemo {

    static volatile boolean won = false;
    static volatile AtomicBoolean boolVar;

    public static void main( String args[] ) throws Exception {
        //CountDownLatch countDownLatch = new CountDownLatch(15);
        //CountDownLatch startLatch = new CountDownLatch(1);
        CyclicBarrier cyclicBarrier = new CyclicBarrier(15);
        ExecutorService es = Executors.newFixedThreadPool(15);
        try {
            int numThreads = 15;
            Runnable[] racers = new Runnable[numThreads];
            Future[] futures = new Future[numThreads];

            // create thread tasks
            for (int i = 0; i < numThreads; i++) {
                racers[i] = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cyclicBarrier.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (BrokenBarrierException e) {
                            throw new RuntimeException(e);
                        }
                        System.out.println(Thread.currentThread().getName() + " " + System.currentTimeMillis());
                        race();
                    }
                };
            }

            // submit threads for execution
            for (int i = 0; i < numThreads; i++) {
                futures[i] = es.submit(racers[i]);
            }

            //countDownLatch.await();
            //startLatch.countDown();


            // wait for threads to finish
            for (int i = 0; i < numThreads; i++) {
                futures[i].get();
            }
        } finally {
            es.shutdown();
        }
    }

    static void race() {
        if (!won) {
            won = true;
            System.out.println(Thread.currentThread().getName() + " won the race.");
        } else {
            System.out.println(Thread.currentThread().getName() + " lost.");
        }
    }

}