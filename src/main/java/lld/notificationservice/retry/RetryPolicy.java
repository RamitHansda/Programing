package lld.notificationservice.retry;

/**
 * Defines the retry schedule for soft-failed dispatch attempts.
 *
 * <p>Implementations control maximum attempts and the inter-attempt delay,
 * enabling the orchestrator to switch between exponential backoff for normal
 * notifications and zero-delay retries for critical ones.
 */
public interface RetryPolicy {

    /** Maximum number of dispatch attempts (including the first). */
    int getMaxAttempts();

    /**
     * Delay in milliseconds before the next attempt.
     *
     * @param attemptNumber the attempt that just failed (1-indexed)
     */
    long getDelayMs(int attemptNumber);
}
