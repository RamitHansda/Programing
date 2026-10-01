package lld.notificationservice.channel;

/**
 * Outcome classification returned by a {@link ChannelDispatcher}.
 *
 * <ul>
 *   <li>{@code SUCCESS}   — provider accepted the message.</li>
 *   <li>{@code HARD_FAIL} — permanent error (stale token, opted-out number, bad endpoint).
 *                          No retry; move immediately to the next fallback channel.</li>
 *   <li>{@code SOFT_FAIL} — transient error (rate limit, 5xx, network timeout).
 *                          Subject to exponential-backoff retries before fallback.</li>
 * </ul>
 */
public enum FailureType {
    SUCCESS,
    HARD_FAIL,
    SOFT_FAIL
}
