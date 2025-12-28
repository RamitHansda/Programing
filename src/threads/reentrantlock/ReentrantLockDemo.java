package threads.reentrantlock;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class ReentrantLockDemo {
    public static void main( String args[] ) throws Exception {

        ExecutorService es = Executors.newFixedThreadPool(5);
        ReentrantLock lock = new ReentrantLock();
        Runnable threadA = new Runnable() {
            @Override
            public void run() {
                threadA(lock);
            }
        };

        Runnable threadB = new Runnable() {
            @Override
            public void run() {
                threadB(lock);
            }
        };

        try {
            lock.lock();
            lock.lock();
            lock.lock();

            System.out.println("Main thread lock hold count = " + lock.getHoldCount());

            // submit other threads
            Future future1 = es.submit(threadA);
            Future future2 = es.submit(threadB);

            // release locks slowly
            for (int i = 0; i < 3; i++) {
                Thread.sleep(50);
                lock.unlock();
            }

            System.out.println("Main thread released lock. Lock hold count = " + lock.getHoldCount());
            future1.get();
            future2.get();
        } finally {
            // Make sure to release the locks if an exception occurs
            for (int i = 0; i < lock.getHoldCount(); i++) {
                lock.unlock();
            }

            // Shutdown the executor service
            es.shutdown();
        }
    }

    static void threadB(Lock lock) {
        lock.lock();
        lock.unlock();
    }

    static void threadA(ReentrantLock lock) {

        String name = "THREAD-A";
        Thread.currentThread().setName(name);
        boolean keepTrying = true;

        System.out.println("Is lock owned by any other thread = " + lock.isLocked());

        while (keepTrying) {
            System.out.println(name + " trying to acquire lock");


            if (lock.tryLock()) {
                try {
                    System.out.println(name + " acquired lock");
                    keepTrying = false;
                } finally {
                    lock.unlock();
                    System.out.println(name + " released lock");
                }
            } else {
                System.out.println(name + " failed to acquire lock. Other threads waiting = " + lock.getQueueLength());
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                // ignore exception.
            }
        }
    }
}