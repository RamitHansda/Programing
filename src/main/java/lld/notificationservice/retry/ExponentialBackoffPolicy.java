package lld.notificationservice.retry;

/**
 * Exponential-backoff retry policy with configurable base delay, multiplier, and max delay.
 *
 * <p>Default schedule (base=100 ms, multiplier=3, max=5 attempts, cap=5 s — demo-friendly):
 * <pre>
 *   Attempt 1 (failed) → wait 100 ms
 *   Attempt 2 (failed) → wait 300 ms
 *   Attempt 3 (failed) → wait 900 ms
 *   Attempt 4 (failed) → wait 2700 ms → capped at maxDelayMs
 *   Attempt 5 → exhausted → fallback to next channel
 * </pre>
 *
 * <p>Production schedule (base=30_000 ms, max=5 attempts, cap=3_600_000 ms / 1 hr):
 * <pre>
 *   Attempt 1 → wait 30 s
 *   Attempt 2 → wait 90 s (1.5 min)
 *   Attempt 3 → wait 270 s (4.5 min)
 *   Attempt 4 → wait 810 s (13.5 min) — or capped at 1 hr
 *   Attempt 5 → exhausted
 * </pre>
 */
public final class ExponentialBackoffPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final double multiplier;
    private final long maxDelayMs;

    /** Demo-friendly defaults: fast retries to keep the demo snappy. */
    public ExponentialBackoffPolicy() {
        this(5, 100, 3.0, 5_000);
    }

    public ExponentialBackoffPolicy(int maxAttempts, long baseDelayMs,
                                    double multiplier, long maxDelayMs) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseDelayMs  < 0) throw new IllegalArgumentException("baseDelayMs must be >= 0");
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.multiplier  = multiplier;
        this.maxDelayMs  = maxDelayMs;
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public long getDelayMs(int attemptNumber) {
        // attemptNumber is 1-based: first failure is attempt 1
        double delay = baseDelayMs * Math.pow(multiplier, attemptNumber - 1);
        return (long) Math.min(delay, maxDelayMs);
    }

    /** Returns a no-delay policy for CRITICAL priority notifications. */
    public static ExponentialBackoffPolicy immediate(int maxAttempts) {
        return new ExponentialBackoffPolicy(maxAttempts, 0, 1.0, 0);
    }
}
