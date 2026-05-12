# Multiplayer Session System — High-Level Design (Staff Engineer Level)

**Target scale: 10 M concurrent players · 100 K active sessions · sub-100 ms action p99 · 99.95 % availability.**

---

## 0. Executive Summary

### 0.1 Headline SLOs

| Metric | Target | Measurement |
|---|---|---|
| Concurrent connected players | 10 M | WebSocket connections alive |
| Active game sessions | 100 K | Sessions in state ACTIVE or PAUSED |
| Action submission p99 latency (ACK) | < 100 ms | Gateway inbound → ACK to submitter |
| Session event fan-out p99 | < 50 ms | ACK received → delta pushed to all players |
| Matchmaking wait p95 (2-player casual) | < 5 s | Enqueue → SESSION_STARTED |
| Reconnect grace window | 30 s (configurable) | Disconnect → abandon if no reconnect |
| Write availability | 99.95 % | Measured at Session Service |
| Read availability | 99.99 % | Measured at Gateway (cache reads) |
| RTO (full region failure) | < 5 min | Time to shift traffic to standby region |
| RPO | 0 (session state) · 1 min (analytics) | Redis synchronous replication · Kafka consumer lag |

### 0.2 Architecture in one sentence

> **Client → Edge (CDN/Anycast) → Regional API Gateway (WebSocket) → Session Service (authoritative state in Redis) → Game State Service (rule validation) → Kafka (ordered event log) → Gateway fan-out → all session participants; Matchmaker and Presence Service run as sidecars to this critical path.**

### 0.3 Component map

| Layer | Components |
|---|---|
| **Edge** | CDN (Cloudflare / Fastly), Anycast BGP, DDoS scrubbing |
| **Gateway** | WS Gateway pods, REST API pods, gRPC ingress |
| **Business logic** | Session Service, Matchmaker Service, Game State Service, Presence Service |
| **Async workers** | Anti-cheat Consumer, Replay Recorder, ELO Updater, Notification Worker |
| **Data — hot** | Redis Cluster (session state, presence, matchmaking queues) |
| **Data — durable** | Kafka (event log), PostgreSQL (player profiles, history) |
| **Data — analytics** | ClickHouse (session metrics, action histograms) |
| **Infra** | Kubernetes (EKS/GKE), Istio service mesh, Prometheus + Grafana, OpenTelemetry |

---

## 1. Full System Architecture

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║                                  CLIENTS                                        ║
║   iOS/Android App    Web Browser (JS)    Desktop (native)    Spectator (web)    ║
╚═══════════╤═════════════════╤══════════════════╤═══════════════════╤════════════╝
            │ WSS             │ WSS              │ gRPC-web          │ WSS (RO)
            ▼                 ▼                  ▼                   ▼
╔══════════════════════════════════════════════════════════════════════════════════╗
║                         EDGE LAYER                                              ║
║  ┌───────────────────────────────────────────────────────────────────────────┐  ║
║  │  Cloudflare (CDN + DDoS + TLS termination + Anycast BGP)                  │  ║
║  │  • Static assets cached at edge                                           │  ║
║  │  • WebSocket proxy with health-check routing                              │  ║
║  │  • Rate-limit: 10 req/s per IP (pre-auth), 100 req/s per playerId         │  ║
║  └───────────────────────────────────────────────────────────────────────────┘  ║
╚══════════════════════════════╤═══════════════════════════════════════════════════╝
                               │
            ┌──────────────────┼──────────────────┐
            │   REGION US-EAST │                  │   REGION EU-WEST (active-active)
            ▼                  │                  ▼
╔═══════════════════╗          │        ╔═══════════════════╗
║  WS GATEWAY TIER  ║          │        ║  WS GATEWAY TIER  ║
║  (200 pods)       ║          │        ║  (100 pods)       ║
║  50K conn / pod   ║          │        ║  50K conn / pod   ║
╚════════╤══════════╝          │        ╚════════╤══════════╝
         │ gRPC                │                 │ gRPC
         ▼                     │                 ▼
╔═══════════════════════════════════════════════════════════════════════════════╗
║                      SERVICE MESH  (Kubernetes + Istio)                      ║
║                                                                               ║
║  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐ ║
║  │  Matchmaker │  │   Session   │  │  Game State  │  │     Presence       │ ║
║  │   Service   │  │   Service   │  │   Service    │  │     Service        │ ║
║  │  (3 pods)   │  │  (20 pods)  │  │  (10 pods)   │  │    (5 pods)        │ ║
║  └──────┬──────┘  └──────┬──────┘  └──────┬───────┘  └─────────┬──────────┘ ║
║         │                │                 │                    │            ║
║         └────────────────┴─────────────────┴────────────────────┘            ║
║                                       │                                       ║
║   ┌───────────────────────────────────┼───────────────────────────────────┐   ║
║   │               ASYNC WORKERS       │                                   │   ║
║   │  ┌──────────┐  ┌──────────┐  ┌───┴──────┐  ┌──────────────────────┐  │   ║
║   │  │Anti-cheat│  │  Replay  │  │   ELO    │  │ Notification Worker  │  │   ║
║   │  │Consumer  │  │Recorder  │  │ Updater  │  │ (push/SMS fallback)  │  │   ║
║   │  └──────────┘  └──────────┘  └──────────┘  └──────────────────────┘  │   ║
║   └───────────────────────────────────────────────────────────────────────┘   ║
╚═══════════════════════════════════════════════════════════════════════════════╝
         │                │                 │
         ▼                ▼                 ▼
╔══════════════╗  ╔═══════════════╗  ╔═════════════════╗  ╔══════════════════╗
║  Redis       ║  ║    Kafka      ║  ║   PostgreSQL     ║  ║   ClickHouse     ║
║  Cluster     ║  ║  (3 brokers)  ║  ║  (primary +      ║  ║  (analytics)     ║
║  (6 shards)  ║  ║  replication  ║  ║   read replica)  ║  ║                  ║
║  hot state   ║  ║  factor 3     ║  ║   player data    ║  ║  session metrics ║
╚══════════════╝  ╚═══════════════╝  ╚═════════════════╝  ╚══════════════════╝
```

---

## 2. Component Deep-Dives

### 2.1 Gateway Service

**Responsibilities:** TLS termination, JWT validation, WebSocket lifecycle, protocol fan-in/fan-out.

#### 2.1.1 Connection lifecycle

```
Client                         Gateway Pod                     Internal Services
  │                                │                                  │
  │── WSS handshake ──────────────►│                                  │
  │                                │── verify JWT (local cache) ─────►│ AuthService
  │                                │◄─ {playerId, claims} ────────────│
  │                                │── register conn in local map     │
  │                                │── SET presence:{playerId} EX 15 ►│ Redis
  │◄── 101 Switching Protocols ────│                                  │
  │                                │                                  │
  │  [every 5 s]                   │                                  │
  │── PING frame ─────────────────►│                                  │
  │◄── PONG frame ─────────────────│── SET presence:{playerId} EX 15 ►│ Redis
  │                                │                                  │
  │── {type:ACTION, payload} ─────►│── gRPC SessionService.Submit() ─►│
  │                                │◄─ {actionId} ────────────────────│
  │◄── {type:ACK, actionId} ───────│                                  │
  │                                │                                  │
  │    [Kafka event fan-out]        │◄── Kafka consumer (session-events.*)
  │◄── {type:SESSION_EVENT, ...} ──│                                  │
