# LLD: Notification dispatcher (multi-channel)

## Interview prompt

Design a component that sends **notifications** across channels (email, SMS, push). Users choose preferences; templates vary by event type.

## Clarifying questions

- **Delivery guarantees**: at-most-once vs at-least-once with dedupe keys?
- **Templating**: static strings vs localized templates?
- **PII**: redaction in logs, encryption at rest—mention boundary.

## Functional requirements

- `dispatch(userId, eventType, payload)` chooses channels from preferences.
- Render template per channel constraints (SMS length).
- Record delivery status (queued/sent/failed) if in scope.

## Non-functional requirements

- **Provider isolation**: Twilio outage should not break email path.
- **Backoff / retry** policy per provider (even if stubbed).

## Design patterns

| Pattern | Role |
|--------|------|
| **Strategy** | `NotificationChannel` implementations: email/sms/push. |
| **Template Method** | Shared pipeline: build `Notification` → validate → send → record. |
| **Adapter** | Wrap third-party SDKs behind your `MailSender` interface. |
| **Observer / event bus** (optional) | Internal domain events trigger dispatch asynchronously. |

## Staff-level boundaries

- **Rendering** is pure (easy tests).
- **Transport** is I/O (fake in unit tests).
- **Policy** (preferences, quiet hours) is its own service.

## Java sketch

```java
public interface NotificationChannel {
    boolean supports(Notification n);
    DeliveryResult send(Notification n);
}

public final class NotificationDispatcher {
    private final List<NotificationChannel> channels;
    public void dispatch(Notification n) {
        for (NotificationChannel c : channels) {
            if (!c.supports(n)) continue;
            try { c.send(n); } catch (Exception e) { /* isolate + metrics */ }
        }
    }
}
```

## Failure modes

- **Partial sends**: define per-channel failure visibility; consider idempotency keys per `(userId, eventId, channel)`.

## Testing strategy

- Fake channels + spy metrics.
- Template snapshot tests for localization placeholders.

## Follow-ups

- **Batching**, rate limits per provider, **priority queue** for incidents.
