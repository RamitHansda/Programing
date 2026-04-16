# LLD: Logger framework (handlers, levels, context)

## Interview-ready snapshot

**Say first (≈30s):** Immutable **`LogEvent`** flows through **filter chain** then **fan-out appenders**; broken appender must not kill pipeline; lazy message with `Supplier` on hot path.

**Default assumptions:** Sync dispatch first; mention async queue + drop policy only if they ask.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | MDC model; async or not; hierarchy of levels. |
| Model | 8 min | LogEvent, Logger, filters, appenders, pipeline engine. |
| API + flow | 8 min | `publish` path sketch; level ordering table. |
| Hard | 12 min | Reentrant logging; exception isolation per appender; optional async overflow. |
| Close | 5 min | JSON layout as decorator; sampling as filter. |

**Whiteboard order:** (1) LogEvent fields (2) filter chain (3) appender list (4) isolation try/catch boundary (5) optional async box.

**Likely probes:** Child logger effective level? What if appender calls logger?

**30s closer:** Chain of Responsibility for filters; pipeline owns policy; appenders are ports with strict failure containment.

---

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

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Value object** | `LogEvent` | Immutable snapshot: time, level, logger name, message supplier, throwable, MDC map. |
| **Entity** | `Logger` (named) | Effective level, reference to `LoggerEngine` / parent for hierarchy. |
| **Policy objects** | `LogFilter` | Predicate on `LogEvent` (chain). |
| **Infrastructure ports** | `LogAppender` | Side effect sink; not domain in strict DDD, but core **model** of the library. |
| **Facade** | `LoggerPipeline` / `LoggerEngine` | Wires filters + appenders; applies isolation and reentrancy guards. |

**Relationships:** many `Logger`s may share one pipeline configuration; each `publish` creates one `LogEvent`.

**Not modeled:** log rotation files on disk (OS), remote syslog UDP (network stack).

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
