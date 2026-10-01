package lld.notificationservice.template;

import lld.notificationservice.model.RenderedContent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code {{key}}} Mustache-style template engine.
 *
 * <p>Scans the template string for {@code {{variableName}}} tokens and replaces
 * each with the corresponding value from the payload map. Unknown keys are left
 * as-is so callers can detect un-satisfied placeholders.
 *
 * <p>In production this would delegate to the Mustache.java or Handlebars.java
 * library with compiled template caching keyed on {@code templateId}.
 */
public final class SimpleTemplateEngine implements TemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    @Override
    public RenderedContent render(NotificationTemplate template, Map<String, Object> variables) {
        String subject = template.getSubjectTemplate() != null
                ? substitute(template.getSubjectTemplate(), variables) : null;
        String body = substitute(template.getBodyTemplate(), variables);
        return new RenderedContent(subject, body);
    }

    private static String substitute(String text, Map<String, Object> variables) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key   = m.group(1);
            Object value = variables.get(key);
            m.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value.toString()) : m.group(0));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
