package threads;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;

class Demonstration {

    public static void main(String args[]) throws InterruptedException {
        RaceCondition.runTest();
    }
}

class RaceCondition {


    int randInt;
    Random random = new Random(System.currentTimeMillis());

    private final Object lock = new Object();
    Semaphore semaphore = new Semaphore(1);
    ReentrantLock reentrantLock = new ReentrantLock();

    StampedLock stampedLock = new StampedLock();

// semaphore
//    void printer() {
//
//        int i = 1000000;
//        while (i != 0) {
//            try {
//            semaphore.acquire();
//            if (randInt.get() % 5 == 0) {
//                if (randInt.get() % 5 != 0)
//                    System.out.println(randInt.get());
//                else System.out.println("correct");
//            }} catch (InterruptedException e){
//                Thread.currentThread().interrupt();
//            } finally {
//                semaphore.release();   // unlock
//            }
//            i--;
//        }
//    }

    // mutex lock
//    void printer() {
//
//        int i = 1000000;
//        while (i != 0) {
//            synchronized (lock){
//                if (randInt.get() % 5 == 0) {
//                    if (randInt.get() % 5 != 0)
//                        System.out.println(randInt.get());
//                    else System.out.println("correct");
//                }
//            }
//            i--;
//        }
//    }

//    void printer() {
//
//        int i = 1000000;
//        while (i != 0) {
//            reentrantLock.lock();
//                if (randInt.get() % 5 == 0) {
//                    if (randInt.get() % 5 != 0)
//                        System.out.println(randInt.get());
//                    else System.out.println("correct");
//            }
//            reentrantLock.unlock();
//            i--;
//        }
//    }


    void printer() {

        int i = 10;
        while (i != 0) {
            long stamp = stampedLock.readLock();
            System.out.println("Got read lock "+ stamp + " "+ Thread.currentThread().getName());
            if (randInt % 5 == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (randInt % 5 != 0)
                    System.out.println(randInt);
                else System.out.println("correct "+ stamp + " " + Thread.currentThread().getName());
            }

            stampedLock.unlock(stamp);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            i--;
        }
    }

//    void modifier() {
//
//        int i = 1000000;
//        while (i != 0) {
//            try {
//                semaphore.acquire();
//            randInt.set(random.nextInt(1000));
//            } catch (InterruptedException e){
//                Thread.currentThread().interrupt();
//            } finally {
//                semaphore.release();
//            }
//            i--;
//        }
//    }


// mutex
//    void modifier() {
//
//        int i = 1000000;
//        while (i != 0) {
//            synchronized (lock) {
//                randInt.set(random.nextInt(1000));
//            }
//            i--;
//        }
//    }


//    reentrant lock
//    void modifier() {
//
//        int i = 1000000;
//        while (i != 0) {
//            reentrantLock.lock();
//            randInt.set(random.nextInt(1000));
//            reentrantLock.unlock();
//            i--;
//        }
//    }

    void modifier() {

        int i = 10;
        while (i != 0) {
            long stamp = stampedLock.writeLock();
            System.out.println("Got write lock "+ stamp + " "+ Thread.currentThread().getName());
            randInt = random.nextInt(1000);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            stampedLock.unlock(stamp);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            i--;
        }
    }


    public static void runTest() throws InterruptedException {


        final RaceCondition rc = new RaceCondition();
        Thread thread4 = new Thread(new Runnable(){
            @Override
            public void run(){
                rc.printer();
            }
        });

        Thread thread1 = new Thread(new Runnable() {

            @Override
            public void run() {
                rc.printer();
            }
        });
        Thread thread2 = new Thread(new Runnable() {

            @Override
            public void run() {
                rc.modifier();
            }
        });

        Thread thread3 = new Thread(new Runnable() {

            @Override
            public void run() {
                rc.modifier();
            }
        });


        thread1.start();
        thread2.start();
        thread3.start();
        thread4.start();

        thread1.join();
        thread2.join();
        thread3.join();
        thread4.join();
    }
}