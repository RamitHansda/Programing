package lld.hitcounter;


import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HitCounterLockFree {
    private int WINDOW_SIZE;

    private static class Bucket {
        AtomicLong timestamp = new AtomicLong(0);
        AtomicInteger count = new AtomicInteger(0);
    }

    private final Bucket[] buckets;

    public HitCounterLockFree(int size) {
        this.WINDOW_SIZE = size;
        buckets = new Bucket[WINDOW_SIZE];
        for (int i = 0; i < this.WINDOW_SIZE; i++) {
            buckets[i] = new Bucket();
        }
    }

    // Record a hit at given timestamp (seconds)
    public void hit(long timestamp) {
        int index = (int) timestamp % WINDOW_SIZE;
        Bucket bucket = buckets[index];
        long bucketTime = bucket.timestamp.get();

        // Fast path: same second
        if (bucketTime == timestamp) {
            bucket.count.incrementAndGet();
            return;
        }


        if (bucket.timestamp.compareAndSet(bucketTime, timestamp)) {
            // We won CAS → reset count
            bucket.count.set(1);
        } else {
            // Lost CAS → someone else reset, just increment
            bucket.count.incrementAndGet();
        }
    }



    // Get hits in last 300 seconds
    public int getHits(long timestamp) {
        int total = 0;
        for (Bucket bucket : buckets) {
            if (timestamp - bucket.timestamp.get() < WINDOW_SIZE) {
                total += bucket.count.get();
            }
        }
        return total;
    }

}