```

#### 2.1.2 Stickiness and fan-out

- **Stickiness:** L7 load balancer (NGINX / Envoy) hashes on `playerId` cookie. On reconnect, the same pod receives the connection to reuse the JWT cache.
- **Fan-out:** Each gateway pod is a Kafka consumer on `session-events.*`. The pod maintains an in-memory map `sessionId → [conn1, conn2, ...]`. On each consumed event it checks the map and writes to locally-connected clients; remote clients are reached by other pods consuming the same event from Kafka.

#### 2.1.3 Backpressure

If a client's write buffer is full (slow consumer), the gateway drops the client connection after a configurable drain timeout (default 2 s) and relies on the reconnect flow to resync state.

#### 2.1.4 Scaling formula

```
pods_needed = ceil(peak_concurrent_players / connections_per_pod)
            = ceil(10_000_000 / 50_000)
            = 200 pods
```

Each pod: 4 vCPU · 8 GB RAM · 2 Gbps NIC. HPA triggers at 70 % CPU or 80 % connection capacity.

---

### 2.2 Session Service

**Single source of truth for session state.** Stateless pods; all durable state lives in Redis.

#### 2.2.1 Session lifecycle state machine

```
                  createSession(config, creator)
                           │
                           ▼
              ┌────────────────────────┐
              │   WAITING_FOR_PLAYERS  │◄─── joinSession(player)
              │                        │
              │  minPlayers not yet    │
              │  reached               │
              └──────────┬─────────────┘
                         │ startSession()
                         │ [requires ≥ minPlayers]
                         ▼
              ┌────────────────────────┐
              │        STARTING        │  ready-check ACK window
              │                        │  (default 10 s timeout)
              └──────────┬─────────────┘
                         │ all players ACK "ready"
                         ▼                        leaveSession() or
              ┌────────────────────────┐         notifyDisconnect()
              │         ACTIVE         │──────────────────────────┐
              │                        │◄─────────────────────────┤
              │  submitAction() OK     │     notifyReconnect()    │
              └──────────┬─────────────┘     (within grace)       │
                    │    │                                         ▼
          endSession()   │ notifyDisconnect()          ┌────────────────────────┐
                    │    │ [player drops]               │         PAUSED         │
                    │    └────────────────────────────► │                        │
                    │                                   │  grace timer running   │
                    │                                   └──────────┬─────────────┘
                    │                                              │ grace expires
                    │                                              │ (TTL key)
                    │                                              ▼
                    │                              ┌────────────────────────────┐
                    │                              │         ABANDONED          │ (terminal)
                    │                              └────────────────────────────┘
                    ▼
        ┌────────────────────────┐
        │         FINISHED       │ (terminal)
        └────────────────────────┘
```

#### 2.2.2 Atomic state transitions via Redis Lua

Every state transition runs a Lua script atomically on the Redis node that owns the session's hash slot:

```lua
-- transition_state.lua
-- KEYS[1] = session hash key
-- ARGV[1] = expected current state
-- ARGV[2] = next state
-- ARGV[3] = updatedAt epoch ms
-- Returns 1 on success, 0 on wrong current state, -1 if key missing

local key = KEYS[1]
if redis.call('EXISTS', key) == 0 then return -1 end
local current = redis.call('HGET', key, 'state')
if current ~= ARGV[1] then return 0 end
redis.call('HMSET', key,
    'state',       ARGV[2],
    'updatedAt',   ARGV[3])
redis.call('EXPIRE', key, redis.call('HGET', key, 'sessionTtlSec'))
return 1
```

This eliminates the read-modify-write race without distributed locks.

#### 2.2.3 Idempotency

```
Per-session Redis Sorted Set:
  Key:   session:{sessionId}:actions
  Score: processedAt epoch ms
  Member: idempotencyKey

On submitAction():
  1. ZADD NX session:{sessionId}:actions <now> <idempotencyKey>
     → returns 1 (novel) or 0 (duplicate)
  2. If 0 → reject with DUPLICATE_ACTION error
  3. Prune old entries: ZREMRANGEBYSCORE ... 0 (now - 3600000)
     (keep last 1h of keys, sufficient for any retry window)
```

#### 2.2.4 Reconnect grace timer

```
On notifyDisconnect(sessionId, playerId):
  SET session:{sessionId}:grace:{playerId}  <disconnectEpoch>  EX 30
  → Redis keyspace notification "__keyevent@0__:expired" fires after 30 s
  → Session Service consumer: onGraceExpired() → ABANDONED

On notifyReconnect(sessionId, playerId):
  DEL session:{sessionId}:grace:{playerId}  → cancels timer
  → resume ACTIVE if all players present
```

---

### 2.3 Matchmaker Service

#### 2.3.1 Queue architecture

```
Redis Sorted Sets (one per game-type × skill-bracket):

  matchmaking:{gameType}:{bracket}
    score  = enqueuedAt epoch ms  (FIFO within bracket)
    member = playerId

Brackets (configurable per game type):
  0–999   (bronze)
  1000–1499 (silver)
  1500–1999 (gold)
  2000+     (diamond)
```

#### 2.3.2 Matching algorithm — progressive relaxation

```
Each 500 ms tick per queue:

  Round 1 (t = 0–5 s):   exact bracket match
  Round 2 (t = 5–15 s):  adjacent bracket ±1
  Round 3 (t = 15–30 s): any bracket (ELO diff ≤ 300)
  Round 4 (t > 30 s):    fill with any available player

Score → skill rating stored in player profile (Postgres).
Strategy interface allows plugging in ML-based ranking later.
```

#### 2.3.3 Leader election

Only one Matchmaker pod processes each game-type queue to prevent duplicate matches. Leader election uses a Redis lock:

```
SET matchmaking:leader:{gameType}  {podId}  NX  EX 10
→ winner runs the tick loop and refreshes the key every 5 s
→ on pod crash: lock expires in 10 s; standby pod acquires it
```

#### 2.3.4 Matchmaking flow (sequence)

```
Player A               Player B           Matchmaker         Session Svc       Gateways A/B
  │                       │                   │                  │                   │
  │── enqueue(CHESS,1200) ──────────────────►│                  │                   │
  │                       │── enqueue(CHESS,1250) ─────────────►│                  │
  │                       │                   │                  │                   │
  │                       │                   │── [tick 500ms]   │                   │
  │                       │                   │── ZRANGE bracket │                   │
  │                       │                   │── match(A,B) ──►│                   │
  │                       │                   │                  │── createSession() │
  │                       │                   │                  │── HMSET session   │
  │                       │                   │◄── sessionId ────│                   │
  │                       │                   │── ZREM A, B      │                   │
  │                       │◄──── push MATCH_FOUND {sessionId} ────────────────────►│
  │── joinSession() ───────────────────────────────────────────►│                   │
  │                       │── joinSession() ─────────────────────►│                  │
  │── startSession() ──────────────────────────────────────────►│                   │
  │                       │◄─── SESSION_STARTED (Kafka fan-out) ──────────────────►│
  │                       │                   │                  │                   │
