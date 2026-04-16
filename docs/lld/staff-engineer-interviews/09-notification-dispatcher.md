# LLD: Notification dispatcher (multi-channel)

## Interview-ready snapshot

**Say first (≈30s):** Build **`Notification`** value, apply **user prefs + policies**, fan out to **`NotificationChannel` strategies** (email/SMS/push); **isolate** provider failures; **Adapter** hides SDKs.

**Default assumptions:** At-least-once or best-effort—state which; dedupe key if retries.

| Phase | ~Time | Deliver |
|-------|------|---------|
| Align | 5 min | Delivery guarantee; PII in logs; templating locale. |
| Model | 8 min | Notification VO, preferences, channels, dispatcher, results. |
| API + flow | 8 min | dispatch one event; show channel loop + try/catch policy. |
| Hard | 12 min | Partial failure visibility; idempotency key per channel; backoff stub. |
| Close | 5 min | Async queue + worker pool as scale-out note. |

**Whiteboard order:** (1) input event (2) rendered Notification (3) channel interface (4) fan-out (5) failure isolation.

**Likely probes:** Quiet hours? Rate limit per provider? Ordering?

**30s closer:** Rendering pure; transport behind ports; dispatcher owns orchestration and failure policy.

---

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

## Domain model

| Kind | Type | Responsibility |
|------|------|----------------|
| **Value object** | `Notification` / `NotificationPayload` | Channel-agnostic content + metadata (locale, dedupe key, severity). |
| **Entity** | `UserPreferences` | Opt-in channels, quiet hours, locale. |
| **Port** | `NotificationChannel` | Email/SMS/push capability; maps domain notification to provider wire format in adapter layer. |
| **Domain service** | `NotificationDispatcher` | Selects channels, applies policy, invokes ports, records outcomes. |
| **Value object** | `DeliveryId`, `DeliveryResult` | Correlation and status for idempotent retries. |

**Relationships:** dispatcher **reads** `UserPreferences`, **fans out** to N channels for one logical event.

**Not modeled:** SendGrid HTTP client details (behind adapter).

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
