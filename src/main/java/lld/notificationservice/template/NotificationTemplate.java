package lld.notificationservice.template;

import lld.notificationservice.model.DeliveryChannel;

import java.util.Objects;

/**
 * A channel-specific notification template.
 *
 * <p>Templates use {@code {{variableName}}} placeholders which are substituted
 * with values from the notification payload at render time.
 * Email templates carry both a subject and a body; other channels use body only.
 */
public final class NotificationTemplate {

    private final String templateId;
    private final String notificationType; // e.g. "order_confirmed"
    private final DeliveryChannel channel;
    private final String subjectTemplate;  // nullable
    private final String bodyTemplate;

    public NotificationTemplate(String notificationType, DeliveryChannel channel,
                                String bodyTemplate) {
        this(notificationType, channel, null, bodyTemplate);
    }

    public NotificationTemplate(String notificationType, DeliveryChannel channel,
                                String subjectTemplate, String bodyTemplate) {
        this.templateId       = notificationType + ":" + channel.name().toLowerCase();
        this.notificationType = Objects.requireNonNull(notificationType, "notificationType");
        this.channel          = Objects.requireNonNull(channel, "channel");
        this.subjectTemplate  = subjectTemplate;
        this.bodyTemplate     = Objects.requireNonNull(bodyTemplate, "bodyTemplate");
    }

    public String getTemplateId()       { return templateId; }
    public String getNotificationType() { return notificationType; }
    public DeliveryChannel getChannel() { return channel; }
    public String getSubjectTemplate()  { return subjectTemplate; }
    public String getBodyTemplate()     { return bodyTemplate; }
}
