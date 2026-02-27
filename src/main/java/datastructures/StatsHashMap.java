package datastructures;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map that delegates to {@link java.util.concurrent.ConcurrentHashMap}
 * and tracks get-call volume: number of get calls in the last 5 minutes and average
 * get QPS over that window. Uses a fixed-size ring of 1-second buckets (300 buckets)
 * so memory and per-get cost are O(1) even under very high request rates.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class StatsHashMap<K, V> {

    private static final int WINDOW_SECONDS = 5 * 60;

    private final Map<K, V> map = new ConcurrentHashMap<>();
    // Ring of 1-second buckets: bucket i holds count for second (bucketSecond[i])
    private final long[] bucketSecond = new long[WINDOW_SECONDS];
    private final int[] bucketCount = new int[WINDOW_SECONDS];
    private final Object statsLock = new Object();

    public V put(K key, V value) {
        return map.put(key, value);
    }

    public V get(K key) {
        long nowSec = System.currentTimeMillis() / 1000;
        int idx = (int) (nowSec % WINDOW_SECONDS);
        synchronized (statsLock) {
            if (bucketSecond[idx] != nowSec) {
                bucketSecond[idx] = nowSec;
                bucketCount[idx] = 0;
            }
            bucketCount[idx]++;
        }
        return map.get(key);
    }

    /**
     * Returns the number of get calls that occurred in the last 5 minutes.
     */
    public int getGetCountLast5Mins() {
        long nowSec = System.currentTimeMillis() / 1000;
        long cutoff = nowSec - WINDOW_SECONDS;
        synchronized (statsLock) {
            int total = 0;
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (bucketSecond[i] >= cutoff) {
                    total += bucketCount[i];
                }
            }
            return total;
        }
    }

    /**
     * Returns the average get QPS (queries per second) over the last 5 minutes.
     * Equals (get calls in last 5 mins) / 300.0. Returns 0 if no gets in that window.
     */
    public double getAvgQPS() {
        int count = getGetCountLast5Mins();
        return count == 0 ? 0.0 : (double) count / WINDOW_SECONDS;
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }
}
