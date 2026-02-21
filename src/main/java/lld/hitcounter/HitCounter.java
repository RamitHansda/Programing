package lld.hitcounter;

import java.util.concurrent.atomic.AtomicInteger;
// or
import java.util.concurrent.atomic.LongAdder;

public class HitCounter {
    private final int windowSizeInSeconds;
    private final int slotCount;
    private final AtomicInteger[] hits;  // Changed to AtomicInteger array
    private final long[] timestamps;

    public HitCounter(int windowSizeInSeconds, int slotCount) {
        this.windowSizeInSeconds = windowSizeInSeconds;
        this.slotCount = slotCount;
        this.hits = new AtomicInteger[slotCount];
        this.timestamps = new long[slotCount];

        // Initialize the AtomicInteger array
        for (int i = 0; i < slotCount; i++) {
            hits[i] = new AtomicInteger(0);
        }
    }

    // Default constructor
    public HitCounter() {
        this(300, 300); // 5-minute window with 300 slots (1 second per slot)
    }

    public synchronized void hit(long timestamp) {
        int idx = getIndex(timestamp);

        // If this is a new timestamp for this slot, reset the counter
        if (timestamps[idx] != timestamp) {
            timestamps[idx] = timestamp;
            hits[idx].set(1);
        } else {
            // Otherwise, increment the counter atomically
            hits[idx].incrementAndGet();
        }
    }

    public int getHits(long currentTimestamp) {
        int totalHits = 0;
        long cutoffTime = currentTimestamp - windowSizeInSeconds;

        for (int i = 0; i < slotCount; i++) {
            if (timestamps[i] > cutoffTime) {
                totalHits += hits[i].get();
            }
        }

        return totalHits;
    }

    // Other methods...

    private int getIndex(long timestamp) {
        return (int) (timestamp % slotCount);
    }
}