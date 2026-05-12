# Multiplayer Session System — High-Level Design

**Target scale: 10 million concurrent players, 100 K active game sessions, sub-100 ms action round-trip.**

---

## 0. Executive Summary

### 0.1 Headline numbers

| Metric | Target |
|---|---|
| Concurrent connected players | 10 M |
| Active sessions | 100 K |
| Action submission p99 latency | < 100 ms |
| Reconnect grace period | 30 s (configurable) |
| Session fan-out (state broadcast) | < 50 ms |
| Matchmaking wait (casual, 2-player) | < 5 s |
| Availability SLA | 99.95% |

### 0.2 One-sentence architecture

> **Client → API Gateway (WebSocket/gRPC) → Session Service → Session Store (Redis) → Game State Service; all state changes are published to a per-session Event Bus (Kafka) that fans out to connected clients and downstream consumers (analytics, replay, anti-cheat).**

### 0.3 Component responsibilities

| Component | Single responsibility |
|---|---|
| **Gateway Service** | Persistent client connections, authentication, protocol translation |
| **Matchmaker Service** | Queue management, skill/region bucketing, session creation trigger |
| **Session Service** | Session lifecycle authority: create / join / start / pause / end |
| **Game State Service** | Game-type-specific action validation and state machine |
| **Session Store** | Durable key-value store for session metadata (Redis Cluster) |
| **Event Bus** | Ordered, durable fan-out of session events (Kafka) |
| **Presence Service** | Heartbeat tracking, disconnect detection, reconnect signalling |
| **Notification Service** | Push / SMS fallback for offline players |

---

## 1. System Context Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS                                        │
│  Mobile App     Web Browser     Desktop Client     Spectator Browser        │
└────────┬────────────┬────────────────┬─────────────────┬────────────────────┘
         │ WebSocket  │ WebSocket      │ gRPC            │ WebSocket (read-only)
         ▼            ▼                ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         API GATEWAY LAYER                                   │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │  Load Balancer  (L7, sticky by playerId hash)                       │   │
│   └────────────────────────┬────────────────────────────────────────────┘   │
│                            │                                                │
│   ┌────────────────┐  ┌────┴───────────┐  ┌─────────────────────────────┐  │
│   │  Gateway Node  │  │  Gateway Node  │  │       Gateway Node          │  │
│   │  (stateful)    │  │  (stateful)    │  │       (stateful)            │  │
│   └────────┬───────┘  └───────┬────────┘  └──────────────┬──────────────┘  │
└────────────┼──────────────────┼──────────────────────────┼─────────────────┘
             │ internal RPC (gRPC)                          │
             ▼                  ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SERVICE MESH (Kubernetes)                           │
│                                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │  Matchmaker  │  │   Session    │  │  Game State  │  │   Presence    │  │
│  │   Service    │  │   Service    │  │   Service    │  │   Service     │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
│         │                 │                  │                  │           │
│         └─────────────────┴──────────────────┴──────────────────┘           │
│                                    │                                        │
│                     ┌──────────────▼──────────────────┐                    │
│                     │        Data Tier                 │                    │
│   ┌─────────────┐   │  ┌──────────┐  ┌────────────┐  │  ┌─────────────┐  │
│   │    Kafka    │   │  │  Redis   │  │  Postgres  │  │  │  ClickHouse │  │
│   │  (events)   │   │  │ Cluster  │  │  (persist) │  │  │ (analytics) │  │
│   └─────────────┘   │  └──────────┘  └────────────┘  │  └─────────────┘  │
│                     └─────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Subsystems

### 2.1 Gateway Service

**Role:** Owns every persistent client connection. Translates between the wire protocol (WebSocket frames / gRPC streams) and internal Protobuf messages. Responsible for:

