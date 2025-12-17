package lld.producer_consumer.countingsemphore;


class BlockingQueueSemaphoreDemo {
    public static void main( String args[] ) throws InterruptedException {
        final BlockingQueueWithSemaphore<Integer> q = new BlockingQueueWithSemaphore<Integer>(5);

        Thread t1 = new Thread(new Runnable() {

            public void run() {
                try {
                    for (int i = 0; i < 20; i++) {
                        q.enqueue(new Integer(i));
                        System.out.println("Thread 1 enqueued: " + i);
                    }
                } catch (InterruptedException ie) {

                }
            }
        });

        Thread t4 = new Thread(new Runnable() {

            public void run() {
                try {
                    for (int i = 30; i < 40; i++) {
                        q.enqueue(new Integer(i));
                        System.out.println("Thread 4 enqueued: " + i);
                    }
                } catch (InterruptedException ie) {

                }
            }
        });

        Thread t2 = new Thread(new Runnable() {

            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        System.out.println("Thread 2 dequeued: " + q.dequeue());
                    }
                } catch (InterruptedException ie) {

                }
            }
        });

        Thread t3 = new Thread(new Runnable() {

            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        System.out.println("Thread 3 dequeued: " + q.dequeue());
                    }
                } catch (InterruptedException ie) {

                }
            }
        });

        t1.start();
        t4.start();
        //Thread.sleep(4000);
        t2.start();
        t2.join();

        t3.start();
        t1.join();
        t3.join();
        t4.join();

    }
}