```

---

### 2.4 Game State Service

**Stateless rule engine.** Each game type registers a `GamePlugin`:

```java
public interface GamePlugin {
    String getGameType();
    GameState newGame(SessionConfig config, List<Player> players);
    ValidationResult validate(GameState state, GameAction action);
    StateDelta apply(GameState state, GameAction action);
    boolean isTerminal(GameState state);
    String getWinnerId(GameState state);
}
```

Registered at startup via a plugin registry. New game types (Chess, Poker, Battle Royale) are added by implementing `GamePlugin` — the session infrastructure never changes.

#### 2.4.1 Action processing pipeline

```
submitAction(sessionId, action)
         │
         ▼
 [Session Service]
 1. Load session from Redis                         (~1 ms)
 2. Check session.state == ACTIVE                   (local)
 3. Check playerId ∈ session.players                (local)
 4. ZADD NX idempotency set (dedup)                 (~1 ms Redis)
         │
         ▼
 [Game State Service]
 5. HGETALL session:{id}:gs  (load game state)      (~2 ms Redis)
 6. plugin.validate(state, action)                  (< 1 ms CPU)
 7. delta = plugin.apply(state, action)             (< 1 ms CPU)
 8. HSET session:{id}:gs (write new state)          (~1 ms Redis)
         │
         ▼
 [Session Service]
 9. Kafka producer.send(session-events, delta)      (~5 ms async)
 10. Return ACK{actionId} to Gateway                (total ~10 ms internal)

 [Gateway]
 11. Forward ACK to Player A                        (~5–40 ms network)

 [Kafka fan-out consumer]
 12. Push delta to Players B..N via Gateway         (~5–50 ms)

 Total p99 ACK: ≤ 100 ms end-to-end
 Total p99 fan-out: ≤ 150 ms
```

---

### 2.5 Presence Service

```
Client heartbeat every 5 s:
  Gateway writes:  SET presence:{playerId}  "1"  EX 15

Redis keyspace notification on expire:
  → Presence Service receives expired event
  → Looks up player's currentSessionId from Redis
  → Calls Session Service.notifyDisconnect()

Normal disconnect (WS close frame):
  → Gateway DELs presence key immediately
  → Same notifyDisconnect() path

Heartbeat intervals and grace periods:
  heartbeat:    5 s
  presence TTL: 15 s  (3× heartbeat — survives 2 missed beats)
  reconnect grace: 30 s  (started by Session Service on disconnect)
```

---

### 2.6 Event Bus (Kafka)

#### 2.6.1 Topic layout

| Topic | Partitions | Key | Retention | Consumers |
|---|---|---|---|---|
| `session-events.{gameType}` | 100 | `sessionId` | 7 days | Gateway fan-out, Anti-cheat, Replay Recorder |
| `session-lifecycle` | 20 | `sessionId` | 30 days | ELO Updater, Analytics, Audit log |
| `matchmaking-events` | 10 | `gameType` | 7 days | Analytics, Wait-time monitors |
| `presence-events` | 50 | `playerId` | 1 day | Session Service disconnect handler |
| `notification-requests` | 20 | `playerId` | 1 day | Notification Worker (push/SMS) |

#### 2.6.2 Ordering guarantee

`sessionId` as Kafka key guarantees all events for a session land on the same partition and are delivered in order to each consumer group. This is critical for:
- **Replay recorder** — must reconstruct exact game sequence
- **Anti-cheat** — must see actions in submission order
- **Fan-out** — clients must see state deltas in order

#### 2.6.3 Consumer group isolation

```
Topic: session-events.CHESS
Partitions: 100

Consumer groups:
  gateway-fanout       → reads all partitions; pushes to WebSocket clients
  anti-cheat-chess     → reads all partitions; validates action sequences
  replay-recorder      → reads all partitions; writes to S3 cold storage
  analytics-sink       → reads all partitions; sinks to ClickHouse

Each group maintains independent offsets → failure of one group
does not affect others. Replay recorder falling behind does not
delay fan-out.
```

---

### 2.7 Session Store (Redis Cluster)

#### 2.7.1 Full key schema

```
# Session metadata
session:{sessionId}                  HASH
  fields: sessionId, gameType, state, minPlayers, maxPlayers,
          createdAt, startedAt, lastActivityAt, sessionTtlSec,
          reconnectGraceSec, hostPlayerId

# Players in session
session:{sessionId}:players          SET    (playerIds)
session:{sessionId}:spectators       SET    (spectator playerIds)

# Game state (opaque to session layer)
session:{sessionId}:gs               HASH   (game-type-specific fields)

# Idempotency (action dedup)
session:{sessionId}:actions          ZSET   score=processedAt, member=idempotencyKey

# Reconnect grace timers
session:{sessionId}:grace:{playerId} STRING  TTL = reconnectGraceSec

# Player hot state
player:{playerId}                    HASH
  fields: playerId, displayName, state, currentSessionId,
          eloRating, region

# Presence
presence:{playerId}                  STRING  TTL = 15s  (value = gatewayPodId)

# Matchmaking queues (per game-type, per skill bracket)
matchmaking:{gameType}:{bracket}     ZSET   score=enqueuedAt, member=playerId

# Matchmaker leader lock
matchmaking:leader:{gameType}        STRING  TTL = 10s  (value = podId)

# Lobby (private room waiting for players)
lobby:{lobbyId}                      HASH
  fields: lobbyId, hostPlayerId, gameType, state, createdAt
lobby:{lobbyId}:players              SET    (playerIds)
```

#### 2.7.2 Hash tagging for cluster slot co-location

All keys for a session use the `{sessionId}` hash tag to guarantee they land on the same Redis Cluster slot. This allows pipelining all session reads/writes in a single round-trip and avoids cross-slot Lua script restrictions:

```
session:{abc-123}            ┐
session:{abc-123}:players    │  all on slot hash("abc-123")
session:{abc-123}:gs         │
session:{abc-123}:actions    │
session:{abc-123}:grace:*    ┘
```

#### 2.7.3 TTL strategy

| Key | TTL reset trigger | Expiry action |
|---|---|---|
| `session:{id}` | every write | Session evicted; Keyspace notification → SESSION_ABANDONED |
| `presence:{playerId}` | every heartbeat | Presence Service → notifyDisconnect |
| `session:{id}:grace:{pid}` | set on disconnect, DEL on reconnect | Session Service → ABANDONED |
| `matchmaking:leader:{gt}` | every 5 s by leader | Standby pod acquires lock |

---

### 2.8 Async Workers

#### 2.8.1 Anti-cheat Consumer

Reads `session-events.*` and flags suspicious patterns:

| Pattern | Detection method |
|---|---|
| Action submission rate > N/s | Sliding window counter per player |
| Impossible move sequence | Rule-book replay; deterministic validator |
| Clock drift (submittedAt vs. processedAt gap) | Statistical baseline per game type |
| Multiple account fingerprint | Device fingerprint hash comparison |

Flagged events publish to `anti-cheat-alerts` topic → manual review queue → automated ban pipeline.

#### 2.8.2 Replay Recorder

Writes ordered action log to S3 for each session:

```
s3://game-replays/{gameType}/{year}/{month}/{day}/{sessionId}.jsonl

