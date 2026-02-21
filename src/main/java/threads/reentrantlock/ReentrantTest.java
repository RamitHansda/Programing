package threads.reentrantlock;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReentrantTest {

    public static void main(String[] args) {
        test();
    }
    static ReentrantLock lock = new ReentrantLock();
    ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    static int test() {

        lock.lock();

        try {
            return -1;
        } finally {
            lock.unlock();
            System.out.println("Unlocked");
        }

        //System.out.println("Exiting Program");
    }
}