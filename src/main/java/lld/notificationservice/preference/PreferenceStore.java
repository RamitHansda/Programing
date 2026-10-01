package lld.notificationservice.preference;

import java.util.Optional;

/**
 * Reads and writes user notification preferences.
 *
 * <p>In production this wraps a read-through Redis cache (5-min TTL)
 * backed by a MySQL / DynamoDB source of truth.
 */
public interface PreferenceStore {

    /**
     * Retrieve preferences for a user. Returns a sensible default if the user
     * has no explicit preferences recorded.
     */
    UserPreferences getPreferences(String userId);

    /** Persist updated preferences for a user. Invalidates any cache entry. */
    void savePreferences(UserPreferences prefs);

    /**
     * Atomically increment the notification counter for a user within the current UTC day.
     * Returns the new counter value, which the caller compares against the cap.
     */
    int incrementAndGetDailyCount(String userId);

    /** Returns the current daily notification count for a user. */
    int getDailyCount(String userId);
}
