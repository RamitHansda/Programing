package lld.jenkinslike;

import java.time.Instant;

/**
 * A timestamped log line emitted during build execution.
 */
public record BuildLogEntry(Instant timestamp, String message) {
    public BuildLogEntry {
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
