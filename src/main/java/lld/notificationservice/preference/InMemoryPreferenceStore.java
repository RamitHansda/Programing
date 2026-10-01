package lld.notificationservice.preference;

import lld.notificationservice.model.DeliveryChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory implementation of {@link PreferenceStore}.
 * Thread-safe via {@link ConcurrentHashMap} and {@link AtomicInteger}.
 *
 * <p>Unknown users receive a default preference set: all channels enabled,
 * no DND window, no frequency cap, and no contact details registered.
 */
public final class InMemoryPreferenceStore implements PreferenceStore {

    private final Map<String, UserPreferences> store = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> dailyCounts = new ConcurrentHashMap<>();

    @Override
    public UserPreferences getPreferences(String userId) {
        return store.computeIfAbsent(userId, uid ->
                UserPreferences.builder(uid)
                        .channelPriority(java.util.List.of(
                                DeliveryChannel.PUSH,
                                DeliveryChannel.EMAIL,
                                DeliveryChannel.SMS))
                        .build());
    }

    @Override
    public void savePreferences(UserPreferences prefs) {
        store.put(prefs.getUserId(), prefs);
    }

    @Override
    public int incrementAndGetDailyCount(String userId) {
        return dailyCounts.computeIfAbsent(userId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    @Override
    public int getDailyCount(String userId) {
        AtomicInteger counter = dailyCounts.get(userId);
        return counter == null ? 0 : counter.get();
    }
}
