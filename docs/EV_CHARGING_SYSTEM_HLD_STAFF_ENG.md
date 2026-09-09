# EV Charging System — High-Level Design (Staff Engineer)

**Product analogy:** ChargePoint / Electrify America / Tesla Supercharger network (multi-operator, open connectors)  
**Focus themes:** **real-time station locator** · **site & grid load balancing** · session metering & billing  
**Target scale:** 50 K stations · 200 K connectors · 5 M registered drivers · 80 K concurrent charging sessions · multi-region

How to read this doc: **picks are in bold**. Every load-bearing pick names what we give up and when to flip.

| § | Contents |
|---|---|
| 0 | SLOs, thesis, decision scoreboard |
| 1–3 | Framing, requirements, capacity |
| 4–6 | Principles, architecture, APIs |
| 7 | Hard problems (locator, availability, load balancing, OCPP, reservations, billing) |
| 8–11 | Failures, observability, evolution, cost |
| 12–13 | Interview timing and Q&A |
| 14 | Trade-off catalog |

---

## 0. Executive Summary

### 0.1 Headline SLOs

| Metric | Target | Why it is the SLO |
|---|---|---|
| Locator search p99 (nearby available) | **< 150 ms** | Map UX feels broken above ~200 ms |
| Availability freshness (connector status) | **< 5 s p99** | Drivers navigate to “green” pins that must not lie |
| Session start ACK (app → charger) | **< 3 s p99** | Includes OCPP round-trip; hardware bound |
| Load-balance reallocation p99 | **< 2 s** | Plug-in / unplug must re-slice site power before overcurrent |
| Site power never exceeds `site_limit_kw` | **hard invariant** | Transformer trip = outage + safety incident |
| Metering accuracy | **±1% vs mid meter** | Billing / utility settlement |
| Availability (locator + session control plane) | **99.95%** | Can degrade map detail; charging start must stay up |
| Availability (OCPP gateway path) | **99.99%** regional | Chargers must stay reachable for stop/emergency |
| RPO (session + meter ticks) | **0 after ACK** | Lost kWh ticks = revenue + trust loss |
| RTO (region loss, locator) | **< 60 s** | Anycast + replica reads |

### 0.2 Architecture in one sentence

> **Drivers hit a geo-indexed locator (Redis GEO + availability bloom/bitmaps) that never talks to chargers; chargers speak OCPP to a regional Charge Point Gateway; Site Load Balancer is the authority for kW allocation per site; Session Service owns lifecycle + metering; Kafka fans out status for map freshness and analytics.**

### 0.3 Core thesis (non-negotiable)

1. **Availability is a cache of truth, not truth.** Source of truth for connector state is the Charge Point (via OCPP). The map is an eventually-consistent projection with a freshness SLO.
2. **Power is a scarce shared resource at the site.** Allocation is a **site-scoped consensus problem**, not a per-connector CRUD update. Never let N independent sessions set max power without a site arbiter.
3. **Split planes:** Locator (read-heavy, geo) ≠ Session control (write, OCPP) ≠ Billing (async, correct).
4. **OCPP is the hardware contract.** Design around Charge Point ↔ Central System; do not invent a proprietary charger protocol in v1.
5. **Load balancing is safety + fairness, not just optimization.** Overcurrent protection first; then SoC-aware / departure-time fairness; then grid DR signals.

### 0.4 Decision scoreboard

| Decision | **Pick** | We give up | Flip when |
|---|---|---|---|
| Product shape | **Network platform** (locate → reserve/start → pay) | Pure CPO-only ops console | You only operate one depot |
| Locator index | **Redis GEO + H3 cell secondary** | Perfect offline maps | Need offline-first → ship tile packs |
| Availability store | **Redis bitmaps / hashes per station + Kafka projection** | Strong consistency on map | Map must be CP-linearizable (almost never) |
| Charger protocol | **OCPP 1.6J / 2.0.1** | Proprietary simplicity | Closed fleet with custom firmware only |
| Site power authority | **Site Load Balancer service (per-site lease in Redis)** | Dumb equal-split firmware only | All sites have local EMS that is authoritative |
| Allocation algorithm | **Water-filling + priority tiers** (min guarantee → fair share → boost) | Optimal MILP | Depot with known schedules → solve offline |
| Session SoT | **Postgres (session) + append-only meter log (Kafka → CH)** | Single-store simplicity | Extreme write QPS on ticks |
| Reservations | **Soft hold 15 min, connector-scoped lease** | Guaranteed stall | Airports / ICE-blocked stalls need hard holds |
| Payments | **Auth hold at start, capture on stop (Stripe/Adyen)** | Prepay wallets only | Closed-loop fleet RFID |
| Grid DR | **Accept utility setpoints as site_limit override** | Ignore grid | No utility partnership |
| Multi-region | **Regional OCPP affinity; global locator with regional shards** | One global WS mesh | <10 K chargers in one metro |

