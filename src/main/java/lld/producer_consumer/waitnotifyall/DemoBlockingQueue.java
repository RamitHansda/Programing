package lld.producer_consumer.waitnotifyall;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DemoBlockingQueue {
    public static void main(String[] args) {
        BlockingQueueWithWaitNotifyAll queue = new BlockingQueueWithWaitNotifyAll<Integer>(5);
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        queue.enqueue(Integer.valueOf(i));
                    }
                } catch (InterruptedException e){

                }

            }
        }, "producer-1");

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        Integer item = (Integer) queue.dequeue();
                    }
                } catch (InterruptedException e){

                }

            }
        }, "consumer-1");

        Thread thread3 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        Integer item = (Integer) queue.dequeue();
                    }
                } catch (InterruptedException e){

                }

            }
        }, "consumer-2");

        thread2.start();
        thread1.start();
        thread3.start();

        try {
            thread1.join();
            thread2.join();
            thread3.join();
        } catch (Exception e){
            System.out.println("");
        }

        return;

    }
}