Each line: {"actionId":"...","playerId":"...","actionType":"...","payload":{...},"processedAt":...}
```

A replay server loads the file and re-runs `plugin.apply()` deterministically to reconstruct any game state at any point in time.

#### 2.8.3 ELO Updater

Consumes `session-lifecycle` events for `SESSION_FINISHED`. Reads winner/loser from payload, loads current ELO ratings from PostgreSQL, computes new ratings using Elo formula, writes back:

```
K  = 32   (provisional) / 16 (established, > 30 games)
E_A = 1 / (1 + 10^((R_B - R_A)/400))
R_A_new = R_A + K * (S_A - E_A)   where S_A = 1 (win), 0.5 (draw), 0 (loss)
```

Processed in batches of 100; at-least-once delivery + idempotency via game session ID.

---

## 3. Data Flow Diagrams

### 3.1 Player Action — Happy Path (full detail)

```
Player A                   Gateway Pod            Session Svc         Game State Svc       Kafka
   │                           │                      │                     │                │
   │──{type:ACTION,            │                      │                     │                │
   │   actionType:"MOVE",      │                      │                     │                │
   │   payload:{row:0,col:0},  │                      │                     │                │
   │   idempotencyKey:"a-1"}──►│                      │                     │                │
   │                           │                      │                     │                │
   │                           │── gRPC SubmitAction ►│                     │                │
   │                           │   {sessionId,action} │                     │                │
   │                           │                      │                     │                │
   │                           │                      │ ┌─ Redis pipeline ─┐│                │
   │                           │                      │ │ HGET state       ││                │
   │                           │                      │ │ ZSCORE actions   ││ (idempotency)  │
   │                           │                      │ │ SMEMBERS players ││                │
   │                           │                      │ └──────────────────┘│                │
   │                           │                      │                     │                │
   │                           │                      │── gRPC ValidateAct ►│                │
   │                           │                      │   {gameState,action}│                │
   │                           │                      │◄─ {valid,delta} ────│                │
   │                           │                      │                     │                │
   │                           │                      │ ┌─ Redis write ─────┐                │
   │                           │                      │ │ ZADD actions      │                │
   │                           │                      │ │ HSET gs (delta)   │                │
   │                           │                      │ │ EXPIRE session    │                │
   │                           │                      │ └───────────────────┘                │
   │                           │                      │                                      │
   │                           │                      │── Kafka produce ────────────────────►│
   │                           │                      │   {ACTION_SUBMITTED, delta}          │
   │                           │                      │                                      │
   │                           │◄── gRPC ACK ─────────│                                      │
   │◄──{type:ACK,actionId}─────│                      │                                      │
   │                           │                      │                                      │
   │        (~10–40 ms total)  │                      │              [async, 5–50 ms later]  │
   │                           │◄─────────────────────────────── Kafka consume ──────────────│
   │                           │── push delta to locally-connected B, C...                   │
```

### 3.2 Disconnect → Reconnect (full detail)

```
Player B         Gateway Pod A     Gateway Pod B'     Presence Svc    Session Svc      Redis
  │                   │                  │                  │               │              │
  │ [TCP drops]       │                  │                  │               │              │
  │                   │                  │                  │               │              │
  │                   │── WS close ─────►│[Presence Svc]    │               │              │
  │                   │ (EOF detected)    │── DEL presence:B ────────────────────────────►│
  │                   │                  │               ─► │               │              │
  │                   │                  │                  │── notifyDisconnect(sid, B) ─►│
  │                   │                  │                  │               │── Lua CAS    │
  │                   │                  │                  │               │   ACTIVE→   │
  │                   │                  │                  │               │   PAUSED ──►│
  │                   │                  │                  │               │── SET grace  │
  │                   │                  │                  │               │   :B EX30 ──►│
  │                   │                  │                  │               │              │
  │     [< 30 s later]│                  │                  │               │              │
  │── WSS connect() ──────────────────►│ (same or new pod)  │               │              │
  │── JWT auth ───────────────────────►│                    │               │              │
  │                                    │── validate JWT     │               │              │
  │                                    │── SET presence:B ──────────────────────────────►│
  │                                    │── notifyReconnect(sid, B) ─────────────────────►│
  │                                    │                    │               │── DEL grace  │
  │                                    │                    │               │   :B ───────►│
  │                                    │                    │               │── Lua CAS    │
  │                                    │                    │               │   PAUSED→   │
  │                                    │                    │               │   ACTIVE ──►│
  │◄── sync full game state ───────────│◄───────────────────────────────── │              │
  │                                    │                    │               │              │
```

### 3.3 Private Lobby → Session Start

```
Host (Alice)           Guest (Bob)           Session Svc       Redis       Gateway A/B
  │                       │                      │               │               │
  │── createLobby() ─────────────────────────►│               │               │
  │                       │                      │── HMSET      │               │
  │                       │                      │   lobby:{id} ─────────────►│               │
  │◄── {lobbyId} ─────────────────────────────│               │               │
  │                       │                      │               │               │
  │── share lobbyId via out-of-band (link/QR) ──►│              │               │
  │                       │── joinLobby(lobbyId) ─────────────►│               │
  │                       │                      │── SADD       │               │
  │                       │                      │   lobby:members ──────────►│               │
  │◄── PLAYER_JOINED push ──────────────────────────────────────────────────►│ (fan-out)
  │                       │                      │               │               │
  │── startLobby(lobbyId) ────────────────────►│               │               │
  │                       │                      │── createSession()            │               │
  │                       │                      │── joinSession(bob)           │               │
  │                       │                      │── startSession()             │               │
  │                       │                      │── DEL lobby:{id}  ────────►│               │
  │                       │◄── SESSION_STARTED (Kafka fan-out) ──────────────────────────────►│
```

---

## 4. API Contracts

### 4.1 Client WebSocket Protocol

All messages are JSON-framed. Binary (Protobuf) framing is an optional upgrade for native clients.

#### Client → Server (inbound)

```json
// Authenticate after WS connect
{ "type": "AUTH", "token": "<JWT>" }

// Enqueue for matchmaking
{ "type": "ENQUEUE", "gameType": "CHESS", "mode": "RANKED" }

// Cancel matchmaking
{ "type": "DEQUEUE" }

// Join a specific session (after MATCH_FOUND or lobby invite)
{ "type": "JOIN_SESSION", "sessionId": "<uuid>" }

// Signal ready (used during STARTING state)
{ "type": "PLAYER_READY", "sessionId": "<uuid>" }

// Submit a game action
{
  "type": "ACTION",
  "sessionId": "<uuid>",
  "actionType": "MOVE",
  "payload": { "row": 0, "col": 2 },
  "idempotencyKey": "client-uuid-or-seq-number"
}

// Graceful leave
{ "type": "LEAVE_SESSION", "sessionId": "<uuid>" }

