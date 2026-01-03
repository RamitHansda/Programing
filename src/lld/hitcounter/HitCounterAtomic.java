package lld.hitcounter;


import java.util.concurrent.atomic.AtomicReferenceArray;

public class HitCounterAtomic {
    private final int WINDOW_SIZE;

    // Immutable class to represent a bucket state
    private static final class BucketState {
        final long timestamp;
        final int count;

        BucketState(long timestamp, int count) {
            this.timestamp = timestamp;
            this.count = count;
        }

        // Create a new state with the same timestamp but incremented count
        BucketState incrementCount() {
            return new BucketState(this.timestamp, this.count + 1);
        }

        // Create a new state with a new timestamp and count 1
        static BucketState createNew(long timestamp) {
            return new BucketState(timestamp, 1);
        }
    }

    private final AtomicReferenceArray<BucketState> buckets;

    public HitCounterAtomic(int size) {
        this.WINDOW_SIZE = size;
        buckets = new AtomicReferenceArray<>(WINDOW_SIZE);
        // Initialize all buckets with timestamp 0 and count 0
        for (int i = 0; i < WINDOW_SIZE; i++) {
            buckets.set(i, new BucketState(0, 0));
        }
    }

    public void hit(long timestamp) {
        int index = (int) (timestamp % WINDOW_SIZE);

        while (true) {
            // Get the current state
            BucketState currentState = buckets.get(index);

            // Determine the new state
            BucketState newState;
            if (currentState.timestamp == timestamp) {
                // Same timestamp - increment count
                newState = currentState.incrementCount();
            } else {
                // New timestamp - reset bucket
                newState = BucketState.createNew(timestamp);
            }

            // Try to update atomically
            if (buckets.compareAndSet(index, currentState, newState)) {
                // Success! We're done
                return;
            }
            // Otherwise, someone else updated the bucket - retry
        }
    }

    public int getHits(long currentTimestamp) {
        int total = 0;
        long cutoffTime = currentTimestamp - WINDOW_SIZE;

        for (int i = 0; i < WINDOW_SIZE; i++) {
            // Get a consistent snapshot of the bucket
            BucketState state = buckets.get(i);

            // Check if it's within our time window
            if (state.timestamp > cutoffTime) {
                total += state.count;
            }
        }

        return total;
    }
}