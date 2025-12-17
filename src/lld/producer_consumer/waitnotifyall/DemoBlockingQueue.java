package lld.producer_consumer.waitnotifyall;

import java.util.LinkedList;

public class DemoBlockingQueue {
    public static void main(String[] args) {
        BlockingQueueWithWaitNotifyAll queue = new BlockingQueueWithWaitNotifyAll<Integer>(5);
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        queue.enqueue(new Integer(i));
                        System.out.println("Enqueued "+ i+ " by "+ Thread.currentThread().getName());
                    }
                } catch (InterruptedException e){

                }

            }
        }, "producer1");

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        Integer item = (Integer) queue.dequeue();
                        System.out.println("Dequeued "+ item+" by "+ Thread.currentThread().getName());
                    }
                } catch (InterruptedException e){

                }

            }
        }, "consumer1");

        Thread thread3 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i =1;i<20;i++){
                        Integer item = (Integer) queue.dequeue();
                        System.out.println("Dequeued "+ item+" by "+ Thread.currentThread().getName());
                    }
                } catch (InterruptedException e){

                }

            }
        }, "consumer2");

        thread2.start();
        thread1.start();
        thread3.start();

        try {
            thread2.join();
            thread3.join();
        } catch (Exception e){
            System.out.println("");
        }

        LinkedList<Integer> list = new LinkedList<>();
        list.addFirst(12);


    }
}