// Heartbeat (every 5 s)
{ "type": "PING" }
```

#### Server → Client (outbound)

```json
// Auth result
{ "type": "AUTH_OK",  "playerId": "<uuid>" }
{ "type": "AUTH_ERR", "code": "INVALID_TOKEN", "message": "..." }

// Heartbeat reply
{ "type": "PONG" }

// Matchmaking updates
{ "type": "ENQUEUE_OK",  "gameType": "CHESS", "position": 14 }
{ "type": "MATCH_FOUND", "sessionId": "<uuid>", "players": [...] }

// Session events (all share this envelope)
{
  "type": "SESSION_EVENT",
  "sessionId": "<uuid>",
  "eventType": "SESSION_CREATED | PLAYER_JOINED | PLAYER_LEFT |
                SESSION_STARTING | SESSION_STARTED |
                PLAYER_DISCONNECTED | PLAYER_RECONNECTED |
                SESSION_PAUSED | SESSION_RESUMED |
                ACTION_SUBMITTED | SESSION_FINISHED | SESSION_ABANDONED",
  "payload": { },
  "occurredAt": 1778568922608
}

// Action acknowledgement
{ "type": "ACK", "actionId": "<uuid>", "sessionId": "<uuid>" }

// Error response
{
  "type": "ERROR",
  "code": "SESSION_NOT_FOUND | SESSION_FULL | INVALID_STATE |
           PLAYER_NOT_IN_SESSION | DUPLICATE_ACTION | UNAUTHORIZED",
  "message": "human-readable description",
  "requestId": "<echo>"
}
```

### 4.2 Internal gRPC — SessionService

```protobuf
syntax = "proto3";
package multiplayer.v1;

service SessionService {
  rpc CreateSession    (CreateSessionRequest)   returns (SessionProto);
  rpc JoinSession      (JoinSessionRequest)     returns (SessionProto);
  rpc LeaveSession     (LeaveSessionRequest)    returns (google.protobuf.Empty);
  rpc StartSession     (StartSessionRequest)    returns (google.protobuf.Empty);
  rpc EndSession       (EndSessionRequest)      returns (google.protobuf.Empty);
  rpc SubmitAction     (SubmitActionRequest)    returns (SubmitActionResponse);
  rpc NotifyDisconnect (DisconnectRequest)      returns (DisconnectResponse);
  rpc NotifyReconnect  (ReconnectRequest)       returns (google.protobuf.Empty);
  rpc GetSession       (GetSessionRequest)      returns (SessionProto);
  rpc SyncState        (SyncStateRequest)       returns (SyncStateResponse);
}

message CreateSessionRequest {
  SessionConfigProto config  = 1;
  string             creator_player_id = 2;
}

message SubmitActionRequest {
  string session_id      = 1;
  string player_id       = 2;
  string action_type     = 3;
  bytes  payload         = 4;  // Protobuf-encoded, game-type specific
  string idempotency_key = 5;
  int64  submitted_at    = 6;  // client-side epoch ms
}

message SubmitActionResponse {
  string action_id    = 1;
  bytes  state_delta  = 2;  // Protobuf-encoded delta
  int64  processed_at = 3;
}

message DisconnectResponse {
  int64 grace_expires_at = 1;  // epoch ms when grace window closes
}

message SyncStateRequest {
  string session_id = 1;
  string player_id  = 2;
}

message SyncStateResponse {
  SessionProto session    = 1;
  bytes        game_state = 2;  // full game state for reconnecting player
}
```

### 4.3 Internal gRPC — MatchmakerService

```protobuf
service MatchmakerService {
  rpc Enqueue      (EnqueueRequest)   returns (EnqueueResponse);
  rpc Dequeue      (DequeueRequest)   returns (google.protobuf.Empty);
  rpc GetQueueInfo (QueueInfoRequest) returns (QueueInfoResponse);
}

message EnqueueRequest {
  string player_id   = 1;
  string game_type   = 2;
  string mode        = 3;   // "CASUAL" | "RANKED" | "PRIVATE"
  string region      = 4;   // "us-east-1" | "eu-west-1" | ...
  int32  elo_rating  = 5;
}

message QueueInfoResponse {
  int32 queue_depth       = 1;
  int64 estimated_wait_ms = 2;
  int32 position          = 3;
}
```

### 4.4 REST API (HTTP/2) — Management & Fallback

Used by game clients that cannot maintain WebSocket connections and by internal tooling.

```
POST   /v1/sessions                       Create session (host)
POST   /v1/sessions/{id}/join             Join session
DELETE /v1/sessions/{id}/players/{pid}    Leave session
POST   /v1/sessions/{id}/start            Start session
POST   /v1/sessions/{id}/end              End session
POST   /v1/sessions/{id}/actions          Submit action (polling fallback)
GET    /v1/sessions/{id}                  Get session state
GET    /v1/sessions/{id}/state            Get full game state (reconnect)

POST   /v1/matchmaking/enqueue            Enqueue player
DELETE /v1/matchmaking/dequeue            Dequeue player
GET    /v1/matchmaking/status             Poll match status

POST   /v1/lobbies                        Create private lobby
POST   /v1/lobbies/{id}/join              Join lobby
POST   /v1/lobbies/{id}/start             Start lobby session

GET    /v1/players/{id}/sessions          Session history
```

All endpoints require `Authorization: Bearer <JWT>` header. Rate-limited per `playerId` at the Gateway.

---

## 5. Data Model

### 5.1 PostgreSQL — Persistent Schema

```sql
-- Player profiles (source of truth for identity and history)
CREATE TABLE players (
    player_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name    VARCHAR(32) NOT NULL,
    email           VARCHAR(255) UNIQUE NOT NULL,
    elo_rating      INT         NOT NULL DEFAULT 1200,
    games_played    INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Session history (written asynchronously by ELO Updater after SESSION_FINISHED)
CREATE TABLE session_history (
    session_id      UUID        PRIMARY KEY,
    game_type       VARCHAR(32) NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_sec    INT,
    final_state     VARCHAR(16) NOT NULL,  -- FINISHED | ABANDONED
    winner_id       UUID        REFERENCES players(player_id),
    player_count    SMALLINT    NOT NULL
);

-- Per-session player outcomes
CREATE TABLE session_players (
    session_id      UUID        NOT NULL REFERENCES session_history(session_id),
    player_id       UUID        NOT NULL REFERENCES players(player_id),
    outcome         VARCHAR(8)  NOT NULL,  -- WIN | LOSS | DRAW | ABANDON
    elo_before      INT         NOT NULL,
    elo_after       INT         NOT NULL,
    actions_taken   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, player_id)
);

-- Indexes
CREATE INDEX idx_session_history_game_type      ON session_history(game_type, finished_at DESC);
CREATE INDEX idx_session_players_player_id      ON session_players(player_id, session_id DESC);
CREATE INDEX idx_players_elo                    ON players(game_type_elo) WHERE games_played > 0;
```

### 5.2 Redis Hot Schema (annotated)

```
# Session: TTL = sessionTtlSec, reset on every write
session:{sessionId}            HASH
  sessionId          : "abc-123"
  gameType           : "CHESS"
  state              : "ACTIVE"
  minPlayers         : "2"
  maxPlayers         : "2"
  createdAt          : "1778568900000"
  startedAt          : "1778568922000"
  lastActivityAt     : "1778568960000"
  sessionTtlSec      : "3600"
  reconnectGraceSec  : "30"
  hostPlayerId       : "player-uuid-A"

