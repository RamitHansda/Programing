package threads.stampedLock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.StampedLock;

public class StampedLockDemo1 {
    public static void main( String args[] ) {

        ExecutorService executorService = Executors.newFixedThreadPool(10);

        // create an instance of StampedLock
        StampedLock stampedLock = new StampedLock();

        // optimistic read
        stampedLock.tryOptimisticRead();

        // outputs "read lock count 0 is read locked false"
        System.out.println("read lock count " + stampedLock.getReadLockCount() + " is read locked " + stampedLock.isReadLocked());
    }
}
