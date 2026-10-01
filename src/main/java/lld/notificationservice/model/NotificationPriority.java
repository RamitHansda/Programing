package lld.notificationservice.model;

/**
 * Business priority of a notification.
 * CRITICAL and HIGH notifications bypass frequency caps and DND windows;
 * CRITICAL additionally skips retry delays and triggers immediate fallback.
 */
public enum NotificationPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}