# Players set: no separate TTL; evicted with session hash
session:{sessionId}:players    SET    → {"player-uuid-A", "player-uuid-B"}

# Game state: game-type specific fields, updated atomically
session:{sessionId}:gs         HASH
  board              : "[[0,0,0],[0,1,0],[0,0,2]]"   (TicTacToe example)
  currentTurn        : "player-uuid-A"
  moveCount          : "3"

# Idempotency: ZSET, score=processedAt, pruned at 1h
session:{sessionId}:actions    ZSET
  "idempotency-key-1" → 1778568922100
  "idempotency-key-2" → 1778568923400

# Reconnect grace timers
session:{sessionId}:grace:{playerId}   STRING  "1778568950000"  EX 30

# Player hot state: TTL 24h (refreshed on login)
player:{playerId}              HASH
  state              : "IN_SESSION"
  currentSessionId   : "abc-123"
  eloRating          : "1250"
  region             : "us-east-1"

# Presence heartbeat
presence:{playerId}            STRING  "gateway-pod-7"  EX 15

# Matchmaking queues (ZSET, score=enqueuedAt)
matchmaking:CHESS:silver       ZSET   → {"player-A": 1778568800000, "player-B": 1778568801000}
matchmaking:CHESS:gold         ZSET   → {"player-C": 1778568802000}

# Matchmaker leader lock
matchmaking:leader:CHESS       STRING  "matchmaker-pod-2"  EX 10
```

### 5.3 Kafka Message Schema

```json
// session-events.{gameType} — published for every session lifecycle change and action
{
  "schema": "session-event/v1",
  "sessionId": "abc-123",
  "eventType": "ACTION_SUBMITTED",
  "occurredAt": 1778568922608,
  "payload": {
    "actionId": "act-uuid",
    "playerId": "player-uuid-A",
    "actionType": "MOVE",
    "stateDelta": { "board": [[0,1,0],[0,0,0],[0,0,0]], "currentTurn": "player-uuid-B" },
    "processedAt": 1778568922615
  }
}

// session-lifecycle — one message per terminal/major transition
{
  "schema": "session-lifecycle/v1",
  "sessionId": "abc-123",
  "eventType": "SESSION_FINISHED",
  "gameType": "CHESS",
  "occurredAt": 1778570000000,
  "payload": {
    "winnerId": "player-uuid-A",
    "loserId":  "player-uuid-B",
    "durationSec": 1100,
    "totalActions": 42
  }
}
```

---

## 6. Non-Functional Design

### 6.1 Scalability

| Dimension | Bottleneck | Strategy |
|---|---|---|
| **Gateway connections** | File descriptors, NIC | 200 pods × 50 K = 10 M; HPA on connection % |
| **Session Service throughput** | Redis round-trips | Pipeline reads; Lua CAS; 20 pods × 25 K RPS = 500 K/s |
| **Redis throughput** | Single-threaded Redis node | Cluster with 6 shards; pipeline; no cross-slot operations |
| **Kafka throughput** | Broker I/O | 3 brokers × 1 GB/s; 100 partitions for 500 K msg/s |
| **Matchmaker** | Per-game-type leader | Shard by game-type; one leader per type; ephemeral |

### 6.2 Availability and Fault Tolerance

| Failure | Detection | Recovery | Impact |
|---|---|---|---|
| Gateway pod crash | Kubernetes liveness probe (5 s) | Pod replaced; clients reconnect to new pod via LB; session survives in Redis | 5–15 s reconnect per affected client |
| Session Service pod crash | gRPC health check | Load balancer routes to healthy pod; next request retried | Single RPC retry; < 100 ms extra |
| Redis primary crash | Sentinel / Cluster failover | Replica promoted in < 10 s; within reconnect grace window | Reads served from replica; < 10 s write unavailability |
| Kafka broker crash | Partition leader re-election | ISR election < 30 s; producers queue messages | Fan-out delayed up to 30 s |
| Matchmaker leader crash | Redis lock TTL expires (10 s) | Standby acquires lock | Matchmaking paused for ≤ 10 s |
| Full AZ failure | CloudWatch / Datadog alert | Traffic shifted to other AZs via health-check routing | < 60 s re-routing |
| Full region failure | DNS failover (Route 53) | Secondary region promoted | < 5 min (RTO) |

**Circuit breakers (Resilience4j):**

```
Session Service → Redis:       CB opens after 50 % errors in 10 s window
                               Fallback: serve cached session state (read-only)

Session Service → Game State:  CB opens after 5 consecutive timeouts
                               Fallback: reject action with 503; client retries

Gateway → Session Service:     CB opens after 10 % errors in 30 s window
                               Fallback: queue action locally; drain on recovery
```

### 6.3 Consistency Model

| Operation | Consistency | Rationale |
|---|---|---|
| State transitions (create/start/pause/end) | **Linearisable** (Redis Lua CAS) | Must not corrupt session state |
| Action submission | **Linearisable** (Redis ZADD NX) | Idempotency and ordering |
| Player presence | **Eventual** (TTL + heartbeat) | Heartbeat jitter acceptable; disconnect detected within 15 s |
| ELO rating update | **Eventual** (Kafka async) | Rating lag of seconds to minutes is acceptable |
| Leaderboards | **Eventual** (read replica) | Staleness of minutes acceptable |

### 6.4 Security Threat Model

| Threat | Mitigation |
|---|---|
| **Unauthenticated access** | JWT required; RS256-signed by Auth Service; verified at Gateway using cached public key |
| **Replay attack (old JWT)** | Short JWT expiry (15 min); refresh token flow |
| **Session hijacking** | `sessionId` (UUID v4) + player JWT must both match on every write |
| **Action injection** | Server-side game rule validation in Game State Service; client cannot dictate outcome |
| **Action replay** | Per-session idempotency key ring; same key rejected with DUPLICATE_ACTION |
| **DDoS** | Cloudflare rate limiting (10 req/s per IP pre-auth); per-player rate limiting at Gateway |
| **Enumeration** | UUID v4 session/player IDs; no sequential IDs exposed |
| **Information leakage** | Spectators receive only committed deltas (no pending state); no player IDs in fan-out beyond session members |
| **Bot detection** | Anti-cheat Consumer: action timing analysis, move pattern ML model |
| **Cheat — state manipulation** | Game State Service is the only writer of game state; client payload is opaque input only |

### 6.5 Rate Limiting

```
Layer 1 — Cloudflare (per IP):
  • Pre-auth: 10 req/s
  • Post-auth: 100 req/s
  • WebSocket frames: 50 frames/s per connection

Layer 2 — Gateway (per playerId, sliding window in Redis):
  • ACTION messages: 20/s (casual) | 60/s (real-time games)
  • ENQUEUE: 1/s
  • JOIN_SESSION: 5/s

