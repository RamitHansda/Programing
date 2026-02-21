package threads.atomicInteger.AtomicFloat;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class AtomicFloatDemo {

    public static void main( String args[] ) throws Exception {
        AtomicFloat atomicFloat = new AtomicFloat(0.23f);
        ExecutorService executorService = Executors.newFixedThreadPool(100);

        // create 50 threads that each adds 0.17 a 1000 times. At the end our atomic
        // float should sum up to 8500.23 but you'll see a close enough value since
        // float isn't precise. However, multiple runs of the program should produce
        // the same value indicating the class AtomicFloat the thread-safe.
        try {
            for (int i = 0; i < 50; i++) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < 1000; i++)
                            atomicFloat.getAndAdd(01.00f);
                    }
                });
            }
        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.HOURS);
        }

        // print after the shift operations are complete
        System.out.println(atomicFloat.floatValue());
    }
}
