package threads.atomicArray;



import java.util.concurrent.atomic.*;
        import java.util.concurrent.*;

class AtomicArrayDemo {

    public static void main( String args[] ) throws Exception {
        final int arrayLength = 10;
        AtomicIntegerArray atomicIntegerArray = new AtomicIntegerArray(arrayLength);
        AtomicInteger[] arrayOfAtomicIntegers = new AtomicInteger[arrayLength];

        for (int i = 0; i < arrayLength; i++) {
            arrayOfAtomicIntegers[i] = new AtomicInteger(0);
        }

        ExecutorService executor = Executors.newFixedThreadPool(15);

        try {

            for (int i = 0; i < arrayLength; i++) {

                executor.submit(new Runnable() {
                    @Override
                    public void run() {

                        for (int i = 0; i < 10000; i++) {
                            // choose a random index to add to
                            int index = ThreadLocalRandom.current().nextInt(arrayLength);

                            // add one to the integer at index i
                            atomicIntegerArray.addAndGet(index, 1);
                            arrayOfAtomicIntegers[index].getAndAdd(1);
                        }
                    }
                });
            }

        } finally {
            executor.shutdown();
            executor.awaitTermination(1L, TimeUnit.HOURS);
        }

        // print the atomic integer array
        for (int i = 0; i < arrayLength; i++) {
            System.out.print(atomicIntegerArray.get(i) + " ");
        }

        System.out.println();

        // print the array of atomic integers
        for (int i = 0; i < arrayLength; i++) {
            System.out.print(arrayOfAtomicIntegers[i].get() + " ");
        }
    }
}