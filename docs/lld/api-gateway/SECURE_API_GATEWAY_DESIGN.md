# Secure API Gateway: Third-Party Trading & Wallet Access

**Scope:** Design of a secure API gateway for third-party access to trading and wallet APIs — authentication, request signing, rate limiting, and routing. Complements [Order Book System Design](../order-book/SYSTEM_DESIGN.md) and [Coinbase Domain HLD](../COINBASE_DOMAIN_SYSTEMS_HLD.md).

---

## 1. Overview

A **secure API gateway** sits in front of trading and wallet services and enforces **identity** (who), **integrity** (request not tampered), **authorization** (what they can do), and **fair use** (rate limits). Third-party apps (trading bots, portfolio tools, custody integrations) call the gateway; the gateway validates and forwards to downstream order gateway, wallet service, or market data.

### 1.1 Goals

| Goal | Description |
|------|-------------|
| **Authentication** | Verify caller identity via API keys and/or request signing (HMAC). |
| **Request signing** | Ensure request integrity and authenticity (replay-resistant, timestamped). |
| **Rate limiting** | Per-key, per-IP, per-endpoint limits; tiered by product/plan. |
| **Authorization** | Scoped permissions (e.g. trade vs read-only, wallet vs no wallet). |
| **Auditability** | Log all gateway decisions (allow/deny, reason) for compliance and debugging. |

### 1.2 Out of Scope

- User-facing login/OAuth flows (assume API keys and secrets are issued by a separate portal).
- Downstream business logic (order validation, matching, balance updates).
- DDoS mitigation at network edge (assume L3/L4 protection exists; gateway does L7).

---

## 2. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                         THIRD-PARTY CLIENTS (Bots, Portfolios, Custody)                   │
│  API Key + Secret → Sign requests (HMAC-SHA256); send timestamp + signature in headers    │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │ HTTPS
                                            │
┌───────────────────────────────────────────▼──────────────────────────────────────────────┐
│                                    API GATEWAY (this design)                              │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐ │
│  │ TLS         │ → │ Auth &      │ → │ Rate        │ → │ Authz       │ → │ Route &     │ │
│  │ termination │   │ Signing     │   │ Limit       │   │ (scopes)    │   │ proxy       │ │
│  └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘ │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │
         ┌──────────────────────────────────┼──────────────────────────────────┐
         │                                  │                                  │
         ▼                                  ▼                                  ▼
┌─────────────────┐              ┌─────────────────────┐              ┌─────────────────────┐
│  Order Gateway  │              │  Wallet Service     │              │  Market Data         │
│  (trading)      │              │  (balances, withdraw)│             │  (read-only)         │
└─────────────────┘              └─────────────────────┘              └─────────────────────┘
```

---

## 3. Authentication

### 3.1 Mechanisms

| Mechanism | Use case | How |
|-----------|----------|-----|
| **API Key + Request Signing** | Server-to-server (bots, institutional) | Key ID in header; body + path + timestamp signed with secret (HMAC-SHA256). |
| **API Key only** | Read-only or low-risk endpoints (e.g. public market data with key for rate-limit identity) | Key in header or query; optional for some public endpoints. |
| **OAuth2 / JWT** | User-delegated access (e.g. “connect my exchange account” to a third-party app) | Bearer token from auth server; gateway validates JWT signature and expiry. |

For **trading and wallet** access, **signed requests** are required; key-only or JWT may be allowed for read-only scopes.

### 3.2 Request Signing (HMAC)

**Purpose:** Prove that the request was sent by the holder of the **API secret** and was not altered in transit. Timestamp in the signature bounds replay window.

**Algorithm:** HMAC-SHA256 over a canonical request string.

**Canonical request (example):**

```
{timestamp}\n{method}\n{path}\n{sha256(body)}
```

- `timestamp`: Unix epoch seconds (or milliseconds); must be within ±30s of server time (configurable).
- `method`: HTTP method (e.g. `GET`, `POST`).
- `path`: Path + query string (normalized: sorted query params, no fragment).
- `sha256(body)`: Hex-encoded SHA-256 of raw body; empty string hash for no body.

**Headers (client sends):**

| Header | Description |
|--------|-------------|
| `X-API-Key` or `CB-ACCESS-KEY` | Key ID (public, identifies the key). |
| `X-API-Timestamp` or `CB-ACCESS-TIMESTAMP` | Same timestamp used in canonical request. |
| `X-API-Signature` or `CB-ACCESS-SIGNATURE` | Hex-encoded HMAC-SHA256(canonical_request, secret). |

**Gateway verification steps:**

1. Parse key ID; lookup secret (from secure store, e.g. Vault, KMS-backed DB).
2. Rebuild canonical request from incoming request.
3. Compute HMAC-SHA256(canonical_request, secret).
4. Compare with `X-API-Signature` (constant-time).
5. Reject if timestamp outside allowed window (replay protection).

**Secret storage:** Secrets are never logged or returned. Stored hashed or in a secret manager; gateway retrieves per request or via short-lived cache.

---

## 4. Rate Limiting

### 4.1 Dimensions

| Dimension | Granularity | Purpose |
|-----------|-------------|---------|
| **Per API key** | Key ID | Fair use per client; tiered limits by plan. |
| **Per IP** | Client IP (X-Forwarded-For / X-Real-IP) | Abuse and shared-key isolation. |
| **Per endpoint** | Method + path (e.g. `POST /orders`) | Protect expensive or state-changing operations. |
| **Global** | Optional | Backpressure under extreme load. |

### 4.2 Strategies

- **Token bucket** or **sliding window**: Allow N requests per window (e.g. 100/min per key, 10/s per key for order submission).
- **Quota by tier**: e.g. Basic 10 req/s, Pro 100 req/s, Institutional 1000 req/s per key.
- **Burst**: Allow short burst above sustained rate (e.g. 2x for 1s) with refill.

### 4.3 Implementation Notes

- **State:** Stored in a fast, shared store (e.g. Redis) keyed by `{key_id}` and/or `{ip}` and optionally `{endpoint}`.
- **Response:** On limit exceeded return **429 Too Many Requests** with `Retry-After` and a standard error body (e.g. `{"error":"rate_limit_exceeded","retry_after":60}`).
- **Order:** Rate limit **after** authentication so limits are applied per key; anonymous or bad-key traffic can be limited by IP only.

---

## 5. Authorization (Scopes)

After authentication, the gateway enforces **scopes** attached to the API key (or token).

| Scope | Allowed |
|-------|---------|
| `read` | GET market data, account balances, order status (read-only). |
| `trade` | Place/cancel orders (order gateway). |
| `wallet` | Withdraw, transfer, address management. |
| `admin` | Key management, scope changes (if exposed via API). |

- Keys can have multiple scopes (e.g. `read` + `trade`).
- Each route is mapped to required scope(s); request is rejected with **403 Forbidden** if scope is missing.
- Scope list stored with key metadata (DB or cache).

---

## 6. Gateway Pipeline (Order of Operations)

Recommended order of execution for each request:

```
1. TLS termination (terminate at gateway; optional re-encrypt to backends).
2. Request logging (sanitized: no secrets, no full body if sensitive).
3. Authentication
   - Resolve key (and secret if signing); verify signature and timestamp.
   - On failure → 401 Unauthorized.