---

## 1. Problem Framing

An EV charging network is **three systems sharing a brand**:

| System | User question | Failure mode that matters |
|---|---|---|
| **Locator** | “Where can I charge *now*, near me, with my connector?” | Green pin → occupied / broken on arrival |
| **Session + metering** | “Start, stop, how many kWh, how much $?” | Orphaned session, lost ticks, double charge |
| **Load balancing** | “Share site/grid capacity without tripping the breaker” | Overcurrent trip, unfair starvation, slow everyone |

Staff-level designs that only draw “Station Service + Map” miss the **site power invariant**. Principal-level designs treat **kW as a first-class resource** with the same seriousness as seats in a booking system.

### 1.1 Actors

| Actor | Needs |
|---|---|
| Driver (app / car OEM) | Locate, navigate, start/stop, pay, receipt |
| Charge Point Operator (CPO) | Onboard stations, set tariffs, monitor uptime |
| Site host (retail / workplace) | Cap site kW, prioritize fleet vs public |
| Utility / aggregator | Demand response, TOU price signals |
| Charger hardware | OCPP heartbeats, meter values, remote start/stop |

### 1.2 Clarifying questions (say these in the first 3 minutes)

1. Public network vs private depot vs both?
2. AC only, DC fast charge, or mixed? (changes power math and session length)
3. Reservations required, or walk-up only?
4. Who is the energy meter of record — charger mid, or external CT?
5. Is load balancing **site transformer limit**, **building EMS**, **utility DR**, or all three?
6. Multi-CPO roaming (OCPI/OICP) in scope?

**Defaults for this design:** public + workplace mixed network; AC + DC; soft reservations; charger as meter of record; site limit + optional utility setpoint; roaming as v2.

---

## 2. Requirements

### 2.1 Functional

**Locator**
- Search nearby stations by lat/lng + radius (or viewport bounds).
- Filter: connector type (CCS1/2, NACS, J1772, CHAdeMO, Type 2), min kW, availability, amenities, pricing, accessibility.
- Return ETA-aware ranking: distance + predicted wait + power fit for remaining SoC.
- Real-time status stream for map viewport (WebSocket / SSE).

**Charging session**
- Authorize (RFID / app / Plug&Charge ISO 15118).
- Remote start / stop via OCPP.
- Stream meter values; finalize energy + cost on stop.
- Receipts, invoices, tax.

**Load balancing**
- Enforce `site_limit_kw` (and optional `circuit_limit_kw` per panel).
- Dynamic reallocation when sessions start/stop or SoC/target changes.
- Honor min power per connector (keep EV awake) and max per connector hardware.
- Ingest utility DR / TOU setpoints as temporary site caps.
- Fairness: no session starved below `min_kw` while others boost (unless priority policy says otherwise).

**Ops**
- Station/connector inventory, firmware status, fault codes.
- Tariffs (TOU, idle fees, membership).
- Alerts: offline CP, overcurrent near-miss, payment failures.

### 2.2 Non-functional

| Property | Target |
|---|---|
| Locator QPS (peak) | 20 K searches/s |
| Status update ingest | 50 K events/s (heartbeats + StatusNotification + MeterValues) |
| Concurrent sessions | 80 K |
| Meter tick rate | 1/60 s typical; 1/10 s during ramp (DC) |
| Map status lag | < 5 s p99 |
| Session durability | RPO 0 after start ACK |
| Multi-tenant CPO isolation | noisy neighbor cannot steal another site’s balancer CPU |

