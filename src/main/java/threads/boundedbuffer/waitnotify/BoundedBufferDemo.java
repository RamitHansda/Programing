package threads.boundedbuffer.waitnotify;

/**
 * Demo: multiple producers and consumers using the bounded buffer (wait/notify).
 */
public class BoundedBufferDemo {

    public static void main(String[] args) throws InterruptedException {
        int capacity = 5;
        BoundedBufferWaitNotify<Integer> buffer = new BoundedBufferWaitNotify<>(capacity);

        int itemsPerProducer = 10;
        int numProducers = 2;
        int numConsumers = 2;

        // Producers
        Thread[] producers = new Thread[numProducers];
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int value = producerId * 100 + i;
                        buffer.put(value);
                        System.out.println("Producer " + producerId + " put: " + value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Producer-" + p);
        }

        // Consumers
        Thread[] consumers = new Thread[numConsumers];
        for (int c = 0; c < numConsumers; c++) {
            final int consumerId = c;
            consumers[c] = new Thread(() -> {
                try {
                    for (int i = 0; i < (numProducers * itemsPerProducer) / numConsumers; i++) {
                        Integer value = buffer.take();
                        System.out.println("Consumer " + consumerId + " took: " + value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Consumer-" + c);
        }

        for (Thread t : producers) t.start();
        for (Thread t : consumers) t.start();

        for (Thread t : producers) t.join();
        for (Thread t : consumers) t.join();

        System.out.println("Done. Buffer size: " + buffer.size());
    }
}
