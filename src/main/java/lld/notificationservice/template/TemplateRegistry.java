package lld.notificationservice.template;

import lld.notificationservice.model.DeliveryChannel;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of {@link NotificationTemplate}s, keyed on
 * {@code notificationType + ":" + channel}.
 *
 * <p>Templates are loaded at startup (and refreshed via Redis Pub/Sub invalidation in
 * production). The registry provides a fallback lookup chain so a missing channel-specific
 * template degrades gracefully.
 */
public final class TemplateRegistry {

    private final Map<String, NotificationTemplate> templates = new ConcurrentHashMap<>();

    /** Register a template. Overwrites any previously registered template for the same key. */
    public void register(NotificationTemplate template) {
        templates.put(template.getTemplateId(), template);
    }

    /**
     * Look up a template for the given notification type and channel.
     *
     * @return the matching template, or empty if none registered
     */
    public Optional<NotificationTemplate> find(String notificationType, DeliveryChannel channel) {
        String key = notificationType + ":" + channel.name().toLowerCase();
        return Optional.ofNullable(templates.get(key));
    }

    /**
     * Returns a fallback plain-text template when no registered template matches.
     * Prevents hard failures for unconfigured notification types during development.
     */
    public NotificationTemplate fallback(String notificationType, DeliveryChannel channel) {
        return new NotificationTemplate(notificationType, channel,
                "Notification: " + notificationType,
                "You have a new notification of type " + notificationType + ".");
    }
}
