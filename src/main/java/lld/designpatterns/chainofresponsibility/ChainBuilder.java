package lld.designpatterns.chainofresponsibility;

import java.util.Objects;

/**
 * Fluent assembly of a {@link LogHandler} chain.
 * Prefer this over manual {@code setNext} calls when building multi-handler pipelines.
 */
public final class ChainBuilder {

    private final LogHandler head;
    private LogHandler tail;

    private ChainBuilder(LogHandler first) {
        this.head = Objects.requireNonNull(first, "first");
        this.tail = first;
    }

    public static ChainBuilder start(LogHandler first) {
        return new ChainBuilder(first);
    }

    public ChainBuilder then(LogHandler next) {
        Objects.requireNonNull(next, "next");
        tail.setNext(next);
        tail = next;
        return this;
    }

    public LogHandler build() {
        return head;
    }
}