### 2.3 Out of scope (v1)

- Building the charger firmware / PLC.
- Full OCPI roaming hub (design hooks only).
- On-car route planner with multi-stop energy model (OEM problem; we expose APIs).
- Battery health / V2G discharge marketplace (extension in §10).

---

## 3. Capacity Estimation

### 3.1 Inventory & traffic

| Item | Estimate | Notes |
|---|---|---|
| Stations | 50 K | ~4 connectors avg → 200 K connectors |
| Peak concurrent sessions | 80 K | ~40% utilization of network at peak evening |
| Locator searches | 20 K/s peak | Map pans + search; cacheable by H3 cell |
| Status events | ~200 K connectors × heartbeat/5 min ≈ 670/s baseline; spikes to 50 K/s with StatusNotification storms | |
| MeterValues | 80 K × 1/min ≈ 1.3 K/s sustained; 8 K/s if 10 s interval | |

### 3.2 Storage sketch (order of magnitude)

| Store | Volume | Retention |
|---|---|---|
| Station/connector catalog | ~200 K rows | Forever (mutable) |
| Redis GEO + status | ~200 K geo members + hashes | Hot |
| Sessions | 80 K active + ~2 M/day completed | 90 d hot Postgres; archive |
| Meter ticks | ~100 M/day | Kafka 7 d → ClickHouse 2 y |
| Locator search logs | sample 1% | 30 d analytics |

### 3.3 Site load-balancer QPS

Not global: **per site**. Worst case mega-hub: 100 connectors, session churn 1 start/stop per second → balancer decisions ~1–5/s/site. Design for **correctness under partition**, not mega QPS. Horizontal scale = shard by `site_id`.

---

## 4. Core Principles

### 4.1 Site is the consistency boundary for power

A connector cannot “decide” its kW in isolation. The **Site Load Balancer** holds a lease on `site:{id}:power` and publishes `ChargingProfile` (OCPP) to each active connector. Cross-site power sharing is out of band (campus EMS) and enters as an updated `site_limit_kw`.

### 4.2 Locator is AP; session start is CP for that connector

- Map reads: prefer availability; stale-green for a few seconds is OK if we show **last_updated_at**.
- Start session: **conditional allocate** connector (or reservation lease) then OCPP RemoteStart. Two drivers cannot both win the same connector.

### 4.3 Metering is an append-only log

Never overwrite kWh. Ticks append; finalization is a deterministic fold. Disputes replay the log.

### 4.4 Hardware is flaky — design for it

Chargers reboot, lose Wi-Fi, send duplicate StatusNotifications, clock-skew meter timestamps. Idempotency keys + state machines absorb this.

---

## 5. Architecture

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║  DRIVERS / OEM APPs          CPO CONSOLE           UTILITY DR GATEWAY            ║
╚══════════════╤══════════════════════╤══════════════════════╤═════════════════════╝
               │ HTTPS / WSS          │ HTTPS                │ mTLS webhook
               ▼                      ▼                      ▼
╔══════════════════════════════════════════════════════════════════════════════════╗
║  EDGE: CDN + WAF + API Gateway (rate limit, JWT, geo-steer)                      ║
╚══════════════════════════════════╤═══════════════════════════════════════════════╝
                                   │
     ┌─────────────────────────────┼─────────────────────────────┐
     ▼                             ▼                             ▼
┌─────────────┐            ┌──────────────┐            ┌─────────────────┐
│  Locator    │            │   Session    │            │  Tariff / Pay   │
│  Service    │            │   Service    │            │  Service        │
│  (search,   │            │  (start/stop │            │                 │
│   filters,  │            │   state m/c) │            │                 │
│   ranking)  │            │              │            │                 │
└──────┬──────┘            └──────┬───────┘            └────────┬────────┘
       │                          │                             │
       │                          ▼                             │
       │                   ┌──────────────┐                     │
       │                   │ Site Load    │◄── site_limit, DR ──┘
       │                   │ Balancer     │
       │                   └──────┬───────┘
       │                          │ SetChargingProfile
       │                          ▼
       │                   ┌──────────────┐         ┌──────────────────┐
       │                   │ OCPP Gateway │◄───────►│ Charge Points    │
       │                   │ (CSMS)       │  OCPP   │ (stations)       │
       │                   └──────┬───────┘  WS/JSON│                  │
       │                          │                 └──────────────────┘
       ▼                          ▼
