package threads.atomicInteger.atomicbyte;


import java.util.concurrent.*;

class AtomicByteDemo {
    public static void main( String args[] ) throws Exception {

        AtomicByte atomicByte = new AtomicByte((byte) 0b11111111);
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CyclicBarrier cyclicBarrier = new CyclicBarrier(7);

        // We'll create seven threads to shift our initial pattern of all 1s
        // to the left by one. Eventually, we should see the pattern 10000000
        // i.e. a single one followed by seven zeros. We create seven threads
        // and each thread moves the pattern to the left by one.
        try {
            for (int i = 0; i < 7; i++) {
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
                        atomicByte.shiftLeft();
                    }
                });
            }
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.HOURS);
        }

        // print after the shift operations are complete. The
        // result should be 10000000
        atomicByte.print();

    }
}