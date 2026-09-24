package lld.notificationservice.model;

/** Lifecycle status of a single notification across all delivery attempts. */
public enum NotificationStatus {
    /** Accepted by the service; no dispatch attempted yet. */
    PENDING,
    /** At least one channel attempt succeeded. */
    DELIVERED,
    /** All channel attempts failed after max retries; escalated to DLQ. */
    UNDELIVERABLE,
    /** Held due to DND window; will be delivered when the window ends. */
    HELD,
    /** Dropped because the user's daily frequency cap was exceeded. */
    DROPPED
}
