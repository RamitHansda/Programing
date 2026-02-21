package threads.stampedLock;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.StampedLock;

class StampedLockOptimisticDemo {

    static StampedLock stampedLock = new StampedLock();

    public static void main( String args[] ) {
        int[] array = new int[3];
        array[0] = 3;
        array[1] = 5;
        array[2] = 7;

        ReadWriteLock lock= stampedLock.asReadWriteLock();
        lock.readLock().lock();
        productOfThree(array);
    }

    static int productOfThree(int[] array) {

        // get a stamp from optimistic read
        long stamp = stampedLock.tryOptimisticRead();

        // read the three elements of the array in local variables
        int num1 = array[0];
        int num2 = array[1];
        int num3 = array[2];

        // if stamp isn't valid anymore i.e. a write lock was acquired then
        // get the read lock

        if (!stampedLock.validate(stamp)) {

            // this call may block
            stamp = stampedLock.readLock();

            try {
                return array[0] * array[1] * array[3];
            } catch (RuntimeException re) {
                // log exception
                throw re;
            } finally {
                // remember to unlock in finally block
                stampedLock.unlockRead(stamp);
            }
        }

        // assuming the multiplication doesn't result in an overflow exception
        return num1 * num2 * num3;
    }
}