# Notification Service — Low-Level Design

**Companion to:** `NOTIFICATION_SYSTEM_HLD.md`  
**Implementation:** `src/main/java/lld/notificationservice/`  
**Java version:** 21 (Records, Sealed interfaces, Pattern matching available but not required here)

---

## Table of Contents

1. [Package Structure](#1-package-structure)
2. [Class Diagram](#2-class-diagram)
3. [Core Interfaces & Contracts](#3-core-interfaces--contracts)
4. [Model Layer](#4-model-layer)
5. [Channel Dispatchers](#5-channel-dispatchers)
6. [Preference & Gate Layer](#6-preference--gate-layer)
7. [Template Engine](#7-template-engine)
8. [Delivery & Dedup Stores](#8-delivery--dedup-stores)
9. [Retry Policy](#9-retry-policy)
10. [Orchestrator — NotificationServiceImpl](#10-orchestrator--notificationserviceimpl)
11. [Request Lifecycle (Sequence Diagram)](#11-request-lifecycle-sequence-diagram)
12. [Design Decisions & Trade-offs](#12-design-decisions--trade-offs)
13. [Extension Points](#13-extension-points)
14. [Demo Scenarios](#14-demo-scenarios)

---

## 1. Package Structure

```
lld.notificationservice/
├── NotificationService.java          ← top-level interface
├── NotificationServiceImpl.java      ← orchestrator (core logic)
├── NotificationResponse.java         ← response DTO
├── NotificationServiceDemo.java      ← 8-scenario end-to-end demo
│
├── model/
│   ├── DeliveryChannel.java          ← enum: PUSH, EMAIL, SMS, WEBHOOK
│   ├── NotificationPriority.java     ← enum: CRITICAL, HIGH, NORMAL, LOW
│   ├── NotificationStatus.java       ← enum: PENDING, DELIVERED, UNDELIVERABLE, HELD, DROPPED
│   ├── NotificationRequest.java      ← inbound producer request (immutable builder)
│   ├── Notification.java             ← enriched internal notification (mutable status)
│   ├── DeliveryAttempt.java          ← per-attempt audit record
│   ├── RenderedContent.java          ← rendered subject + body for one channel
│   └── DeviceToken.java              ← push registration token
│
├── channel/
│   ├── ChannelDispatcher.java        ← interface: dispatch() → DispatchResult
│   ├── DispatchResult.java           ← value: SUCCESS / HARD_FAIL / SOFT_FAIL + codes
│   ├── FailureType.java              ← enum: SUCCESS, HARD_FAIL, SOFT_FAIL
│   ├── PushDispatcher.java           ← FCM/APNs simulation (85% success)
│   ├── EmailDispatcher.java          ← SendGrid/SES simulation (80% success)
│   ├── SmsDispatcher.java            ← Twilio/SNS simulation (94% success)
│   └── WebhookDispatcher.java        ← HTTP endpoint simulation (76% success)
│
├── preference/
│   ├── PreferenceStore.java          ← interface: get/save prefs, daily counter
│   ├── UserPreferences.java          ← value: channel order, DND, freq cap, contacts
│   └── InMemoryPreferenceStore.java  ← ConcurrentHashMap + AtomicInteger
│
├── template/
│   ├── TemplateEngine.java           ← interface: render(template, vars) → RenderedContent
│   ├── NotificationTemplate.java     ← template definition (type × channel → strings)
│   ├── SimpleTemplateEngine.java     ← {{key}} substitution using regex
│   └── TemplateRegistry.java         ← in-memory template store with fallback
│
├── delivery/
│   ├── DeliveryStore.java            ← interface: saveAttempt, getAttempts
│   └── InMemoryDeliveryStore.java    ← ConcurrentHashMap + CopyOnWriteArrayList
│
├── dedup/
│   ├── IdempotencyStore.java         ← interface: putIfAbsent, get
│   └── InMemoryIdempotencyStore.java ← ConcurrentHashMap (production: Redis SETNX)
│
└── retry/
    ├── RetryPolicy.java              ← interface: getMaxAttempts, getDelayMs
    └── ExponentialBackoffPolicy.java ← base × multiplier^(attempt-1), capped
```

---

## 2. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        NotificationService (interface)                       │
│  + send(NotificationRequest) : NotificationResponse                          │
│  + getDeliveryHistory(id) : List<DeliveryAttempt>                            │
│  + getStatus(id) : NotificationStatus                                        │
└────────────────────────────┬────────────────────────────────────────────────┘
                             │ implements
┌────────────────────────────▼────────────────────────────────────────────────┐
│                      NotificationServiceImpl                                 │
│  - preferenceStore  : PreferenceStore                                        │
│  - templateEngine   : TemplateEngine                                         │
│  - templateRegistry : TemplateRegistry                                       │
│  - deliveryStore    : DeliveryStore                                          │
│  - idempotencyStore : IdempotencyStore                                       │
│  - dispatchers      : Map<DeliveryChannel, ChannelDispatcher>                │
│  - retryPolicy      : RetryPolicy                                            │
│  - notifications    : Map<String, Notification>                              │
│                                                                              │
│  + send()              → idempotency → enrich → gates → render → dispatch   │
│  - deliverWithFallback() → iterate channels, call attemptWithRetry()         │
│  - attemptWithRetry()  → loop up to maxAttempts, backoff on SOFT_FAIL        │
│  - resolveChannelOrder() → request override > user prefs > default           │
│  - isFreqCapExceeded()  → dailyCount ≥ cap                                   │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────┐    ┌─────────────────────────────────┐
│  NotificationRequest │    │  Notification                   │
│  - producerId        │    │  - id : UUID                    │
│  - eventId           │───▶│  - request : NotificationRequest│
│  - userId            │    │  - channelOrder : List<Channel> │
│  - type              │    │  - status : NotificationStatus  │
│  - priority          │    │  - createdAt : Instant          │
│  - payload : Map     │    └─────────────────────────────────┘
│  - preferredChannels │
│  - forceDelivery     │    ┌─────────────────────────────────┐
└──────────────────────┘    │  DeliveryAttempt                │
                            │  - notificationId               │
┌──────────────────────┐    │  - userId                       │
│  UserPreferences     │    │  - channel : DeliveryChannel    │
│  - userId            │    │  - attemptNumber                │
│  - channelPriority   │    │  - result : DispatchResult      │
│  - optedOutChannels  │    │  - attemptedAt                  │
│  - dndStart/End      │    └─────────────────────────────────┘
│  - timezone          │
│  - freqCapPerDay     │    ┌─────────────────────────────────┐
│  - email             │    │  DispatchResult                 │
│  - phoneNumber       │    │  - failureType : FailureType    │
│  - webhookEndpoint   │    │  - providerMessageId            │
│  - deviceTokens      │    │  - errorCode                    │
└──────────────────────┘    │  - errorDetail                  │
                            └─────────────────────────────────┘

ChannelDispatcher (interface)
  ├── PushDispatcher    (FCM/APNs, 85% success)
  ├── EmailDispatcher   (SendGrid/SES, 80% success)
  ├── SmsDispatcher     (Twilio/SNS, 94% success)
  └── WebhookDispatcher (HTTP, 76% success)

RetryPolicy (interface)
  └── ExponentialBackoffPolicy  (base × multiplier^n, capped)
```

---

## 3. Core Interfaces & Contracts

### 3.1 `NotificationService`

```java
NotificationResponse send(NotificationRequest request);
List<DeliveryAttempt> getDeliveryHistory(String notificationId);
NotificationStatus getStatus(String notificationId);
```

**Contract:**
- `send()` is idempotent on `(producerId, eventId)` — identical calls return the same `notificationId`.
- `send()` never throws on provider failures; all failures are captured as `UNDELIVERABLE` status.
- `send()` is synchronous in this implementation; production would return `202 Accepted` after Kafka publish.

### 3.2 `ChannelDispatcher`

```java
DeliveryChannel getChannel();
DispatchResult dispatch(Notification, UserPreferences, RenderedContent);
```

**Contract:**
- Implementations must be **thread-safe** (no shared mutable state outside of thread-safe structures).
- Must return one of `SUCCESS`, `HARD_FAIL`, `SOFT_FAIL` — never throw.
- Error classification follows real provider semantics:
  - **HARD_FAIL**: permanent errors (stale token, opted-out, 410 Gone, 400 Bad Request)
  - **SOFT_FAIL**: transient errors (rate limit, 5xx, connection timeout)

### 3.3 `RetryPolicy`

```java
int getMaxAttempts();
long getDelayMs(int attemptNumber); // 1-indexed
```

**Contract:**
- `getDelayMs(1)` returns delay after the first failure.
- Must be deterministic and side-effect free.

---

## 4. Model Layer

### 4.1 `NotificationRequest` — Inbound DTO

Built by producers using a fluent builder:

```java
NotificationRequest req = NotificationRequest
    .builder("order-service", "evt-order-9823", "user-bob", "order_confirmed")
    .priority(NotificationPriority.HIGH)
    .payload(Map.of("orderId", "ORD-9823", "amount", "$49.99"))
    .build();
```

Key fields:
| Field | Purpose |
|-------|---------|
| `producerId` | Identifies the sending service |
| `eventId` | Producer-scoped idempotency key |
| `userId` | Recipient |
| `type` | Template lookup key (e.g., "otp", "order_confirmed") |
| `priority` | Controls gate bypass and retry policy selection |
| `payload` | Template variable bag — producer must embed all needed data |
| `preferredChannels` | Optional channel override (null → use user prefs) |
| `forceDelivery` | Explicitly bypass DND and frequency cap |

### 4.2 `Notification` — Internal Enriched Object

Created by the service after idempotency check:
- Assigned a new UUID as `id`
- Holds a reference to the original `NotificationRequest`
- `channelOrder` set after preference lookup
- `status` transitions: `PENDING → DELIVERED / UNDELIVERABLE / HELD / DROPPED`

### 4.3 `DeliveryAttempt` — Audit Record

One record per `(notificationId, channel, attemptNumber)` triple:
- Includes `DispatchResult` with provider message id on success or error code/detail on failure
- Written to `DeliveryStore` after every dispatch call (before retry sleep)

### 4.4 `DeviceToken`

```java
new DeviceToken("fcm-token-abc123", DeviceToken.Platform.ANDROID, "4.1")
```

- `deactivate()` called by the dispatcher when the provider returns `InvalidRegistration`
- `isActive()` checked by dispatcher before including token in a batch call

---

## 5. Channel Dispatchers

### 5.1 Error Classification by Channel

| Channel | HARD_FAIL codes | SOFT_FAIL codes |
|---------|-----------------|-----------------|
| **Push** | `InvalidRegistration`, `NotRegistered`, `NO_DEVICE_TOKEN` | `Unavailable`, `InternalServerError`, `DeviceMessageRateExceeded` |
| **Email** | `BouncePermanent`, `NO_EMAIL` | `TooManyRequests`, `ServiceUnavailable` |
| **SMS** | `21610` (opted-out), `30003` (unreachable), `NO_PHONE` | `30001` (queue overflow), `30002` (suspended) |
| **Webhook** | `HTTP_410`, `HTTP_400`, `NO_WEBHOOK_ENDPOINT` | `HTTP_503`, `HTTP_500`, timeout |

### 5.2 Push Dispatcher (FCM/APNs)

```
dispatch() flow:
  1. Resolve active device tokens from UserPreferences
  2. If empty → HARD_FAIL (NO_DEVICE_TOKEN)
  3. Pick primary token (production: multicast batch of up to 500)
  4. Call FCM/APNs API
  5. Classify per-token error codes → HARD_FAIL (deactivate token) or SOFT_FAIL
```

**Simulation:** 82% success, 13% HARD_FAIL (stale token → deactivated), 5% SOFT_FAIL.

### 5.3 Email Dispatcher (SendGrid/SES)

```
dispatch() flow:
  1. Resolve email from UserPreferences
  2. If blank → HARD_FAIL (NO_EMAIL)
  3. POST to /v3/mail/send
  4. 200/202 → SUCCESS
  5. 4xx permanent bounce → HARD_FAIL; 429/5xx → SOFT_FAIL
```

**Simulation:** 80% success, 12% HARD_FAIL (bounce), 8% SOFT_FAIL (rate limit).

### 5.4 SMS Dispatcher (Twilio)

```
dispatch() flow:
  1. Resolve phone number from UserPreferences
  2. If blank → HARD_FAIL (NO_PHONE)
  3. Enforce 160-char limit (truncate with "...")
  4. POST to /2010-04-01/Accounts/{SID}/Messages
  5. Classify Twilio error codes
```

**Simulation:** 94% success, 4% HARD_FAIL (opted-out), 2% SOFT_FAIL (queue overflow).

### 5.5 Webhook Dispatcher

```
dispatch() flow:
  1. Resolve endpoint URL from UserPreferences
  2. If blank → HARD_FAIL (NO_WEBHOOK_ENDPOINT)
  3. HTTP POST with JSON body to endpoint (10s connect, 30s read timeout)
  4. 2xx → SUCCESS; 410/400 → HARD_FAIL; 5xx/timeout → SOFT_FAIL
```

**Production notes:**
- Per-endpoint circuit breakers (half-open probe after 60s open)
- Async HTTP client (Netty/Reactor) to avoid blocking worker threads on slow endpoints
- Separate worker pool from push/SMS dispatchers

---

## 6. Preference & Gate Layer

### 6.1 `UserPreferences`

All contact details and delivery rules per user:

```java
UserPreferences.builder("user-alice")
    .email("alice@example.com")
    .phoneNumber("+1-555-0101")
    .deviceToken(new DeviceToken("token-abc", Platform.IOS, "5.0"))
    .channelPriority(List.of(PUSH, EMAIL, SMS))
    .optOut(DeliveryChannel.WEBHOOK)
    .dnd(LocalTime.of(22, 0), LocalTime.of(8, 0))  // overnight window
    .timezone(ZoneId.of("America/New_York"))
    .freqCapPerDay(10)
    .build();
```

**DND overnight window logic:**

```java
// Works for both same-day (10:00–12:00) and overnight (22:00–08:00) windows
if (dndStart.isBefore(dndEnd)) {
    return !now.isBefore(dndStart) && now.isBefore(dndEnd);  // same day
}
return !now.isBefore(dndStart) || now.isBefore(dndEnd);       // overnight
```

### 6.2 Gate Checks (in `NotificationServiceImpl.send()`)

```
Gate 1 — DND check:
  if (!forcedDelivery && prefs.isInDnd())
    → status = HELD, return NotificationResponse.held()

Gate 2 — Frequency cap check:
  if (!forcedDelivery && dailyCount >= cap)
    → status = DROPPED, return NotificationResponse.dropped()

Bypass condition:
  forcedDelivery = true   (explicit producer flag)
  OR priority = CRITICAL  (e.g., OTP, security alert)
  OR priority = HIGH      (e.g., payment confirmation)
```

### 6.3 `PreferenceStore`

| Method | Backing store | Notes |
|--------|--------------|-------|
| `getPreferences(userId)` | Redis cache → MySQL fallback | 5-min TTL; auto-creates default if missing |
| `savePreferences(prefs)` | MySQL (write-through invalidates Redis) | |
| `incrementAndGetDailyCount(userId)` | Redis INCR with 24h TTL | Atomic; returns new count |
| `getDailyCount(userId)` | Redis GET | |

---

## 7. Template Engine

### 7.1 `NotificationTemplate` — Registered per (type × channel)

```java
// Registration at startup
registry.register(new NotificationTemplate("otp", DeliveryChannel.PUSH,
        "Your OTP is {{otp}}. Expires in {{expiryMinutes}} minutes."));

registry.register(new NotificationTemplate("otp", DeliveryChannel.EMAIL,
        "One-Time Password",          // subject template
        "Your OTP is <b>{{otp}}</b>. Expires in {{expiryMinutes}} min."));
```

### 7.2 `SimpleTemplateEngine` — `{{key}}` Substitution

```java
// Regex: \{\{(\w+)\}\}
// Replaces each placeholder with payload.get(key) or leaves unchanged if not found
RenderedContent rendered = engine.render(template, Map.of("otp", "847291", "expiryMinutes", "5"));
// → body: "Your OTP is 847291. Expires in 5 minutes."
```

**Production extension:** Swap `SimpleTemplateEngine` for a `MustacheTemplateEngine` that delegates to `com.github.mustachejava.MustacheFactory`, with compiled template caching keyed on `templateId`.

### 7.3 `TemplateRegistry` — Lookup with Fallback

```
find("order_confirmed", EMAIL)
  → looks up key "order_confirmed:email"
  → if missing → fallback("order_confirmed", EMAIL)
     returns: "Notification: order_confirmed" / "You have a new notification of type order_confirmed."
```

Fallback prevents hard failures for unconfigured types during development/testing.

---

## 8. Delivery & Dedup Stores

### 8.1 `DeliveryStore`

Append-only audit log:

```java
void saveAttempt(DeliveryAttempt attempt);
List<DeliveryAttempt> getAttempts(String notificationId);
List<DeliveryAttempt> getAttempts(String notificationId, DeliveryChannel channel);
```

**Production:** Cassandra, partitioned on `(user_id, date)`. Writes flow through a Kafka topic (`status.updates`) → batch-sink consumer (1,000 rows/batch, 500ms flush). This decouples dispatcher latency from DB write latency.

**In-memory implementation:** `ConcurrentHashMap<notificationId, CopyOnWriteArrayList<DeliveryAttempt>>`

### 8.2 `IdempotencyStore`

```java
boolean putIfAbsent(String producerId, String eventId, String notificationId);
Optional<String> get(String producerId, String eventId);
```

**Composite key:** `"producerId:eventId"`  
**Production:** Redis `SET key value NX EX 86400` — atomic, 24-hour TTL.  
**In-memory:** `ConcurrentHashMap.putIfAbsent()` — atomicity guaranteed by the map.

---

## 9. Retry Policy

### 9.1 `ExponentialBackoffPolicy`

```
delay(attempt) = min(baseDelayMs × multiplier^(attempt-1), maxDelayMs)

Demo defaults:   base=100ms, multiplier=3.0, max=5 attempts, cap=5s
  attempt 1 → 100ms
  attempt 2 → 300ms
  attempt 3 → 900ms
  attempt 4 → 2700ms → capped at 5000ms

Production:      base=30_000ms, multiplier=3.0, max=5 attempts, cap=3_600_000ms
  attempt 1 → 30s
  attempt 2 → 1.5min
  attempt 3 → 4.5min
  attempt 4 → 13.5min → capped at 1hr
```

### 9.2 Priority-Specific Policies

```java
// Normal/Low: exponential backoff
RetryPolicy defaultPolicy = new ExponentialBackoffPolicy(5, 30_000, 3.0, 3_600_000);

// CRITICAL: zero-delay immediate retries (OTP, security alerts)
RetryPolicy criticalPolicy = ExponentialBackoffPolicy.immediate(3); // 3 fast retries
```

Selected in `NotificationServiceImpl.send()`:
```java
RetryPolicy retryPolicy = request.getPriority() == CRITICAL
        ? ExponentialBackoffPolicy.immediate(maxAttempts)
        : defaultRetryPolicy;
```

---

## 10. Orchestrator — `NotificationServiceImpl`

### 10.1 `send()` method — Step-by-step

```
1. Idempotency check
   → idempotencyStore.get(producerId, eventId)
   → if present: return NotificationResponse.duplicate(existingId)

2. Create Notification object
   → new Notification(request) → assigns UUID id
   → notifications.put(id, notification)
   → idempotencyStore.putIfAbsent(producerId, eventId, id)

3. Preference enrichment
   → prefs = preferenceStore.getPreferences(userId)

4. Resolve channel order
   → request.hasPreferredChannels() ? request.getPreferredChannels()
     : prefs.getChannelPriority()   (or default [PUSH, EMAIL, SMS])

5. Gate: DND check (skip if CRITICAL/HIGH or forceDelivery)
   → if prefs.isInDnd() → HELD

6. Gate: frequency cap check (skip if CRITICAL/HIGH or forceDelivery)
   → if dailyCount >= cap → DROPPED

7. Increment daily counter
   → preferenceStore.incrementAndGetDailyCount(userId)

8. Select retry policy
   → CRITICAL → ExponentialBackoffPolicy.immediate(maxAttempts)
   → others   → defaultRetryPolicy

9. deliverWithFallback(notification, prefs, channelOrder, retryPolicy)
   → for each channel in order:
       if prefs.isOptedOut(channel): skip
       dispatcher = dispatchers.get(channel)
       template = registry.find(type, channel).orElse(registry.fallback(...))
       content = templateEngine.render(template, payload)
       success = attemptWithRetry(...)
       if success: notification.status = DELIVERED; return true
   → if all channels fail: return false

10. Set final status
    → success:   DELIVERED
    → failure:   UNDELIVERABLE + log to DLQ
```

### 10.2 `deliverWithFallback()` — Channel Fallback Logic

```
for each channel in channelOrder:
  ├── if opted-out: SKIP (log) → next channel
  ├── if no dispatcher: SKIP (log) → next channel
  ├── render template
  └── attemptWithRetry()
      ├── SUCCESS → return true (stop)
      ├── HARD_FAIL → return false (go to next channel immediately)
      └── SOFT_FAIL → retry with backoff up to maxAttempts
                    → if all attempts exhausted → return false (go to next channel)
```

### 10.3 `attemptWithRetry()` — Per-Channel Retry Loop

```java
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    result = dispatcher.dispatch(notification, prefs, content);
    deliveryStore.saveAttempt(new DeliveryAttempt(..., attempt, result));

    if SUCCESS   → return true
    if HARD_FAIL → return false  // no retry
    // SOFT_FAIL:
    if attempt < maxAttempts:
        Thread.sleep(retryPolicy.getDelayMs(attempt))
}
return false  // exhausted
```

---

## 11. Request Lifecycle (Sequence Diagram)

```
Producer          NotificationServiceImpl     PreferenceStore    ChannelDispatcher   DeliveryStore
   │                        │                        │                   │                │
   │── send(request) ──────▶│                        │                   │                │
   │                        │─ get(producerId,       │                   │                │
   │                        │   eventId) ────────────▶                   │                │
   │                        │◀─ Optional.empty() ────│                   │                │
   │                        │                        │                   │                │
   │                        │── new Notification()   │                   │                │
   │                        │── putIfAbsent() ───────▶                   │                │
   │                        │                        │                   │                │
   │                        │── getPreferences() ────▶                   │                │
   │                        │◀─ UserPreferences ─────│                   │                │
   │                        │                        │                   │                │
   │                        │   [Gate: DND check]    │                   │                │
   │                        │   [Gate: freq cap]     │                   │                │
   │                        │── incrementDailyCount()▶                   │                │
   │                        │                        │                   │                │
   │                        │   [render template]    │                   │                │
   │                        │                        │                   │                │
   │                        │── dispatch(notif, prefs, content) ────────▶│                │
   │                        │◀─ DispatchResult{SUCCESS} ────────────────│                │
   │                        │                        │                   │                │
   │                        │─────────────────────── saveAttempt() ──────────────────────▶
   │                        │                        │                   │                │
   │                        │   notification.status = DELIVERED          │                │
   │◀── NotificationResponse│                        │                   │                │
   │    {ACCEPTED, DELIVERED}│                       │                   │                │
```

**Retry path (SOFT_FAIL):**
```
   │── dispatch() ─────────────────────────────────▶│
   │◀─ DispatchResult{SOFT_FAIL} ───────────────────│
   │                        │── saveAttempt(attempt=1, SOFT_FAIL)────────────────────────▶
   │                        │── Thread.sleep(delayMs)
   │── dispatch() ─────────────────────────────────▶│  (retry)
   │◀─ DispatchResult{SUCCESS} ─────────────────────│
   │                        │── saveAttempt(attempt=2, SUCCESS) ─────────────────────────▶
```

**Hard-fail → fallback path:**
```
   │── dispatch() [PUSH] ──────────────────────────▶│
   │◀─ DispatchResult{HARD_FAIL} ───────────────────│
   │                        │── saveAttempt(attempt=1, HARD_FAIL) ───────────────────────▶
   │                        │   [skip retries; try next channel]
   │── dispatch() [EMAIL] ─────────────────────────▶│
   │◀─ DispatchResult{SUCCESS} ─────────────────────│
```

---

## 12. Design Decisions & Trade-offs

### 12.1 Synchronous vs. Asynchronous Dispatch

**Current implementation:** Synchronous — `send()` blocks until delivery succeeds or all channels are exhausted.

**Trade-off:**
- **Pro:** Simple, easy to reason about; good for demo and low-latency use cases (OTP).
- **Con:** Long retry delays (up to 1 hr in production) would block the calling thread.

**Production pattern:** `send()` returns `202 Accepted` after publishing to Kafka. Dispatcher workers consume asynchronously. The `NotificationResponse` contains only the `notificationId`; clients poll `getStatus(id)` or receive webhook callbacks.

### 12.2 Failure Classification (HARD_FAIL vs. SOFT_FAIL)

The binary classification is the most impactful design decision in a notification system:

| Decision | Impact |
|----------|--------|
| Classify stale token as HARD_FAIL | Saves 5 retry attempts × N tokens = massive latency/cost reduction |
| Classify 5xx as SOFT_FAIL | Recovers from transient outages without operator intervention |
| Misclassifying a SOFT error as HARD | Notification silently dropped; user never notified |
| Misclassifying a HARD error as SOFT | 5 unnecessary retry attempts wasted before fallback |

### 12.3 Channel Order Resolution

Priority (highest to lowest):
1. `request.preferredChannels` — producer override for specific use cases
2. `prefs.channelPriority` — user's saved preference
3. Default `[PUSH, EMAIL, SMS]` — system default

This three-tier resolution allows producers to force a specific channel (e.g., `[SMS]` only for OTP) while respecting user preferences for general notifications.

### 12.4 Template Rendering Location

Templates are rendered in the dispatcher path (worker, not API path):
- API latency is not affected by template rendering or secondary data lookups
- Producers must embed all required template variables in the `payload` map at creation time
- If a variable is missing, the placeholder `{{key}}` remains in the output — observable in delivery logs

### 12.5 Frequency Cap Atomicity

`incrementAndGetDailyCount()` is designed to be atomic (`Redis INCR`). This prevents race conditions at high concurrency where two concurrent requests for the same user could both see count < cap and both pass through. The daily counter resets at midnight UTC (Redis TTL managed with a rolling 24h window key or a midnight-reset cron).

### 12.6 Opted-Out Channels vs. Missing Contact Details

Two orthogonal reasons a channel can be skipped:
1. `prefs.isOptedOut(channel)` — user's explicit preference (logged as SKIP)
2. No contact detail in prefs (e.g., no `email` field) — dispatcher returns HARD_FAIL immediately

Both result in the next channel being attempted, but they have different observability implications: opted-out skips should not trigger alerts; no-contact HARD_FAILs may indicate a data quality issue.

---

## 13. Extension Points

| What to extend | How |
|----------------|-----|
| Add a new channel (e.g., WhatsApp) | Implement `ChannelDispatcher`, register in `dispatchers` map |
| Swap template library | Implement `TemplateEngine` (e.g., `MustacheTemplateEngine`) |
| Persistent deduplication | Implement `IdempotencyStore` with Redis SETNX + 24h TTL |
| Persistent preferences | Implement `PreferenceStore` with Redis cache + MySQL/DynamoDB |
| Persistent delivery log | Implement `DeliveryStore` backed by Cassandra / DynamoDB |
| Async dispatch (production) | Replace synchronous `send()` with Kafka-publish path; workers call `deliverWithFallback()` |
| Priority queues | Use separate Kafka topics per `NotificationPriority`; CRITICAL consumers have dedicated pods |
| Rate limiting per provider | Add a `TokenBucketRateLimiter` inside each `ChannelDispatcher.dispatch()` |
| Multi-region | Deploy service in each region with its own Kafka cluster and dispatcher fleet |

---

## 14. Demo Scenarios

Run: `java -cp target/classes lld.notificationservice.NotificationServiceDemo`

| # | Scenario | What it exercises |
|---|----------|------------------|
| 1 | Happy path — OTP via push | Template rendering, push dispatch, DELIVERED status |
| 2 | Push hard-fail → email fallback | HARD_FAIL classification, immediate fallback, multi-channel |
| 3 | Duplicate `event_id` absorbed | Idempotency store, `DUPLICATE` outcome |
| 4 | DND window hold | `isInDnd()`, overnight window detection, `HELD` status |
| 5 | Frequency cap exceeded | Daily counter, cap enforcement, `DROPPED` on 3rd send |
| 6 | CRITICAL bypasses DND + cap | Priority gate bypass, forced delivery |
| 7 | All channels exhausted → DLQ | Three-channel fallback chain, `UNDELIVERABLE` status |
| 8 | Delivery audit trail | Soft-fail retries, per-attempt history, `getDeliveryHistory()` |

### Sample output (Scenario 8 — Retry + Audit):

```
--- Scenario 8: Delivery Audit Trail ---
  [PUSH ] invoice_ready        → SOFT_FAIL (call 1/2, stub)
  [RETRY] channel=PUSH attempt=1/3 → backoff 50 ms
  [PUSH ] invoice_ready        → SOFT_FAIL (call 2/2, stub)
  [RETRY] channel=PUSH attempt=2/3 → backoff 100 ms
  [PUSH ] invoice_ready        → SENT   (call 3, stub)
Response: NotificationResponse{id=4c0aead6..., outcome=ACCEPTED, status=DELIVERED}
Delivery history (3 attempt(s)):
  DeliveryAttempt{notifId=4c0aead6..., channel=PUSH, attempt=1, result=SOFT_FAIL, errorCode=TRANSIENT}
  DeliveryAttempt{notifId=4c0aead6..., channel=PUSH, attempt=2, result=SOFT_FAIL, errorCode=TRANSIENT}
  DeliveryAttempt{notifId=4c0aead6..., channel=PUSH, attempt=3, result=SUCCESS, errorCode=null}
```