Layer 3 — Session Service (per session):
  • Total actions across all players: 200/s
  • Single player: 30/s
```

Exceeded rate limits return `ERROR { code: "RATE_LIMITED", retryAfterMs: <N> }`.

### 6.6 Observability

#### Metrics (Prometheus labels: service, gameType, region)

| Metric | Type | Alert threshold |
|---|---|---|
| `session_active_count` | Gauge | — |
| `session_state_transitions_total` | Counter | — |
| `action_submit_latency_ms` | Histogram (p50/p95/p99) | p99 > 200 ms → PagerDuty |
| `action_submit_errors_total` | Counter by error_code | Error rate > 1 % → Slack |
| `matchmaking_queue_depth` | Gauge by gameType/bracket | > 10 K → PagerDuty |
| `matchmaking_wait_ms` | Histogram (p95) | p95 > 10 s → Slack |
| `reconnect_rate` | Gauge | > 5 % → PagerDuty |
| `session_abandoned_total` | Counter | — |
| `redis_command_latency_ms` | Histogram | p99 > 10 ms → Slack |
| `kafka_consumer_lag` | Gauge by consumerGroup | > 50 K msgs → PagerDuty |
| `gateway_connections_active` | Gauge by pod | > 45 K/pod → scale out |

#### Distributed tracing (OpenTelemetry)

Every inbound action creates a trace spanning:
```
Client (WS frame)
  └── Gateway.receiveAction
        └── SessionService.submitAction
              ├── Redis.pipeline (HGET + ZSCORE + SMEMBERS)
              └── GameStateService.validate + apply
                    ├── Redis.HGETALL (load game state)
                    └── Redis.HSET (write delta)
                          └── Kafka.produce (async span)
```

Traces sampled at 1 % base rate; 100 % for errors and p99+ latency outliers (head-based + tail-based sampling).

---

## 7. Multi-Region Deployment

### 7.1 Topology

```
                        ┌─────────────────────────────────────────┐
                        │        Cloudflare Global Anycast         │
                        │  (Geo-route to nearest healthy region)   │
                        └─────────────┬───────────────┬────────────┘
                                      │               │
                        ┌─────────────▼───┐     ┌─────▼────────────┐
                        │  US-EAST-1      │     │  EU-WEST-1       │
                        │  (primary)      │     │  (active-active) │
                        │                 │     │                  │
                        │  WS Gateway     │     │  WS Gateway      │
                        │  Session Svc    │     │  Session Svc     │
                        │  Matchmaker     │     │  Matchmaker      │
                        │  Redis Cluster  │     │  Redis Cluster   │
                        │  Kafka Cluster  │     │  Kafka Cluster   │
                        └────────┬────────┘     └────────┬─────────┘
                                 │                       │
                        Cross-region replication:
                        • Kafka MirrorMaker 2 (async, ~100 ms lag)
                        • Redis active-passive replication for
                          session state (sync, within 10 ms)
                        • PostgreSQL logical replication (async)
```

### 7.2 Session Routing

Sessions are pinned to the region where they were created. Players crossing regions (rare) are routed to their session's home region via the session registry:

```
Redis global key (replicated):
  session-region:{sessionId}  →  "us-east-1"

On join: Gateway reads home region, proxies request if cross-region
         (< 2 % of traffic; acceptable latency penalty of ~80 ms)
