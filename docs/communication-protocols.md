# Communication Protocols: When to Use What

## 1. HTTP/REST

**What it is:** Request-response protocol over TCP. Client initiates, server responds. Stateless by design.

**Pros:**
- Universal support — works everywhere (browsers, mobile, servers)
- Simple to understand, debug, and test (curl, Postman, browser DevTools)
- Stateless = horizontally scalable with no affinity concerns
- Rich ecosystem: caching (CDN, ETags), load balancing, proxies all work natively
- Human-readable (JSON/XML)

**Cons:**
- Half-duplex — client must always initiate
- High overhead per request (headers repeated, TCP handshake cost)
- No native streaming — polling workarounds are inefficient
- Over/under-fetching problems (solved partially by GraphQL)
- HTTP/1.1 head-of-line blocking (HTTP/2 mitigates this)

**Use when:**
- Public APIs consumed by third parties
- CRUD operations, resource-based APIs
- Browser-facing services
- When cacheability matters (GET requests)
- Interoperability is a priority

---

## 2. WebSocket

**What it is:** Full-duplex, persistent TCP connection. After an HTTP upgrade handshake, both sides can push messages freely.

**Pros:**
- True bidirectional, real-time communication
- Low latency — no repeated handshakes
- Server can push without client polling
- Low per-message overhead after connection is established

**Cons:**
- Stateful — breaks horizontal scaling (requires sticky sessions or a pub/sub broker like Redis)
- No built-in request/response correlation (you build it yourself)
- Harder to cache, proxy, and debug
- Connection management complexity (reconnects, heartbeats)
- Not ideal for simple request-response patterns

**Use when:**
- Real-time features: live chat, multiplayer games, collaborative editing
- Live dashboards, stock tickers, sports scores
- Notifications that must be pushed instantly
- When you need sub-second server-to-client updates

---

## 3. gRPC

**What it is:** RPC framework by Google using HTTP/2 + Protocol Buffers (binary serialization). Defines services via `.proto` schema files.

**Pros:**
- Extremely fast — binary encoding (Protobuf) is 3-10x smaller than JSON
- HTTP/2 multiplexing — multiple streams over one connection
- Strongly typed contracts via `.proto` — compile-time safety, auto-generated client/server code
- Native streaming: unary, server-side, client-side, bidirectional
- Built-in deadline/timeout, cancellation propagation
- Excellent for polyglot systems (code gen for Go, Java, Python, etc.)

**Cons:**
- Not browser-native (requires gRPC-Web proxy for browser clients)
- Binary format = harder to debug without tooling
- Schema evolution requires discipline (backwards compatibility rules)
- Overkill for simple services
- Steeper learning curve

**Use when:**
- Internal microservice-to-microservice communication
- High-throughput, low-latency services
- Polyglot environments needing strong contracts
- Streaming large datasets between services
- When you own both client and server

---

## 4. GraphQL

**What it is:** Query language over HTTP where the client specifies exactly what data it needs.

**Pros:**
- No over/under-fetching — client drives the shape of the response
- Single endpoint, self-documenting schema (introspection)
- Great for aggregating multiple backend data sources
- Subscriptions for real-time (over WebSocket)

**Cons:**
- Complex caching (HTTP caching doesn't apply easily — all POSTs)
- N+1 query problem if resolvers aren't optimized (use DataLoader)
- Schema design is critical and hard to change later
- Higher server-side complexity

**Use when:**
- Frontend teams need flexibility in data fetching
- Aggregating data from multiple microservices for a BFF (Backend for Frontend)
- Mobile apps where bandwidth matters

---

## 5. Server-Sent Events (SSE)

**What it is:** One-directional stream from server to client over plain HTTP. Simple `text/event-stream` response that stays open.

**Pros:**
- Works over standard HTTP/1.1 — proxies, load balancers just work
- Auto-reconnect built into browser EventSource API
- Much simpler than WebSocket when you only need server → client
- Works natively in browsers

**Cons:**
- Unidirectional only (server → client)
- Limited to text data (no binary)
- One connection per stream (HTTP/2 multiplexing helps)

**Use when:**
- Live feeds: notifications, log streaming, progress updates
- You need server push but NOT client push
- Simpler alternative to WebSocket for one-way data

---

## 6. Message Queues / Async Messaging (Kafka, RabbitMQ, SQS)

**What it is:** Producers publish messages to a broker; consumers read asynchronously. Decoupled, durable communication.

**Pros:**
- Temporal decoupling — producer and consumer don't need to be running simultaneously
- Natural backpressure and load leveling
- Durability — messages survive crashes
- Fan-out: one message → many consumers
- Replay capability (Kafka)

**Cons:**
- Not request-response — you lose synchronous feedback
- Added operational complexity (broker to manage)
- Message ordering guarantees vary by system
- At-least-once delivery requires idempotent consumers

**Use when:**
- Event-driven architectures
- Background jobs, email sending, image processing
- Decoupling services that have different scaling profiles
- Data pipelines, audit logs, event sourcing

---

## 7. TCP (Raw Sockets)

**What it is:** Low-level transport protocol providing reliable, ordered byte stream delivery.

**Pros:**
- Maximum control, minimal overhead
- Lowest possible latency
- No protocol overhead beyond what you add yourself

**Cons:**
- You build everything — framing, retries, congestion control logic
- No abstractions — highly error-prone to implement correctly
- Poor developer experience

**Use when:**
- Game servers, custom database protocols
- Financial trading systems where microseconds matter
- Building your own protocol on top

---

## Quick Decision Guide

| Scenario | Protocol |
|---|---|
| Public REST API | HTTP/REST |
| Microservice internal calls | gRPC |
| Live chat / multiplayer | WebSocket |
| Push notifications / live feed | SSE or WebSocket |
| Frontend flexible data needs | GraphQL |
| Background job processing | Message Queue |
| Event streaming / audit log | Kafka |
| Highest performance, custom | Raw TCP |

---

## The Golden Rule

> **Synchronous protocols** (HTTP, gRPC, WebSocket) create **temporal coupling** — both sides must be available simultaneously.
> **Async messaging** (Kafka, RabbitMQ) removes that coupling at the cost of losing immediate feedback.
> Design your system around which tradeoff matters more for each interaction.
