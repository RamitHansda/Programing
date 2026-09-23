package lld.filesystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Absolute path value object. Parses and normalizes segments, including {@code .} and {@code ..}.
 */
public final class Path {
    private final List<String> segments;

    private Path(List<String> segments) {
        this.segments = List.copyOf(segments);
    }

    public static Path root() {
        return new Path(List.of());
    }

    /**
     * Parse an absolute or relative path string into normalized segments relative to {@code base}.
     * Absolute paths ignore {@code base}. Empty string means {@code base} unchanged.
     */
    public static Path resolve(Path base, String raw) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(raw, "path");

        List<String> stack = new ArrayList<>(base.segments);
        boolean absolute = raw.startsWith("/");
        if (absolute) {
            stack.clear();
        }

        if (raw.isEmpty() || "/".equals(raw)) {
            return new Path(stack);
        }

        String trimmed = absolute ? raw.substring(1) : raw;
        if (trimmed.isEmpty()) {
            return new Path(stack);
        }

        for (String part : trimmed.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                // At root, ".." is a no-op (unix-like).
                continue;
            }
            stack.add(part);
        }
        return new Path(stack);
    }

    public static Path ofAbsolute(String absolutePath) {
        if (absolutePath == null || !absolutePath.startsWith("/")) {
            throw new IllegalArgumentException("expected absolute path starting with /");
        }
        return resolve(root(), absolutePath);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public List<String> segments() {
        return segments;
    }

    public Path parent() {
        if (segments.isEmpty()) {
            return this;
        }
        return new Path(segments.subList(0, segments.size() - 1));
    }

    public String leafName() {
        if (segments.isEmpty()) {
            return "/";
        }
        return segments.get(segments.size() - 1);
    }

    public Path child(String name) {
        List<String> next = new ArrayList<>(segments);
        next.add(name);
        return new Path(next);
    }

    /** Absolute path string, always starting with {@code /}. */
    public String toAbsolutePath() {
        if (segments.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", segments);
    }

    @Override
    public String toString() {
        return toAbsolutePath();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Path other)) {
            return false;
        }
        return segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }
}