4. Rate limiting (per key, per IP, per endpoint)
   - On exceeded → 429 Too Many Requests.
5. Authorization (scope check for method + path)
   - On missing scope → 403 Forbidden.
6. Optional: request validation (schema, size limits).
7. Route to backend (Order Gateway, Wallet Service, Market Data).
8. Response: pass through or transform; add headers (e.g. X-Request-Id).
9. Response logging (status, latency, key_id; no sensitive data).
```

---

## 7. Data Flow Example: Signed Order Submit

1. Client has `key_id` and `secret` (issued out-of-band).
2. Client builds: `POST /api/v1/orders` with body `{"side":"buy","product_id":"BTC-USD","size":"0.01"}`.
3. Client sets `timestamp = now()` (e.g. 1709308800).
4. Canonical string: `1709308800\nPOST\n/api/v1/orders\n<sha256(body)>`.
5. Client computes `signature = HMAC-SHA256(canonical, secret)`, sends:
   - `X-API-Key: key_id`
   - `X-API-Timestamp: 1709308800`
   - `X-API-Signature: <hex(signature)>`
6. Gateway: TLS → lookup key/secret → verify signature and timestamp → rate limit → scope check (`trade`) → proxy to Order Gateway.
7. Order Gateway returns 200 + order ID; gateway forwards to client.

---

## 8. Security Considerations

| Concern | Mitigation |
|--------|------------|
| **Replay** | Timestamp in signature; reject if outside ±30s (configurable). |
| **Secret leakage** | Secrets in secret manager; never in logs or responses; keys hashed in DB if stored. |
| **Key compromise** | Support key rotation (new key/secret); revoke old key; short-lived cache for key metadata. |
| **Privilege escalation** | Strict scope checks; no elevation at gateway. |
| **Injection / path traversal** | Validate path and query; reject malformed. |
| **Body size** | Max body size per endpoint to avoid DoS. |
| **Audit** | Log key_id, timestamp, path, result (allow/deny), reason; retain for compliance. |

---

## 9. Operational Notes

- **Key issuance:** Via separate portal or admin API; generate cryptographically random secret; show secret once (e.g. download); store only hash or reference in secret manager.
- **Health:** Gateway exposes `/health` (and optionally `/ready`); downstream health checks for routing.
- **Observability:** Metrics (request count by key, endpoint, status; latency p50/p99; rate-limit hits); tracing (X-Request-Id propagated).
- **Deployment:** Stateless gateway; scale horizontally behind load balancer; rate-limit state in Redis (or similar) shared across instances.

---

## 10. Summary

| Area | Design choice |
|------|----------------|
| **Auth** | API key + HMAC request signing (timestamp + method + path + body hash); optional JWT for user-delegated. |
| **Signing** | Canonical request string; HMAC-SHA256; constant-time compare; timestamp replay window. |
| **Rate limit** | Per key, per IP, per endpoint; token bucket or sliding window in Redis; 429 + Retry-After. |
| **Authz** | Scopes (read, trade, wallet) attached to key; per-route scope check → 403 if missing. |
| **Pipeline** | TLS → Auth → Rate limit → Authz → Route → Backend; audit log for all decisions. |

This design yields a **secure API gateway** suitable for third-party trading and wallet access with authentication, request signing, rate limiting, and scope-based authorization, aligned with common exchange API practices (e.g. Coinbase, Binance-style signed APIs).
