package lld.designpatterns.chainofresponsibility;

import java.util.Objects;

/**
 * Chain of Responsibility: handler that can process a log message or pass to next.
 */
public abstract class LogHandler {

    private LogHandler next;

    public final void setNext(LogHandler next) {
        this.next = next;
    }

    public final void handle(LogMessage message) {
        if (handleMessage(message)) {
            return;
        }
        if (next != null) {
            next.handle(message);
        }
    }

    /**
     * @return true if the message was fully handled (chain stops), false to pass to next.
     */
    protected abstract boolean handleMessage(LogMessage message);

    public record LogMessage(Level level, String source, String text, long timestampMs) {
        public LogMessage {
            Objects.requireNonNull(level);
            Objects.requireNonNull(text);
        }
        public enum Level { DEBUG, INFO, WARN, ERROR }
    }
}