- TLS termination and JWT authentication
- Connection fan-in: routes inbound player actions to the Session Service
- Connection fan-out: pushes session events received from Kafka to connected clients
- Heartbeat / ping-pong to detect dead connections (feeds the Presence Service)
- Spectator admission (read-only subscription to a session's Kafka topic)

**Stickiness:** A player's WebSocket lives on exactly one gateway node. The load balancer uses consistent hashing on `playerId` to route reconnections to the same pod. Session event messages are broadcast to all gateways via Kafka; each gateway filters to its locally connected set.

**Scaling:** Gateway nodes are stateless except for in-memory connection maps. Horizontal scaling is limited only by file-descriptor limits and network bandwidth. A node hosting 50 K connections at 1 KB/s average throughput needs ~50 MB/s NIC capacity.

---

### 2.2 Matchmaker Service

**Role:** Manages per-game-type matchmaking queues and fires session creation when a group is assembled.

#### 2.2.1 Queue structure

```
GameType → [ MatchmakingEntry(player, criteria, enqueuedAt), ... ]
```

One queue shard per game type. Entries are sorted by enqueue time (FIFO) inside a skill bracket.

#### 2.2.2 Matching algorithm layers

| Layer | Description |
|---|---|
| **Exact** | Same game type, same mode, overlapping skill band |
| **Relaxed** | Widen skill band by ±10% every 5 s of wait |
| **Fill** | After 30 s, accept any available player rather than wait longer |

#### 2.2.3 Match flow

```
                  ┌──────────────────────────────────────┐
  Player enqueues │  Matchmaking Queue  (Redis Sorted Set)│
  ──────────────► │  score = enqueue_epoch_ms             │
                  └──────────────┬───────────────────────┘
                                 │ ticker every 500 ms
                                 ▼
                  ┌──────────────────────────────────────┐
                  │  Matching Algorithm                  │
                  │  (FirstAvailable / SkillBracket /    │
                  │   RegionAware)                       │
                  └──────────────┬───────────────────────┘
                                 │ group of N players
                                 ▼
                  ┌──────────────────────────────────────┐
                  │  Session Service.createSession()     │
                  └──────────────┬───────────────────────┘
                                 │ sessionId
                                 ▼
                  ┌──────────────────────────────────────┐
                  │  Notify Players (via Gateway push)   │
                  │  "Session ready: <sessionId>"        │
                  └──────────────────────────────────────┘
```

---

### 2.3 Session Service

**Role:** Single source of truth for session lifecycle. All state transitions are serialised here.

#### 2.3.1 Session lifecycle state machine

```
                  createSession()
                       │
                       ▼
              ┌────────────────────┐
              │  WAITING_FOR_      │  ◄── joinSession()
              │    PLAYERS         │
              └────────┬───────────┘
                       │ startSession() [minPlayers reached]
                       ▼
              ┌────────────────────┐
              │     STARTING       │  [ready-check handshake]
              └────────┬───────────┘
                       │ all players ACK
                       ▼
              ┌────────────────────┐
              │      ACTIVE        │ ◄──────────────────────────┐
              └────────┬───────────┘                            │
              │        │ playerDisconnect()                      │ reconnect()
              │        ▼                                         │ within grace
              │ ┌────────────────────┐                          │
              │ │      PAUSED        │ ──────────────────────────┘
              │ └────────┬───────────┘
              │          │ grace period expired
              │          ▼
              │ ┌────────────────────┐
              │ │     ABANDONED      │ (terminal)
              │ └────────────────────┘
              │
              │ endSession()
              ▼
     ┌────────────────────┐
     │      FINISHED      │ (terminal)
     └────────────────────┘
```

#### 2.3.2 Concurrency model

Every session object is stored in Redis as a hash. State transitions use a **Lua script** (executed atomically on the Redis node) to enforce valid-transition guards, preventing two concurrent events from racing a session into an invalid state:

```lua
-- Pseudocode: atomic CAS on session state
local current = redis.call('HGET', KEYS[1], 'state')
if current ~= ARGV[1] then return 0 end   -- expected state mismatch
redis.call('HSET', KEYS[1], 'state', ARGV[2])
redis.call('HSET', KEYS[1], 'updatedAt', ARGV[3])
return 1   -- success
```

#### 2.3.3 Reconnect grace timer

Implemented as a Redis key with TTL equal to `reconnectGracePeriodSeconds`. When the key expires a Keyspace Notification fires, consumed by the Session Service to trigger the grace-expiry logic.

```
Redis key: session:{sessionId}:reconnect:{playerId}
TTL:       30 seconds (default)
Value:     epoch of disconnect

On expiry event → Session Service sets state = ABANDONED
```

---

### 2.4 Game State Service

**Role:** Game-type-specific action validation and state delta computation. Stateless in itself; loads the current game state from Redis on each request.

```
Client submits action
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  Session Service validates:                               │
│   • session is ACTIVE                                     │
│   • player is in this session                             │
│   • idempotency key not already processed                 │
└─────────────────────────┬─────────────────────────────────┘
                          │ forward to Game State Service
                          ▼
┌───────────────────────────────────────────────────────────┐
│  Game State Service:                                      │
│   1. Load game state from Redis (HGETALL session:{id}:gs) │
│   2. Validate action against game rules                   │
│   3. Compute state delta                                  │
│   4. Write new state back atomically                      │
│   5. Return delta to Session Service                      │
└─────────────────────────┬─────────────────────────────────┘
                          │ delta
                          ▼
┌───────────────────────────────────────────────────────────┐
│  Session Service publishes to Kafka:                      │
│   Topic: session-events.{gameType}                        │
│   Key:   sessionId  (ensures ordering per session)        │
│   Value: SessionEvent{ACTION_SUBMITTED, delta, ...}       │
└─────────────────────────┬─────────────────────────────────┘
                          │
                          ▼
                   Gateway fan-out
              (push delta to all clients
               in this session)
```

**Idempotency:** The session stores a bounded ring-buffer of the last 1,000 processed `idempotencyKey`s per session in Redis. Duplicate submissions within this window are rejected with a 409-style error; outside the window they are assumed expired and accepted (safe for the retry period of any reasonable client).

---

### 2.5 Event Bus (Kafka)

**Role:** Durable, ordered, replayable event log per session.

#### Topic layout

| Topic | Partition key | Consumers |
|---|---|---|
| `session-events.{gameType}` | `sessionId` | Gateway fan-out workers, Anti-cheat, Replay recorder |
| `matchmaking-events` | `gameType` | Analytics |
| `presence-events` | `playerId` | Session Service (disconnect/reconnect handler) |

**Ordering guarantee:** Because `sessionId` is the Kafka partition key, all events for a given session land on the same partition and are processed in order by each consumer group.

**Retention:** 7 days — enough to support game replay and post-match analysis without permanent storage costs.

---

### 2.6 Session Store (Redis Cluster)

#### Key schema

```
session:{sessionId}                      HASH   — metadata (state, config, timestamps)
session:{sessionId}:players              SET    — set of playerIds
session:{sessionId}:gs                   HASH   — game state (opaque per game type)
session:{sessionId}:actions              ZSET   — processed action idempotency ring (score = processedAt)
session:{sessionId}:reconnect:{playerId} STRING — TTL key for reconnect grace
matchmaking:{gameType}                   ZSET   — queue (score = enqueuedAt epoch)
presence:{playerId}                      STRING — TTL key = heartbeat interval × 3
```

**TTL strategy:** Every write to a session hash resets a `EXPIRE session:{sessionId} <ttl>`. The TTL is taken from `SessionConfig.sessionTtlSeconds`. Keyspace notifications on expired keys trigger the Session Service's eviction callbacks.

---

### 2.7 Presence Service

**Role:** Tracks whether a player is currently connected. Drives the disconnect / reconnect flow.

```
Client → Gateway → heartbeat every 5 s
                        │
                        ▼
              Redis SET presence:{playerId}  EX 15
              (TTL = 3× heartbeat interval)

On TTL expiry:
  Redis keyspace notification → Presence Service
  → Presence Service calls Session Service.notifyDisconnect()
  → Session Service starts reconnect grace timer
```

A player's `lastSeenAt` timestamp is stored in Redis and updated on each heartbeat. This is used by the Session Service to differentiate a network blip (< 5 s gap) from a genuine disconnect (> 15 s gap, i.e., 3 missed heartbeats).

---

## 3. Data Flow Diagrams

### 3.1 Player Action Flow (Happy Path)

```
Player A                Gateway               Session Svc        Game State Svc     Kafka         Players B..N
   │                       │                       │                    │              │                │
   │── submitAction() ────►│                       │                    │              │                │
   │                       │── validateSession() ─►│                    │              │                │
   │                       │                       │── loadGameState() ►│              │                │
   │                       │                       │                    │── delta ─────►              │
   │                       │◄─────────────────── ACK (actionId) ────────│              │                │
   │◄─ ACK ────────────────│                       │                    │── publish ──►│                │
   │                       │                       │                    │              │── fan-out ────►│
   │                       │                       │                    │              │                │
```

Round-trip budget for ACK back to Player A: **< 100 ms** at p99.

### 3.2 Disconnect / Reconnect Flow

```
Player B                Gateway           Presence Svc      Session Svc        Redis
   │                       │                   │                │                │
   │   [connection drops]  │                   │                │                │
   │                       │── EOF / timeout ─►│                │                │
   │                       │                   │── DEL presence:{B} ─────────────►│
   │                       │                   │   (or TTL expires)              │
   │                       │                   │── notifyDisconnect(sessionId, B)►│
   │                       │                   │                │── SET reconnect│
   │                       │                   │                │   :{B} EX 30 ─►│
   │                       │                   │                │── HSET state   │
   │                       │                   │                │   = PAUSED ───►│
   │  ... [30 s grace] ... │                   │                │                │
   │── connect() ─────────►│                   │                │                │
   │── auth JWT ──────────►│                   │                │                │
   │                       │── notifyReconnect(sessionId, B) ──►│                │
   │                       │                   │                │── DEL reconnect│
   │                       │                   │                │   :{B} ────────►│
   │                       │                   │                │── HSET state   │
   │                       │                   │                │   = ACTIVE ───►│
   │◄── sync full state ───│◄──────────────────────────────────│                │
   │                       │                   │                │                │
```

### 3.3 Matchmaking Flow

```
Player A                Player B           Matchmaker         Session Svc     Gateway A/B
   │                       │                   │                  │                │
   │── enqueue(CHESS) ────►│── enqueue(CHESS)─►│                  │                │
   │                       │                   │── [tick 500ms]   │                │
   │                       │                   │── match(A, B) ──►│                │
   │                       │                   │                  │── create sess ─┤
   │                       │                   │◄─ sessionId ─────│                │
   │                       │◄─ push "ready: {sessionId}" ─────────────────────────►│
   │── joinSession() ──────────────────────────►│                  │                │
   │                       │── joinSession() ──►│                  │                │
   │────── startSession() ─────────────────────►│                  │                │
   │                       │◄─ SESSION_STARTED event (Kafka fan-out) ──────────────►│
```

---

## 4. Non-Functional Design

### 4.1 Scalability

| Dimension | Strategy |
|---|---|
| **Gateway connections** | Horizontal scale; each pod holds ~50 K WS connections; 200 pods = 10 M players |
| **Session Service** | Stateless; scales horizontally behind an internal load balancer |
| **Matchmaker** | One leader per game-type queue (leader election via Redis `SET NX`); followers on standby |
| **Redis** | Redis Cluster with 6 shards (3 primary + 3 replica); consistent hashing on `sessionId` |
| **Kafka** | 1 partition per 10 K sessions → 10 partitions for 100 K active sessions; easily extended |

### 4.2 Availability

| Failure scenario | Mitigation |
|---|---|
| Gateway pod crash | Clients auto-reconnect; sticky routing re-establishes session within grace period |
| Session Service pod crash | Stateless; request retried to another pod; Redis holds all session state |
| Redis primary failure | Sentinel / Cluster automatic failover; replica promoted in < 30 s (within grace period) |
| Kafka broker failure | Replication factor 3; consumer group re-balances automatically |
| Matchmaker leader crash | Standby acquires Redis lock within 5 s; queue state survives in Redis ZSET |

### 4.3 Consistency

The system is **session-consistent**: all state transitions for a session are serialised through a single Redis slot (same hash slot guaranteed by `{sessionId}` hash tag). This gives linearisable per-session reads without distributed transactions.

Cross-session operations (e.g., leaderboard updates) are eventually consistent and go through an async Kafka pipeline.

### 4.4 Security

| Concern | Control |
|---|---|
| Authentication | JWT signed by Auth Service; verified at Gateway; `playerId` extracted and propagated in headers |
| Authorization | Session Service checks `playerId ∈ session.players` before every write |
| Action replay attacks | Idempotency key ring-buffer prevents replayed action submissions |
| Spectator isolation | Spectator connections receive read-only Kafka consumer on the session topic; cannot submit actions |
| Session hijacking | `sessionId` is a UUID v4 (128-bit entropy); combined with player JWT — both must match |

### 4.5 Observability

| Signal | Tool | Key metrics |
|---|---|---|
| **Metrics** | Prometheus + Grafana | Sessions per state, matchmaking queue depth, action p50/p99 latency, reconnect rate |
| **Tracing** | OpenTelemetry | End-to-end trace per action: Client → Gateway → Session Svc → Game State Svc → Kafka |
| **Logging** | Structured JSON → ELK | Session lifecycle events, error rates per game type |
| **Alerting** | PagerDuty | Reconnect rate > 5%, queue depth > 10 K, action latency p99 > 200 ms |

---

## 5. Data Model

### 5.1 Session (Redis Hash)

| Field | Type | Description |
|---|---|---|
| `sessionId` | string (UUID) | Primary key |
| `gameType` | string | e.g. `CHESS`, `TICTACTOE` |
| `state` | enum string | `WAITING_FOR_PLAYERS` \| `STARTING` \| `ACTIVE` \| `PAUSED` \| `FINISHED` \| `ABANDONED` |
| `minPlayers` | int | Minimum to start |
| `maxPlayers` | int | Hard cap |
| `createdAt` | epoch ms | Creation timestamp |
| `startedAt` | epoch ms | When ACTIVE first reached |
| `lastActivityAt` | epoch ms | Updated on every write; drives TTL |
| `sessionTtlSeconds` | int | Inactivity TTL |
| `reconnectGraceSec` | int | Disconnect grace window |

### 5.2 Player (Redis Hash — identity service owns, cached here)

| Field | Type | Description |
|---|---|---|
| `playerId` | string (UUID) | Primary key |
| `displayName` | string | Human-readable name |
| `state` | enum string | `IDLE` \| `IN_MATCHMAKING` \| `IN_LOBBY` \| `IN_SESSION` \| `DISCONNECTED` |
| `lastSeenAt` | epoch ms | Last heartbeat |
| `currentSessionId` | string? | Populated while IN_SESSION |

### 5.3 GameAction (Kafka message value)

| Field | Type | Description |
|---|---|---|
| `actionId` | UUID | Unique action identifier |
| `sessionId` | UUID | Session this action belongs to |
| `playerId` | UUID | Submitting player |
| `actionType` | string | Game-specific verb (e.g. `MOVE`, `FORFEIT`) |
| `payload` | JSON object | Action parameters (game-type-specific) |
| `idempotencyKey` | string | Client-supplied dedup key |
| `submittedAt` | epoch ms | Client-side timestamp |
| `processedAt` | epoch ms | Server-side acceptance timestamp |

---

## 6. API Contract (Internal gRPC)

### SessionService

```protobuf
service SessionService {
  rpc CreateSession   (CreateSessionRequest)   returns (Session);
  rpc JoinSession     (JoinSessionRequest)     returns (Session);
  rpc LeaveSession    (LeaveSessionRequest)    returns (google.protobuf.Empty);
  rpc StartSession    (StartSessionRequest)    returns (google.protobuf.Empty);
  rpc EndSession      (EndSessionRequest)      returns (google.protobuf.Empty);
  rpc SubmitAction    (SubmitActionRequest)    returns (SubmitActionResponse);
  rpc NotifyDisconnect(DisconnectRequest)      returns (DisconnectResponse);
  rpc NotifyReconnect (ReconnectRequest)       returns (google.protobuf.Empty);
  rpc GetSession      (GetSessionRequest)      returns (Session);
}
```

### MatchmakerService

```protobuf
service MatchmakerService {
  rpc Enqueue  (EnqueueRequest)  returns (google.protobuf.Empty);
  rpc Dequeue  (DequeueRequest)  returns (google.protobuf.Empty);
  rpc GetStatus(StatusRequest)   returns (MatchmakingStatus);
}
```

### Gateway → Client (WebSocket message envelope)

```json
{
  "type": "SESSION_EVENT",
  "sessionId": "<uuid>",
  "eventType": "ACTION_SUBMITTED | PLAYER_JOINED | SESSION_STARTED | ...",
  "payload": { },
  "occurredAt": 1778568922608
}
```

---

## 7. Capacity Estimation

### Assumptions

| Parameter | Value |
|---|---|
| Concurrent players | 10 M |
| Avg session size | 2 players |
| Active sessions | 100 K (5 M players in-session, rest idle/matchmaking) |
| Actions per session per second | 2 (casual turn-based) to 20 (real-time) |
| Avg action payload | 256 bytes |
| Session state size | 4 KB |

### Throughput

| Flow | Rate |
|---|---|
| Actions in | 100 K sessions × 5 avg actions/s = **500 K actions/s** |
| Events out (fan-out, avg 2 players) | 500 K × 2 = **1 M events/s** to clients |
| Kafka writes | 500 K messages/s at 256 B = **128 MB/s** |
| Redis reads | ~5 M ops/s (action validation + state load) |
| Redis writes | ~1.5 M ops/s (state update + idempotency + TTL reset) |

### Storage (Redis, hot tier)

| Data | Per unit | Total |
|---|---|---|
| Session metadata | 4 KB | 100 K × 4 KB = **400 MB** |
| Idempotency ring (1 K entries × 64 B) | 64 KB | 100 K × 64 KB = **6.4 GB** |
| Presence keys | 64 B | 10 M × 64 B = **640 MB** |
| Matchmaking queues | 128 B / entry | 500 K × 128 B = **64 MB** |
| **Total Redis hot** | | **~8 GB** (fits in a 3-node × 16 GB cluster) |

### Kafka retention

100 K sessions × avg 1 K actions × 256 B × 7 days / session lifetime (avg 30 min)
≈ **~10 GB retained at any time** (well within a standard 3-broker cluster).

---

## 8. Key Design Decisions

### 8.1 Authoritative server vs. P2P

**Choice: Authoritative server.**

P2P reduces server cost but introduces:
- Network address translation (NAT) traversal complexity
- No canonical truth → cheat prevention is impossible
- Reconnect / replay requires a designated host with state

An authoritative server gives a single truth source, clean reconnect semantics, and straightforward anti-cheat integration.

### 8.2 In-process vs. distributed event bus

**LLD implementation:** In-process `SessionEventBus` using Java virtual threads.
**Production:** Kafka per session topic.

The LLD bus is a drop-in replacement via the same `SessionEventListener` interface. The migration path is: swap `SessionEventBus.publish()` to call a Kafka producer, and replace in-process listener registration with a Kafka consumer group.

### 8.3 State transition serialisation

**Choice: Redis Lua CAS scripts instead of application-layer optimistic locking.**

Application-layer compare-and-swap requires a round-trip read then write. A Lua script executes the read-compare-write atomically on the Redis node, eliminating the race window and the extra network hop.

### 8.4 Reconnect grace period implementation

**Choice: Redis key TTL + Keyspace Notifications.**

Alternatives considered:
- Application timer (ScheduledExecutorService): works but ties timers to a specific JVM process; does not survive pod restarts.
- Polling loop: wastes CPU; adds latency equal to the poll interval.

Redis TTL expiry is durable, fires once, and survives service restarts. The 30-second default grace period is longer than a Redis failover (< 10 s) and the typical mobile reconnect time (< 5 s).

### 8.5 Idempotency key scope

**Choice: Per-session ring buffer of the last 1,000 keys.**

A global dedup store would require cross-session coordination. Since idempotency only matters within a session (a client retrying an action it submitted to *this* session), per-session scope is sufficient and avoids contention.

---

## 9. Extension Points

| Capability | Where to add |
|---|---|
| **Skill-based matchmaking** | Replace `FirstAvailableStrategy` with `SkillBracketStrategy` (same interface) |
| **Regional latency routing** | Add `region` field to `MatchmakingCriteria`; Matchmaker filters by region before skill |
| **Spectators** | `Session.addSpectator()` already exists; Gateway needs a read-only subscription path |
| **Tournament brackets** | Add a `TournamentService` that calls `SessionManager.createSession` per match and listens on `SESSION_FINISHED` to advance brackets |
| **Anti-cheat** | New Kafka consumer group on `session-events.*`; reads action stream, flags anomalies asynchronously |
| **Game replay** | Kafka consumer writes the action log to cold storage (S3); a replay server reads it back deterministically |
| **In-game chat** | Separate `ChatService` subscribing to session membership events; publishes chat messages to a `chat.{sessionId}` Kafka topic |
| **Persistent matchmaking history** | Kafka consumer sinks `matchmaking-events` into Postgres for win/loss tracking and ELO recalculation |

---

## 10. LLD → HLD Mapping

The pure-Java LLD implementation in `src/main/java/lld/multiplayer/` maps to the distributed components as follows:

| LLD class / interface | HLD component | Notes |
|---|---|---|
| `SessionManagerImpl` | **Session Service** (pod) | Add Redis client; replace in-memory maps with Redis calls |
| `InMemorySessionStore` | **Redis Cluster** | `SessionStore` interface is already abstract; swap implementation |
| `SessionEventBus` | **Kafka producer** | Same `SessionEventListener` API; swap dispatch to Kafka |
| `Matchmaker` | **Matchmaker Service** (pod) | Replace `CopyOnWriteArrayList` queues with Redis Sorted Sets |
| `Lobby` | **Session Service** (WAITING_FOR_PLAYERS state) | Lobby is a thin view on an unstarted session |
| `MultiplayerSessionSystem` | **API Gateway** (façade) | Gateway calls Session Service and Matchmaker via gRPC |
| `Player` | **Player record in Redis + Auth JWT** | Player state lives in Redis; identity in Auth Service |
| `GameAction` | **Kafka message value** | Already serialisation-agnostic; add Protobuf schema |
| `FirstAvailableStrategy` | **Casual matchmaking** | Keep as default; add skill/region variants |