```

### 7.3 Failover

1. CloudWatch health checks detect region degradation (3 consecutive failures in 30 s).
2. Route 53 health-check routing shifts DNS to secondary region.
3. New sessions created in secondary region.
4. Active sessions: clients reconnect to secondary; session state replicated via Redis sync replication (< 10 s RPO for active sessions).
5. Full recovery: < 5 min RTO.

---

## 8. Capacity Estimation

### 8.1 Assumptions

| Parameter | Value | Notes |
|---|---|---|
| Concurrent players | 10 M | |
| % in active sessions | 50 % | 5 M players, 2.5 M sessions (avg 2 players) |
| % in matchmaking | 20 % | 2 M players |
| % idle/lobby | 30 % | 3 M players |
| Active sessions | 100 K | Peak estimate (competitive play skews to short sessions) |
| Avg session duration | 30 min | Turn-based; real-time is shorter |
| Actions/session/s | 2–20 | Casual turn-based to fast real-time |
| Avg action payload | 256 B | |
| Avg game state size | 4 KB | |

### 8.2 Throughput

| Flow | Calculation | Rate |
|---|---|---|
| Actions inbound | 100 K sessions × 5 actions/s avg | **500 K/s** |
| Redis reads (per action: 3 ops) | 500 K × 3 | **1.5 M reads/s** |
| Redis writes (per action: 3 ops) | 500 K × 3 | **1.5 M writes/s** |
| Kafka writes | 500 K × 256 B | **128 MB/s** |
| Events outbound (avg 2 players/session) | 500 K × 2 × 512 B | **512 MB/s** to clients |
| Presence heartbeats | 10 M × (1/5) per s | **2 M writes/s** to Redis |

### 8.3 Redis Sizing

| Data | Per-unit | Count | Total |
|---|---|---|---|
| Session metadata | 4 KB | 100 K | 400 MB |
| Game state | 4 KB | 100 K | 400 MB |
| Idempotency sets | 64 KB | 100 K | 6.4 GB |
| Player hot state | 512 B | 10 M | 5 GB |
| Presence keys | 64 B | 10 M | 640 MB |
| Matchmaking queues | 128 B | 500 K entries | 64 MB |
| **Total** | | | **~13 GB** |

**Cluster:** 6 shards × 2 nodes each = 12 nodes; 32 GB RAM per node → 192 GB available. Redis hot tier uses ~13 GB, leaving ample headroom.

### 8.4 Kafka Sizing

| Metric | Calculation | Value |
|---|---|---|
| Ingest rate | 500 K msg/s × 512 B avg | **256 MB/s** |
| Retention (7 days) | 256 MB/s × 86,400 × 7 | **155 TB** raw |
| With replication factor 3 | 155 TB × 3 | **465 TB** total |
| Brokers needed (10 Gbps NIC) | 256 MB/s ÷ 1.25 GB/s | 3 brokers (with 30 % headroom) |

**Cluster:** 6 brokers (3 replication factor), 100 TB storage each.

### 8.5 Compute (Kubernetes)

| Service | Pods | CPU/pod | RAM/pod | Total CPU | Total RAM |
|---|---|---|---|---|---|
| WS Gateway | 200 | 4 vCPU | 8 GB | 800 vCPU | 1.6 TB |
| Session Service | 20 | 8 vCPU | 16 GB | 160 vCPU | 320 GB |
| Game State Service | 10 | 4 vCPU | 8 GB | 40 vCPU | 80 GB |
| Matchmaker | 3 | 2 vCPU | 4 GB | 6 vCPU | 12 GB |
| Presence Service | 5 | 2 vCPU | 4 GB | 10 vCPU | 20 GB |
| Async Workers (total) | 20 | 4 vCPU | 8 GB | 80 vCPU | 160 GB |
| **Total** | **258** | | | **~1,100 vCPU** | **~2.2 TB RAM** |

---

## 9. Key Design Decisions

### 9.1 Authoritative Server vs. P2P

**Decision: Authoritative server.**

| Criterion | Authoritative Server | P2P |
|---|---|---|
| Anti-cheat | All actions validated server-side | Impossible; peers cannot trust each other |
| Reconnect | Full state sync from server | Requires designated host; breaks if host drops |
| Server cost | Higher (compute + bandwidth) | Lower |
| Latency (action ACK) | +1 RTT to server | Direct peer, lower |
| Consistency | Always single truth | Divergence possible |
| Spectators | Trivial (subscribe to event stream) | Complex (relay through host) |

For a competitive/integrity-critical game platform, authoritative server is the only viable choice.

### 9.2 Redis Lua CAS vs. Distributed Locks

**Decision: Lua CAS scripts.**

Distributed locks (Redlock) require acquiring a lock across 3+ nodes, adding 2–3 extra network round-trips. Lua scripts run atomically on the single Redis node that owns the session's hash slot — one hop, no lock contention, and no expiry/release complexity.

Caveat: Lua scripts cannot span multiple hash slots. Hash tagging (`{sessionId}`) ensures all session keys land on the same slot.

### 9.3 Kafka for Fan-out vs. Redis Pub/Sub

**Decision: Kafka.**

| Criterion | Kafka | Redis Pub/Sub |
|---|---|---|
| Durability | Yes (replicated log) | No (fire-and-forget) |
| Replay | Yes | No |
| Consumer lag tolerance | Consumer group tracks offset | Message lost if consumer slow |
| Ordering | Yes (per partition) | Best-effort |
| Downstream consumers | Many independent groups | All receive the same stream; no independent offsets |

Redis Pub/Sub could be used for ultra-low-latency fan-out (< 5 ms) at the cost of durability. The hybrid approach (Redis Pub/Sub for real-time push, Kafka for durability) adds complexity without proportional benefit for our p50 < 50 ms SLO, which Kafka satisfies.

### 9.4 Session State in Redis vs. Stateful Service Pods

**Decision: Redis.**

Keeping session state in pods would prevent horizontal scaling (every request must hit the same pod) and makes pod restarts catastrophic. Redis Cluster gives us distributed, durable, fast key-value storage with TTL-native session expiry.

### 9.5 WebSocket vs. HTTP Long-Polling vs. Server-Sent Events

**Decision: WebSocket as primary; HTTP polling as fallback.**

| Protocol | Latency | Server load | Bi-directional | Firewall/proxy |
|---|---|---|---|---|
| WebSocket | < 5 ms | Low (persistent conn) | Yes | Some corporate proxies block |
| HTTP long-polling | 50–200 ms | High (many requests) | Simulated | Universal |
| Server-Sent Events | 20–100 ms | Medium | Server→client only | Good |

WebSocket wins on latency and bi-directionality. HTTP polling is offered as a fallback for restricted networks.

### 9.6 Matchmaker Leader Election vs. Partitioned Workers

**Decision: Leader election per game type via Redis SET NX.**

Alternative: partition the matchmaking queue by hash ring across N workers. This adds complexity (rebalancing, consistent hashing) for a workload where the queue fits in < 10 MB of RAM per game type. A single leader is simpler, and failover (< 10 s lock TTL) is well within acceptable matchmaking downtime.

---

## 10. Extension Points

| Capability | Design approach | Effort |
|---|---|---|
| **Real-time multiplayer (FPS/RTS)** | Reduce action pipeline to UDP datagram service; authoritative server remains; add dead reckoning client-side | High — new transport layer |
| **Spectator mode** | Gateway subscribes to session Kafka topic as read-only consumer; no changes to Session Service | Low |
| **Tournaments** | `TournamentService` calls `SessionManager.createSession` per match; listens `SESSION_FINISHED` to advance brackets; bracket state in PostgreSQL | Medium |
| **Skill-based matchmaking** | Implement `SkillBracketStrategy` with ELO bands; add `relaxation` policy | Low — same interface |
| **Regional matchmaking** | Add `region` to `MatchmakingCriteria`; prefer same-region matches; fall back to cross-region after 15 s | Low |
| **In-game chat** | Separate `ChatService`; subscribes to `session-lifecycle` for membership; Kafka topic `chat.{sessionId}` | Medium |
| **Anti-cheat ML** | Extend `Anti-cheat Consumer` with TensorFlow Serving sidecar; action embeddings fed to anomaly model | High |
| **Game replay** | `ReplayRecorder` consumer writes to S3; replay server replays `plugin.apply()` deterministically | Medium |
| **Cross-platform leaderboards** | `ELO Updater` sinks to PostgreSQL + ClickHouse; GraphQL API over read replica | Medium |
| **Persistent lobby (tournament rooms)** | Lobby TTL extended to days; add invite code generation and expiry | Low |

---

## 11. LLD → HLD Migration Path

The pure-Java in-process implementation (`lld/multiplayer/`) can be evolved incrementally to the distributed HLD without a big-bang rewrite:

| Phase | Change | LLD code touched |
|---|---|---|
| **Phase 1: Extract Session Service** | Deploy `SessionManagerImpl` as a standalone Spring Boot service; replace `InMemorySessionStore` with `RedisSessionStore` (implements `SessionStore`) | Only `SessionStore` implementation swapped |
| **Phase 2: Distribute Event Bus** | Replace `SessionEventBus.publish()` with a Kafka producer call; replace in-process listeners with Kafka consumer groups | Only `SessionEventBus` internals |
| **Phase 3: Extract Matchmaker** | Deploy `Matchmaker` as its own service; replace `CopyOnWriteArrayList` queues with Redis Sorted Sets; add leader election | `Matchmaker` internals only |
| **Phase 4: Add Gateway** | Wrap `MultiplayerSessionSystem` behind a WebSocket Gateway; expose gRPC internally | New layer; no LLD changes |
| **Phase 5: Multi-region** | Add Redis cross-region replication; Kafka MirrorMaker; DNS routing | Infrastructure only |

The interface contracts (`SessionManager`, `SessionStore`, `MatchmakingStrategy`, `SessionEventListener`) are already defined with this evolution in mind — swapping implementations does not change call sites.

---

## 12. Disaster Recovery

| Scenario | RTO | RPO | Playbook |
|---|---|---|---|
| Single pod crash | < 30 s | 0 | Kubernetes restart; client reconnects within grace period |
| Redis primary crash | < 10 s write unavailability | 0 (sync replica) | Sentinel auto-failover; clients retry on error |
| Kafka broker crash | < 30 s fan-out delay | 0 (ISR) | Partition leader election; consumers re-balance |
| Single AZ failure | < 2 min | 0 | Cross-AZ LB shifts traffic; Kubernetes reschedules pods |
| Full region failure | < 5 min | < 1 min (analytics) | Route 53 DNS failover; secondary region activated; active sessions replayed from Kafka |
| Data corruption (Redis) | < 15 min | Last Redis AOF checkpoint (< 1 s) | Redis restore from AOF; replay recent Kafka events |
| Kafka topic corruption | < 30 min | Last Kafka snapshot | Restore from MirrorMaker replica |
