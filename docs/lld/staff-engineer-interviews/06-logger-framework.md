# LLD: Logger framework (handlers, levels, context)

## Interview prompt

Design a **logging library** similar in spirit to Logback/Log4j2: levels, multiple appenders, formatting, and filtering.

## Clarifying questions

- **Sync vs async**: async implies a bounded queue + drop policy—state explicitly.
- **Context**: MDC-style thread-local vs explicit `LogEvent` fields?
- **Extensibility**: third-party appenders?

## Functional requirements

- Levels: TRACE … ERROR (define ordering).
- Route events through **filters** and to **appenders**.
- Support **named loggers** and optional hierarchical effective level.

## Non-functional requirements

- **Hot path performance**: avoid string concatenation if disabled (`Supplier<String>` lazy message).
- **Failure isolation**: one broken appender must not disable others.

## Design patterns

| Pattern | Role |
|--------|------|
| **Chain of Responsibility** | Ordered filters (`LevelFilter`, `RegexFilter`). |
| **Observer** (conceptual) | Appenders “observe” events; implemented as list dispatch. |
| **Decorator** | `FormattingAppender` wraps base sink. |

## Staff-level structure

- `LogEvent` immutable: timestamp, level, logger name, message supplier, throwable, MDC map.
- `Logger` is a thin facade; heavy lifting in `LoggerEngine` (pipeline).

## Java API sketch

```java
public interface LogFilter {
    boolean accept(LogEvent e);
}

public interface LogAppender {
    void append(LogEvent e);
}

public final class LoggerPipeline {
    private final List<LogFilter> filters;
    private final List<LogAppender> appenders;

    public void publish(LogEvent e) {
        for (LogFilter f : filters) if (!f.accept(e)) return;
        for (LogAppender a : appenders) {
            try { a.append(e); } catch (Exception ex) { /* isolate */ }
        }
    }
}
```

## Failure modes

- Recursive logging from appenders: **guard** with reentrancy flag or route errors to `System.err`.
- Async overflow: **block**, **drop**, or **coalesce**—pick and justify.

## Testing strategy

- Fake appenders capturing events.
- Filter ordering tests.
- Appender exception does not prevent next appender.

## Follow-ups

- Sampling, rate-limited error logging, structured JSON layout.