┌─────────────┐            ┌──────────────┐         ┌──────────────────┐
│ Redis       │◄──events───│ Kafka        │────────►│ ClickHouse       │
│ GEO+status  │            │ status,meter │         │ analytics        │
└─────────────┘            └──────┬───────┘         └──────────────────┘
                                  ▼
                           ┌──────────────┐
                           │ PostgreSQL   │  catalog, sessions, tariffs, ledger refs
                           └──────────────┘
```

### 5.1 Component map

| Component | Responsibility |
|---|---|
| **Locator Service** | Geo search, filters, ranking, viewport subscriptions |
| **Availability Projector** | Kafka → Redis status; computes free/in-use/faulted |
| **Session Service** | AuthZ, reservation/start/stop, session state machine |
| **Site Load Balancer** | Per-site kW allocation; emits OCPP charging profiles |
| **OCPP Gateway (CSMS)** | Persistent connections to chargers; translate to domain events |
| **Tariff & Payment** | Price quotes, auth holds, capture, idle fees |
| **Catalog Service** | Stations, connectors, amenities, site electrical limits |
| **Notification** | Session complete, stall occupied beyond grace, DR events |

### 5.2 Data stores

| Store | Role | Key / partition |
|---|---|---|
| **PostgreSQL** | Catalog, tariffs, sessions (SoT), payment intents | `site_id`, `session_id` |
| **Redis Cluster** | GEO index, connector status, site power leases, reservation locks | geo + `{site_id}` hash tags |
| **Kafka** | StatusNotification, MeterValues, session events, DLQ | `site_id` or `connector_id` |
| **ClickHouse** | Utilization, energy, revenue, balancer decisions | `(day, site_id)` |
| **Object store** | Firmware packages, CDR archives | prefix by CPO |

---

## 6. APIs (driver-facing sketch)

### 6.1 Locator

```http
GET /v1/stations/nearby?lat=37.77&lng=-122.42&radius_m=5000
  &connector=CCS2&min_kw=50&available_only=true&limit=20

GET /v1/stations/{station_id}

GET /v1/map/viewport?sw_lat=&sw_lng=&ne_lat=&ne_lng=&zoom=
```

**Response fields (station card):** `station_id`, `location`, `distance_m`, `connectors[]` with `{type, max_kw, status, last_updated_at}`, `price_hint`, `predicted_wait_s`, `site_load_pct` (optional transparency).

### 6.2 Session

```http
POST /v1/reservations          { station_id, connector_id?, until }
POST /v1/sessions/start        { station_id, connector_id, payment_method_id, idempotency_key }
POST /v1/sessions/{id}/stop
GET  /v1/sessions/{id}         # live kWh, power_kw, cost_so_far
```

### 6.3 Real-time channels

```
WSS /v1/stream/map?viewport=...     # station status deltas
WSS /v1/stream/sessions/{id}        # meter + power allocation updates
```

### 6.4 CPO / internal

```http
PUT  /v1/sites/{site_id}/power-limit     { site_limit_kw, reason, expires_at? }
POST /v1/ops/chargers/{id}/reset
GET  /v1/ops/sites/{id}/balancer-state
```

---

## 7. Hard Problems

### 7.1 Real-time locator

#### 7.1.1 Geo index

**Pick: Redis `GEOSEARCH` on `stations:geo` + H3 cell sets for viewport fan-out.**

| Approach | Pros | Cons | When |
|---|---|---|---|
| **Redis GEO** | Simple, sub-ms, radius queries | Rebalance on Redis reshard; less ideal for huge viewport polygons | **Default** |
| PostGIS | Rich spatial SQL | Hot path latency & connection cost | Ops analytics, complex polygons |
| Elasticsearch geo | Relevance + geo | Ops heavy for status churn | If search relevance >> distance |
| H3 cell inverted index | Excellent viewport + pub/sub grouping | Cell edge artifacts | Zoomed map tiles, WS subscriptions |

**Algorithm (nearby):**
1. `GEOSEARCH stations:geo FROMLONLAT lng lat BYRADIUS r m COUNT 200`
2. `MGET`/`pipeline HGETALL` status hashes for candidates.
3. Filter connector type / min kW / `status==AVAILABLE`.
4. Rank: `score = w1*distance + w2*predicted_wait + w3*(needed_kw/offered_kw) + w4*price`.
5. Return top K with `last_updated_at` so UI can grey-out stale.

**Viewport:** map H3 cells at zoom-appropriate resolution → `SUNION` station sets → same filter/rank. Subscribe WS to those cell topics.

#### 7.1.2 Availability projection

```
Charge Point --StatusNotification--> OCPP Gateway --Kafka:cp.status-->
   Availability Projector --> Redis HSET station:{id} conn:{n} = AVAILABLE|CHARGING|FAULTED|OFFLINE
                         --> Redis PUBLISH h3:{cell} delta
