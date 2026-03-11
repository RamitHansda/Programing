package interview.fampay.ratelimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Thread-safe, per-user token bucket rate limiter.
 *
 * <p>Each user gets an independent bucket that refills to {@code maxTokens} every
 * {@code refillPeriodMs} milliseconds. Partial periods are preserved — time
 * progress is never lost between refills. Buckets for users who have been idle
 * longer than {@code bucketTtlMs} are evicted automatically to prevent unbounded
 * memory growth.</p>
 *
 * <p>This class implements {@link AutoCloseable}. Always close it (or use
 * try-with-resources) to release the background eviction thread.</p>
 */
public class TokenBucketRateLimiter implements RateLimiter, AutoCloseable {

    private static final Logger log = Logger.getLogger(TokenBucketRateLimiter.class.getName());

    private static final long DEFAULT_REFILL_PERIOD_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long DEFAULT_BUCKET_TTL_MS    = TimeUnit.MINUTES.toMillis(10);

    private static class TokenBucket {
        private long availableTokens;
        private long lastRefillTime;
        // Read by the eviction thread without holding the bucket lock — must be volatile.
        private volatile long lastAccessTime;

        TokenBucket(long tokens, long now) {
            this.availableTokens = tokens;
            this.lastRefillTime  = now;
            this.lastAccessTime  = now;
        }
    }

    private final int maxTokens;
    private final long refillPeriodMs;
    private final long bucketTtlMs;
    private final ConcurrentHashMap<String, TokenBucket> userBuckets;
    private final ScheduledExecutorService evictionScheduler;

    /** Refills to {@code maxTokens} every second; evicts idle buckets after 10 minutes. */
    public TokenBucketRateLimiter(int maxTokens) {
        this(maxTokens, DEFAULT_REFILL_PERIOD_MS, DEFAULT_BUCKET_TTL_MS);
    }

    /** Refills to {@code maxTokens} every {@code refillPeriodMs}; evicts idle buckets after 10 minutes. */
    public TokenBucketRateLimiter(int maxTokens, long refillPeriodMs) {
        this(maxTokens, refillPeriodMs, DEFAULT_BUCKET_TTL_MS);
    }

    public TokenBucketRateLimiter(int maxTokens, long refillPeriodMs, long bucketTtlMs) {
        if (maxTokens <= 0)      throw new IllegalArgumentException("maxTokens must be > 0, got: " + maxTokens);
        if (refillPeriodMs <= 0) throw new IllegalArgumentException("refillPeriodMs must be > 0, got: " + refillPeriodMs);
        if (bucketTtlMs <= 0)    throw new IllegalArgumentException("bucketTtlMs must be > 0, got: " + bucketTtlMs);

        this.maxTokens      = maxTokens;
        this.refillPeriodMs = refillPeriodMs;
        this.bucketTtlMs    = bucketTtlMs;
        this.userBuckets    = new ConcurrentHashMap<>();

        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-bucket-eviction");
            t.setDaemon(true);
            return t;
        });
        this.evictionScheduler.scheduleAtFixedRate(
                this::evictStaleBuckets, bucketTtlMs, bucketTtlMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns {@code true} if the request for {@code userId} is within the rate limit,
     * {@code false} if the bucket is empty.
     *
     * @throws IllegalArgumentException if {@code userId} is null or blank
     */
    @Override
    public boolean request(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }

        long now = System.currentTimeMillis();
        TokenBucket bucket = userBuckets.computeIfAbsent(userId, k -> new TokenBucket(maxTokens, now));

        synchronized (bucket) {
            refill(bucket);
            bucket.lastAccessTime = now;
            if (bucket.availableTokens > 0) {
                bucket.availableTokens--;
                return true;
            }
            return false;
        }
    }

    /**
     * Must be called within a {@code synchronized(bucket)} block.
     * Adds tokens for each full period elapsed while preserving the partial-period
     * remainder so that accumulated time is never lost.
     */
    private void refill(TokenBucket bucket) {
        long now           = System.currentTimeMillis();
        long elapsed       = now - bucket.lastRefillTime;
        long periodsElapsed = elapsed / refillPeriodMs;

        if (periodsElapsed > 0) {
            long tokensToAdd = Math.min((long) periodsElapsed * maxTokens, maxTokens);
            bucket.availableTokens = Math.min(bucket.availableTokens + tokensToAdd, maxTokens);
            // Advance by whole periods only — preserves the partial period remainder.
            bucket.lastRefillTime += periodsElapsed * refillPeriodMs;
        }
    }

    private void evictStaleBuckets() {
        long cutoff = System.currentTimeMillis() - bucketTtlMs;
        int removed = 0;
        for (var entry : userBuckets.entrySet()) {
            if (entry.getValue().lastAccessTime < cutoff) {
                userBuckets.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            final int evicted = removed;
            log.fine(() -> String.format("Evicted %d stale bucket(s); active=%d", evicted, userBuckets.size()));
        }
    }

    /** Shuts down the background eviction thread. */
    @Override
    public void close() {
        evictionScheduler.shutdown();
    }
}
