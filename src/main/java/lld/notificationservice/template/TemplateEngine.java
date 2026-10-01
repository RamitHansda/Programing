package lld.notificationservice.template;

import lld.notificationservice.model.RenderedContent;

import java.util.Map;

/**
 * Renders a {@link NotificationTemplate} by substituting payload variables.
 * Implementations may cache compiled templates to amortise parsing overhead.
 */
public interface TemplateEngine {

    /**
     * Render the given template using the provided variable map.
     *
     * @param template the template to render
     * @param variables key-value pairs supplied by the producer's payload
     * @return rendered subject (if any) and body
     */
    RenderedContent render(NotificationTemplate template, Map<String, Object> variables);
}