```

**Freshness rules:**
- Heartbeat miss > 90 s → mark connectors `UNKNOWN` (not AVAILABLE).
- `last_updated_at` older than 60 s → UI shows “status may be stale”.
- Faulted stays faulted until `StatusNotification` clears (don’t flap on single missed tick).

**Anti-lie tactics (reduce green-pin rage):**
- Optimistic occupancy: on reservation/start intent, flip to `RESERVED`/`PREPARING` **before** OCPP ACK; roll back on failure.
- Idle fee + camera/sensor later; v1 uses connector status only.
- Rank down stations with high recent “arrived but occupied” reports.

#### 7.1.3 Caching & scale

- Edge CDN for **static** station cards (name, photos, amenities) TTL 5–15 min; **never** cache live status at CDN.
- Locator pods keep in-process LRU of hot H3 cells (1–2 s TTL) to absorb pan storms.
- Shard Redis by geography (region) — US-West GEO keyspace separate from EU.

---

### 7.2 Connector allocation & reservations

**Pick: Redis lease `connector:{id}:lease` with TTL = reservation window (15 min) or session TTL heartbeat.**

```
START:
  SET lease NX EX 900 value=user/session
  if fail → 409 CONNECTOR_BUSY
  create session PREPARING
  payment auth hold
  OCPP RemoteStartTransaction
  on Accepted → CHARGING; extend lease via heartbeat
  on Rejected/timeout → release lease, void hold, session FAILED
```

Idempotency: `idempotency_key` → same session id on retry.

**Walk-up RFID:** same lease path; authorize via RFID → user mapping; if unknown tag → reject.

---

### 7.3 Load balancing (the core hard problem)

#### 7.3.1 What we are balancing

```
site_limit_kw  (transformer / utility interconnection)
   └── optional circuit_limit_kw[]  (panels)
         └── connector_max_kw[]     (hardware)
               └── ev_max_accept_kw (from MeterValues / ISO 15118)
```

**Invariant:** `sum(allocated_kw[c] for c in site) ≤ site_limit_kw` at all times (plus safety margin, e.g. 5%).

#### 7.3.2 Authority & concurrency

**Pick: one balancer actor per site** — logical single-threaded decision via Redis lock/lease `balancer:{site_id}` held by the pod that owns the site (consistent hash of `site_id` → balancer shard).

Why not “each session updates power independently”? Lost update → overcurrent.

Why not solve in charger firmware only? Firmware equal-split cannot see payment priority, DR setpoints, or cross-circuit constraints centrally; hybrid OK if local EMS is SoT — then cloud becomes observer + policy publisher.

#### 7.3.3 Algorithm — water-filling with tiers

Inputs per active session `i`:
- `min_i` — keep-alive / contract minimum (e.g. 6 kW AC, 15 kW DC)
- `max_i` — min(connector_max, ev_accept, tariff_cap)
- `weight_i` — priority (fleet > member > guest; or departure-time urgency)
- `site_limit` — current effective cap (base − DR curtailment − margin)

```
1. If sum(min_i) > site_limit:
     shed by reverse priority (guests first) OR pause lowest weight
     until sum(min_remaining) ≤ site_limit
