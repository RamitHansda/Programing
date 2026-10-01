package lld.notificationservice.delivery;

import lld.notificationservice.model.DeliveryAttempt;
import lld.notificationservice.model.DeliveryChannel;

import java.util.List;

/**
 * Persists and queries per-attempt delivery records.
 *
 * <p>In production this is backed by Cassandra or DynamoDB — partitioned on
 * {@code (user_id, date)} for time-range queries — with writes flowing through
 * a Kafka-based async batch sink to avoid direct write pressure from dispatchers.
 */
public interface DeliveryStore {

    /** Record a delivery attempt (idempotent on attempt id). */
    void saveAttempt(DeliveryAttempt attempt);

    /** All attempts for a notification, ordered by attempt number. */
    List<DeliveryAttempt> getAttempts(String notificationId);

    /** All attempts for a notification on a specific channel, ordered by attempt number. */
    List<DeliveryAttempt> getAttempts(String notificationId, DeliveryChannel channel);
}
