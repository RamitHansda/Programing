package threads.atomicInteger;


import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AtomicIntegerDemo1 {

    static AtomicInteger counter = new AtomicInteger(0);
    static int simpleCounter = 0;

    public static void main( String args[] ) throws Exception {
        test(true);
        test(false);
    }

    synchronized static void incrementSimpleCounter() {
        simpleCounter++;
    }

    static void test(boolean isAtomic) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        long start = System.currentTimeMillis();
        CyclicBarrier cyclicBarrier = new CyclicBarrier(10);

        try {
            for (int i = 0; i < 10; i++) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cyclicBarrier.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (BrokenBarrierException e) {
                            throw new RuntimeException(e);
                        }
                        for (int i = 0; i < 1000000; i++) {

                            if (isAtomic) {
                                counter.incrementAndGet();
                            } else {
                                incrementSimpleCounter();
                            }
                        }
                    }
                });
            }
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.HOURS);
        }

        long timeTaken = System.currentTimeMillis() - start;
        System.out.println("Time taken by " + (isAtomic ? "atomic integer counter " : "integer counter ") + timeTaken + " milliseconds.");
    }
}