2. Allocate each min_i
3. residual = site_limit - sum(min_i)
4. Distribute residual proportional to weight_i * (max_i - min_i)
   (classic weighted water-filling / max-min fairness with caps)
5. Quantize to charger-legal steps (e.g. 1 A AC / 1 kW DC)
6. Publish OCPP SetChargingProfile / TxProfile per connector
7. Persist decision to Kafka for audit
```

**Triggers to recompute:** session start/stop, MeterValues showing EV taper (lower `ev_max_accept`), DR setpoint change, connector fault, reservation activate.

**Latency budget:** recompute < 50 ms CPU; OCPP fan-out < 2 s p99.

#### 7.3.4 Example

Site limit **150 kW**, three DC sessions:

| Session | min | max | weight |
|---|---:|---:|---:|
| A fleet | 30 | 150 | 3 |
| B member | 30 | 150 | 2 |
| C guest | 30 | 100 | 1 |

`sum(min)=90` → residual `60`. Capacity heads: A 120, B 120, C 70. Weighted residual → A 30, B 20, C 10 → final **A 60 / B 50 / C 40 kW**.

When C unplugs: residual grows; A/B refill toward max within 150.

#### 7.3.5 OCPP mapping

- OCPP 1.6: `SetChargingProfile` with `TxProfile` / `ChargePointMaxProfile`.
- OCPP 2.0.1: richer `ChargingNeeds` / `NotifyEVChargingNeeds` for ISO 15118 departure-time aware balancing.
- Always set a **site-level ChargePointMaxProfile** as a backstop if cloud balancer is unreachable (fail-safe local cap).

#### 7.3.6 Grid / utility integration

```
Utility DR --> webhook --> Power Policy Service --> updates site_limit_kw (TTL)
                     --> balancer recomputes --> new profiles
```

TOU: Tariff Service changes **price**; balancer may optionally reduce guest weights at peak price — product policy, not physics.

#### 7.3.7 Failure behavior of balancer

| Failure | Behavior |
|---|---|
| Balancer pod crash | Lock expires (2–5 s); another pod recomputes from Redis session set + last limit |
| OCPP set profile fails | Retry; if persistent, mark connector degraded; reduce its allocation to 0 in next pass |
| Redis site state loss | Rebuild from Session Service (Postgres) + OCPP GetCompositeSchedule |
| Network split cloud↔site | Chargers keep last profile; local `ChargePointMaxProfile` enforces hard cap |

---

### 7.4 Session state machine

```
CREATED → AUTHORIZED → PREPARING → CHARGING ⇄ SUSPENDED_EV / SUSPENDED_EVSE
        → FINISHING → COMPLETED
        → FAILED / INVALID
```

- Meter ticks accepted only in `CHARGING` / `SUSPENDED_*` (energy usually flat when suspended).
- Stop: app/RFID/EVSE Emergency → `RemoteStop` → final MeterValues → cost = tariff fold(ticks) + idle fees → payment capture.

### 7.5 Billing sketch

- Quote at start from Tariff Service (TOU band).
- Auth hold ≈ estimated full charge or cap.
- Finalize from meter log; capture min(hold, actual); release remainder.
- Idle fee after `grace_minutes` if connector still occupied (status Occupied + no power).

CDRs (Charge Detail Records) exported for roaming partners later (OCPI).

### 7.6 Ranking for “best charger for me”

Not only distance:

```
score = distance_m / speed
      + α * queue_wait_estimate
      + β * energy_time_estimate(needed_kwh, allocated_kw_expected)
      + γ * price_per_kwh
      + δ * reliability_score   # historical success start rate
