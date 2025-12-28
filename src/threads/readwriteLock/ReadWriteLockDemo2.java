package threads.readwriteLock;


import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class ReadWriteLockDemo2 {

    static Random random = new Random();
    static boolean isDataFresh = true;

    public static void main( String args[] ) throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(15);

        // cache
        HashMap<String, Object> cache = new HashMap<>();
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        // put some data in the cache
        cache.put("key", -1);

        Runnable writerTask = new Runnable() {
            @Override
            public void run() {
                writerThread(lock);
            }
        };

        Runnable readerTask = new Runnable() {
            @Override
            public void run() {
                readerThread(cache, lock);
            }
        };

        try {
            Future future1 = es.submit(writerTask);
            Future future2 = es.submit(readerTask);
            Future future3 = es.submit(readerTask);
            Future future4 = es.submit(readerTask);

            future1.get();
            future2.get();
            future3.get();
            future4.get();
        } finally {
            es.shutdown();
        }
    }

    static void writerThread(ReadWriteLock lock) {

        for (int i = 0; i < 9; i++) {
            try {
                Thread.sleep(random.nextInt(50));
            } catch (InterruptedException ie) {
                // ignore
            }

            lock.writeLock().lock();
            System.out.println("Acquired write lock");
            isDataFresh = false;
            lock.writeLock().unlock();
        }
    }

    static void updateData(HashMap<String, Object> cache) {
        cache.put("key", random.nextInt(1000));
        isDataFresh = true;
    }

    static void readerThread(HashMap<String, Object> cache, ReadWriteLock lock) {

        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(random.nextInt(50));
            } catch (InterruptedException ie) {
                // ignore
            }

            // acquire the read lock to check if data is fresh before
            // reading from the cache
            lock.readLock().lock();

            try {
                // check if the data is fresh
                if (!isDataFresh) {

                    // release the read lock, before acquiring the write lock
                    lock.readLock().unlock();

                    // acquire the write lock before triggering an update
                    lock.writeLock().lock();

                    try {

                        // Check the flag again, the data might already have been refreshed by
                        // another writer thread.
                        if (!isDataFresh) {
                            updateData(cache);
                        }

                        // acquire read lock before releasing the write lock. This is an
                        // example of downgrading from write -> read lock
                        lock.readLock().lock();
                    } finally {
                        lock.writeLock().unlock();
                    }
                }

                System.out.println("Acquire read lock and reading key = " + cache.get("key"));

            } finally {
                lock.readLock().unlock();
            }
        }
    }
}
