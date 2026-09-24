package lld.notificationservice.delivery;

import lld.notificationservice.model.DeliveryAttempt;
import lld.notificationservice.model.DeliveryChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe in-memory implementation of {@link DeliveryStore}. */
public final class InMemoryDeliveryStore implements DeliveryStore {

    // notificationId → list of attempts (append-only)
    private final Map<String, CopyOnWriteArrayList<DeliveryAttempt>> store = new ConcurrentHashMap<>();

    @Override
    public void saveAttempt(DeliveryAttempt attempt) {
        store.computeIfAbsent(attempt.getNotificationId(), k -> new CopyOnWriteArrayList<>())
             .add(attempt);
    }

    @Override
    public List<DeliveryAttempt> getAttempts(String notificationId) {
        List<DeliveryAttempt> list = store.getOrDefault(notificationId, new CopyOnWriteArrayList<>());
        List<DeliveryAttempt> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(DeliveryAttempt::getAttemptNumber));
        return Collections.unmodifiableList(sorted);
    }

    @Override
    public List<DeliveryAttempt> getAttempts(String notificationId, DeliveryChannel channel) {
        return getAttempts(notificationId).stream()
                .filter(a -> a.getChannel() == channel)
                .toList();
    }
}