```

`allocated_kw_expected` uses **current site_load_pct** so a “350 kW” stall on a saturated site ranks below a free 150 kW stall.

---

## 8. Failure Modes

| Scenario | Detection | Response |
|---|---|---|
| Charger offline mid-session | Heartbeat miss / WS close | Session → SUSPENDED_EVSE; keep lease; alert driver; finalize on reconnect or timeout policy |
| Over-allocation bug | `sum(alloc) > limit` metric / hardware overcurrent alarm | Emergency shed: broadcast min profiles; page on-call; feature-flag freeze boosts |
| Status storm (flapping) | Event rate z-score per CP | Quarantine CP updates; mark UNKNOWN; don’t flap map |
| Payment capture fail | PSP error | Retry; debt ledger; block new sessions for user |
| Redis GEO region loss | Cluster failover | Locator reads degrade to Postgres/PostGIS fallback (slower) |
| Kafka lag on status | Consumer lag > 5 s | Scale projectors; UI freshness banner |
| DR setpoint nonsense (0 kW) | Policy validation | Clamp to `emergency_min_site_kw`; alert |

---

## 9. Observability

| Signal | Why |
|---|---|
| Locator p50/p99, empty-result rate | UX |
| Status lag histogram (`now - last_updated`) | Freshness SLO |
| `site_power_headroom_kw` | Grid safety |
| `allocation_sum_vs_limit` gauge | Invariant |
| OCPP call success rate / latency | Hardware path |
| Session start success funnel | Auth → lease → RemoteStart → Charging |
| “False available” reports | Product trust |
| Balancer recompute latency | Load-balance SLO |

Tracing: `trace_id` from app start through OCPP correlation id.

Alert pages: invariant breach, OCPP gateway saturation, payment error spike, site overcurrent.

---

## 10. Evolution

| Phase | Scope |
|---|---|
| **v1** | Locator + walk-up/app start + site water-filling + basic TOU tariff |
| **v2** | Soft reservations, idle fees, CPO multi-tenant console, DR webhooks |
| **v3** | OCPI roaming, Plug&Charge (ISO 15118), departure-time optimal allocation |
| **v4** | V2G / bidirectional, campus EMS federation, predictive wait ML |

---

## 11. Cost shape (order-of-magnitude thinking)

Dominated by: **OCPP Gateway persistent connections** (200 K WS), **Redis memory** for geo+status, **Kafka+CH** for meter ticks, **PSP fees**. Balancer CPU is cheap. Optimize meter interval carefully — 10 s ticks everywhere is ~6× storage/ingest of 60 s.

---

## 12. Interview timing (~45 min)

| Min | Do |
|---|---|
| 0–5 | Clarify: public vs depot, AC/DC, reservations, what “load balancing” means (site vs LB servers — joke once, then site kW) |
| 5–10 | Requirements + SLOs + capacity napkin |
| 10–20 | Architecture boxes; emphasize plane split |
| 20–30 | **Deep dive locator** OR **deep dive site balancer** (ask which they care about; cover both briefly) |
| 30–38 | Session + OCPP + failure modes |
| 38–45 | Trade-offs, freshness vs truth, evolution |

**30-second opener:**  
“I’d split this into a read-heavy real-time locator, an OCPP-connected session plane, and a **site-scoped power balancer** that treats kW like inventory with a hard ceiling. The map is an eventually consistent projection; the balancer is strongly consistent per site.”

---

## 13. Likely probe questions

**Q: How do you keep the map from lying?**  
A: Project from OCPP with freshness timestamps; optimistic RESERVED on start; mark UNKNOWN on heartbeat miss; never CDN-cache status; rank by reliability.

**Q: Redis GEO vs PostGIS?**  
A: Redis on the hot path; PostGIS for admin polygons and reporting. Flip if queries are complex and QPS is low.

**Q: Is load balancing round-robin on API servers?**  
A: No — **electrical** load balancing. API LB is boring Kubernetes. The interesting part is `sum(kW) ≤ site_limit`.

**Q: How do you avoid overcurrent if the balancer is down?**  
A: Last profiles stick; ChargePointMaxProfile local cap; sessions can continue at reduced power; new starts may pause if we cannot confirm allocation.

**Q: Fairness vs fleet priority?**  
A: Weighted water-filling; min guarantees first so guests don’t die at 0 kW unless policy explicitly sheds.

**Q: Exactly-once meter ticks?**  
A: At-least-once Kafka + idempotent `(session_id, meter_idx)` / timestamp unique; fold is commutative for energy deltas if we store absolute meter readings (prefer **absolute Wh** counters).

**Q: Multi-region chargers?**  
A: OCPP connections are sticky to the region nearest the site (latency + data residency). Locator is global with regional Redis; cross-region search merges.

**Q: Reservation no-shows?**  
A: TTL lease expiry → release; strike policy / fee for chronic no-shows.

---

## 14. Trade-off catalog

| Topic | **Pick** | Alternative | Flip when |
|---|---|---|---|
| Status SoT | OCPP connector status | Camera/spot sensor fusion | High ICE-blocking pain |
| Map transport | WSS viewport deltas | Poll 5 s | Tiny scale |
| Balancer placement | Cloud per-site actor | On-prem EMS | Site has certified local EMS |
| Algorithm | Weighted water-filling | Equal split / MILP | Equal split for tiny sites; MILP for scheduled depots |
| Reservation | Soft TTL lease | Hard guaranteed stall | Airports, premium SKUs |
| Payments | Auth hold + capture | Prepaid wallet | Fleets / closed loop |
| Meter interval | 60 s steady / 10 s ramp | Always 10 s | Regulatory or UX needs live needle |
| Geo shard | Regional Redis | Global cluster | Single-metro product |
| Roaming | v2 OCPI | v1 home network only | Partnership pressure |
| Plug&Charge | v3 | RFID/app first | OEM contracts |
| Consistency map | AP + timestamps | CP read-your-writes only on session | — |
| Safety margin | 5% headroom | 0% | Never 0% on shared transformers |

---

## 15. Why this is staff-shaped

- Names the **real** load-balancing problem (kW inventory), not HTTP LBs.
- Separates **availability projection** from **allocation truth**.
- Gives a concrete **algorithm** (water-filling), **protocol hook** (OCPP profiles), and **fail-safe** (local max profile).
- Capacity numbers and SLOs that drive Redis/Kafka/Gateway sizing.
- Explicit CAP choices: map AP, site power CP, metering append-only.

---

## Appendix A — Domain glossary

| Term | Meaning |
|---|---|
| **EVSE / connector** | Dispenses power; one vehicle at a time typically |
| **Station / Charge Point** | Physical location unit; 1..N connectors |
| **Site** | Electrical boundary sharing one `site_limit_kw` |
| **CSMS** | Central System (our OCPP Gateway) |
| **CDR** | Charge Detail Record for billing/roaming |
| **DR** | Demand Response utility curtailment |
| **SoC** | State of Charge |

## Appendix B — Minimal schema sketch

```sql
-- PostgreSQL
sites(id, name, site_limit_kw, timezone, cpo_id)
stations(id, site_id, lat, lng, h3_cell, amenities jsonb)
connectors(id, station_id, type, max_kw, status_cache, ocpp_id)
tariffs(id, site_id, rules jsonb, currency)
sessions(id, user_id, connector_id, state, started_at, ended_at,
         kwh, cost_cents, payment_intent_id, idempotency_key UNIQUE)
reservations(id, connector_id, user_id, expires_at, state)

-- Redis
GEOADD stations:geo lng lat station_id
HSET station:{id}:status conn:{n} AVAILABLE last_ts ...
SET lease:connector:{id} {session} EX 900 NX
SET balancer:{site_id}:lock {pod} EX 5
HSET site:{id}:alloc session:{sid} kw
```

## Appendix C — Sequence: start session with load balance

```
Driver          Session          Lease/Redis      Balancer         OCPP GW         Charger
  │ start         │                 │                │                │               │
  │──────────────►│ SET lease NX    │                │                │               │
  │               │────────────────►│                │                │               │
  │               │ payment hold    │                │                │               │
  │               │ recompute ─────►│───────────────►│                │               │
  │               │                 │                │ SetChargingProfile────────────►│
  │               │ RemoteStart ─────────────────────────────────────►│──────────────►│
  │               │                 │                │                │  Accepted     │
  │◄── CHARGING ──│◄──────────────────────────────────────────────────│◄──────────────│
  │               │ MeterValues ... │                │ (recompute if taper)           │
```

---

*End of HLD. Use §0 scoreboard + §7.3 algorithm on the whiteboard; pull failure table from §8 if probed on reliability.*
