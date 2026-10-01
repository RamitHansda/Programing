package lld.notificationservice;

import lld.notificationservice.model.DeliveryAttempt;
import lld.notificationservice.model.NotificationRequest;
import lld.notificationservice.model.NotificationStatus;

import java.util.List;

/**
 * Primary entry point for sending notifications.
 *
 * <p>Responsibilities (single-method facade):
 * <ol>
 *   <li>Idempotency check — absorb duplicate producer retries.</li>
 *   <li>Preference enrichment — load channel order, DND window, frequency cap.</li>
 *   <li>Gate checks — DND and frequency cap.</li>
 *   <li>Dispatch with retry and multi-channel fallback.</li>
 *   <li>Delivery recording — every attempt persisted to the delivery store.</li>
 * </ol>
 */
public interface NotificationService {

    /**
     * Accept and dispatch a notification request.
     *
     * @param request producer-provided notification payload
     * @return response containing the notification id and its outcome status
     */
    NotificationResponse send(NotificationRequest request);

    /**
     * Retrieve all recorded delivery attempts for a notification.
     * Useful for audit trails and debugging delivery failures.
     */
    List<DeliveryAttempt> getDeliveryHistory(String notificationId);

    /**
     * Return the current status of a notification.
     * Returns {@code null} if no notification with that id exists.
     */
    NotificationStatus getStatus(String notificationId);
}
