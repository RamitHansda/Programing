package lld.notificationservice.model;

import java.util.Objects;

/**
 * Channel-specific rendered notification content produced by the {@link lld.notificationservice.template.TemplateEngine}.
 * Email uses both subject and body; push/SMS/webhook use only body.
 */
public final class RenderedContent {

    private final String subject; // nullable; used for email
    private final String body;

    public RenderedContent(String body) {
        this(null, body);
    }

    public RenderedContent(String subject, String body) {
        this.subject = subject;
        this.body    = Objects.requireNonNull(body, "body");
    }

    public String getSubject() { return subject; }
    public String getBody()    { return body; }

    @Override
    public String toString() {
        return subject != null
                ? "RenderedContent{subject='" + subject + "', body='" + body + "'}"
                : "RenderedContent{body='" + body + "'}";
    }
}
