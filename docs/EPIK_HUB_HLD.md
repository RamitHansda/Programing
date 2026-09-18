# Epik Hub Operations Platform - High-Level Design

**Problem:** A hub is Epik's operational nerve centre: products live here, demo engineers start and end here, and a hub POC coordinates demos in real time. Today this coordination happens through WhatsApp and gut feel. That works at ~20 demos/day, but breaks at ~80 demos/day because state is scattered, prioritization is manual, and exception handling depends on memory.

**Core constraint:** The hub POC is not technical. The system must be usable on a tablet with zero training.

---

## 0. Executive Summary

### 0.1 One-sentence architecture

> **A tablet-first Hub Console sits on top of a realtime operations backend: demo events, engineer presence, product inventory, and task assignments flow through an orchestration service and event log; the POC sees a single color-coded live board with one-tap actions, while demo engineers use a lightweight mobile workflow for check-in, pickup, demo progress, and return.**

### 0.2 Primary outcomes

| Outcome | What the system must do |
|---|---|
| Run 80+ demos/day per hub | Maintain live demo, engineer, and product state without WhatsApp coordination |
| Give the POC one source of truth | Tablet dashboard answers: what is happening, what is blocked, who owns it, what to do next |
| Reduce training to near zero | Use simple states, large buttons, colors, QR scans, and guided exception flows |
| Keep products accountable | Track product location, readiness, handoff, damage, and return |
| Coordinate engineers in real time | Assign work based on availability, skills, product readiness, route timing, and load |
| Preserve auditability | Every handoff and override becomes an event with actor, time, and reason |

### 0.3 Top-level components

| Layer | Components |
|---|---|
| **Clients** | POC Tablet Console, Demo Engineer Mobile App/PWA, Admin Web Console, optional Hub Display |
| **Edge/API** | API Gateway, Auth, Hub BFF tuned for tablet workflows |
| **Core services** | Hub Orchestration, Demo Scheduling, Assignment Engine, Product/Inventory, Engineer Presence, Task/Exception, Notification |
| **Realtime** | WebSocket/SSE Gateway, event bus, hub projection cache |
| **Data** | PostgreSQL for transactional truth, Redis for live board/cache/locks, Kafka or managed event stream for audit and replay, object storage for photos/docs |
| **Analytics** | Operational metrics, SLA dashboards, bottleneck reports |

---

## 1. Requirements

### 1.1 Functional requirements

#### POC tablet console

- Show all demos for the day as a live board grouped by status: upcoming, ready, in progress, blocked, returning, completed.
- Show product readiness and location: in hub, assigned, with engineer, at customer, returned, damaged, missing.
- Show engineer status: not checked in, available, assigned, travelling, in demo, returning, offline.
- Recommend the next action: assign engineer, prepare product, approve dispatch, call customer, resolve delay, mark return.
- Allow one-tap actions with confirmation only for risky operations.
- Support QR/barcode scan for product handoff and return.
- Surface exceptions as simple cards: "Product not ready", "Engineer late", "Demo running long", "Return overdue".
- Provide a manual override path for the POC with reason capture.

#### Demo engineer workflow

- Check in/out at hub.
- View assigned demos and pickup checklist.
- Scan product before leaving and after returning.
- Mark demo lifecycle: picked up, left hub, arrived, started, completed, returning, returned.
- Report issues with minimal friction: product problem, customer no-show, running late, replacement needed.
- Receive push/in-app notifications for assignments and changes.

#### Product and inventory workflow

- Maintain product catalog and physical inventory units.
- Track each physical unit's current status, hub, owner, and condition.
- Attach readiness checklists by product type.
- Capture return condition and photos for damage/missing accessories.
- Reserve products for scheduled demos and release them on cancellation/return.

#### Admin and planning workflow

- Import or create demos with time, customer, product, location, priority, and required skills.
- Configure hub operating hours, engineer shifts, product types, skill mappings, and escalation rules.
- View historical reports: demo completion, delays, product utilization, engineer utilization, exception reasons.

### 1.2 Non-functional requirements

| Property | Target |
|---|---|
| Per-hub volume | 80 demos/day v1; design for 300 demos/day without architectural change |
| Multi-hub scale | 100 hubs, 30K demos/day aggregate |
| Tablet action latency | p95 < 300 ms for reads from live board; p95 < 1 s for writes |
| Realtime update latency | p95 < 2 s from engineer action to POC board |
| Availability | 99.9% during hub operating hours |
| Offline tolerance | Engineer app queues actions for short network drops; tablet remains readable from last snapshot |
| Auditability | 100% of state changes recorded as immutable events |
| Usability | New POC can run core flows by recognizing colors, icons, and "next action" cards |

### 1.3 Out of scope for v1

- Full route optimization across a city fleet.
- Customer-facing live tracking.
- Automated procurement or replenishment.
- ML-based demand prediction.
- Payroll or performance management.

---

## 2. User Experience Principle: Zero-Training Operations

The highest-risk user is the POC. The interface should behave like an airport operations board, not an enterprise admin panel.

### 2.1 POC console layout

```
+-----------------------------------------------------------------------------+
| HUB: Bengaluru Indiranagar        Today: 80 demos       Health: 3 issues     |
+-----------------+--------------------+------------------+------------------+
| NOW             | NEXT 60 MIN         | BLOCKED          | RETURNS          |
| 12 in progress  | 18 upcoming         | 3 need action    | 9 due soon       |
+-----------------+--------------------+------------------+------------------+
| ACTION CARDS                                                                 |
| [Assign engineer to Demo #142] [Product missing cable] [Return overdue]      |
+-----------------------------------------------------------------------------+
| LIVE DEMO BOARD                                                               |
| 09:30  Customer A  Product X  READY      Engineer: Neha     [Dispatch]       |
| 09:45  Customer B  Product Y  BLOCKED    Product issue      [Fix now]        |
| 10:00  Customer C  Product Z  ASSIGNED   Engineer: Aman     [View]           |
+-----------------------------------------------------------------------------+
```

### 2.2 Interaction rules

- **No hidden workflows:** Every demo card shows its current state and next valid action.
- **No codes or internal jargon:** Use user-facing labels like "Ready to leave" instead of `READY_FOR_DISPATCH`.
- **Color semantics stay fixed:**
  - Green: ready or healthy.
  - Blue: active/in progress.
  - Yellow: attention soon.
  - Red: blocked or overdue.
  - Grey: done/cancelled.
- **One-tap happy path:** Assign, dispatch, start return, and close should be single primary buttons.
- **Guided exception path:** When something is blocked, the UI offers 2-4 concrete options, not a blank text box.
- **QR-first identity:** Product handoffs use scan; manual search is fallback.

---

## 3. Domain Model

### 3.1 Core entities

| Entity | Purpose | Key fields |
|---|---|---|
| `Hub` | Physical operating location | `hub_id`, address, operating hours, timezone |
| `ProductType` | Catalog-level product definition | `product_type_id`, demo duration, checklist, required skills |
| `ProductUnit` | Physical item in a hub | `unit_id`, serial, `product_type_id`, `hub_id`, condition, status |
| `Demo` | Customer demo appointment | `demo_id`, customer, product type, scheduled window, priority, location, status |
| `Engineer` | Demo engineer | `engineer_id`, skills, home hub, current status |
| `Shift` | Engineer availability window | `shift_id`, engineer, hub, start/end, status |
| `Assignment` | Links demo, engineer, and product unit | `assignment_id`, `demo_id`, `engineer_id`, `unit_id`, status |
| `Task` | POC-visible work item | `task_id`, hub, type, severity, owner, due time, status |
| `HubEvent` | Immutable audit/event stream | event id, type, entity refs, actor, timestamp, payload |

### 3.2 Demo lifecycle

```
CREATED
  |
  +-- reserve product + assign engineer
  v
READY_FOR_PREP
  |
  +-- checklist completed
  v
READY_FOR_DISPATCH
  |
  +-- engineer scans product and leaves hub
  v
DISPATCHED
  |
  +-- engineer arrives
  v
AT_CUSTOMER
  |
  +-- demo started
  v
IN_DEMO
  |
  +-- completed / failed / customer no-show
  v
RETURNING
  |
  +-- product scanned at hub + return checklist
  v
COMPLETED

Any active state ---> BLOCKED ---> previous valid state or CANCELLED
```

### 3.3 Product unit lifecycle

```
AVAILABLE
  | reserve for demo
  v
RESERVED
  | prep checklist complete
  v
READY
  | engineer pickup scan
  v
CHECKED_OUT
  | customer demo
  v
IN_FIELD
  | return scan
  v
RETURN_CHECK
  | pass                 | fail
  v                      v
AVAILABLE            NEEDS_ATTENTION / DAMAGED / MISSING
```

### 3.4 Engineer lifecycle

```
OFF_SHIFT -- check-in ---> AVAILABLE -- assign ---> ASSIGNED
                                      |
                                      v
                                PICKING_UP
                                      |
                                      v
                                TRAVELLING
                                      |
                                      v
                                  IN_DEMO
                                      |
                                      v
                                 RETURNING
                                      |
                                      v
                                AVAILABLE
```

---

## 4. High-Level Architecture

```
+------------------------------------------------------------------------------+
|                                  CLIENTS                                      |
|  POC Tablet PWA       Engineer Mobile PWA       Admin Web       Hub Display  |
+---------------+--------------------+----------------+-----------------------+
                | HTTPS/WSS          | HTTPS/WSS      | HTTPS
                v                    v                v
+------------------------------------------------------------------------------+
|                             EDGE / API LAYER                                  |
|  CDN + WAF  |  API Gateway  |  Auth/RBAC  |  Hub BFF  |  Realtime Gateway     |
+------------------------------+-------------------------------+---------------+
                               | REST/gRPC                     | WebSocket/SSE
                               v                               v
+------------------------------------------------------------------------------+
|                              CORE SERVICES                                    |
|                                                                              |
|  +------------------+   +------------------+   +--------------------------+ |
|  | Hub Orchestrator |   | Scheduling       |   | Assignment Engine        | |
|  | state machines   |   | demo windows     |   | engineer + product match | |
|  +--------+---------+   +--------+---------+   +-----------+--------------+ |
|           |                      |                         |                |
|  +--------v---------+   +--------v---------+   +-----------v--------------+ |
|  | Product Inventory|   | Engineer Presence|   | Task / Exception Service | |
|  | units, checklists|   | shifts, location |   | POC action cards         | |
|  +--------+---------+   +--------+---------+   +-----------+--------------+ |
|           |                      |                         |                |
|  +--------v----------------------v-------------------------v--------------+ |
|  | Notification Service: push, in-app, WhatsApp/SMS fallback if needed     | |
|  +-------------------------------------------------------------------------+ |
+------------------------------+-----------------------------------------------+
                               |
                               v
+------------------------------------------------------------------------------+
|                                 DATA LAYER                                    |
|  PostgreSQL        Redis Cluster          Event Stream          Object Store  |
|  source of truth   live board + locks     audit + projections   photos/docs   |
+------------------------------------------------------------------------------+
                               |
                               v
+------------------------------------------------------------------------------+
|                             ANALYTICS / OPS                                   |
|  ClickHouse/warehouse  |  dashboards  |  alerting  |  replay/debug tooling    |
+------------------------------------------------------------------------------+
```

---

## 5. Component Design

### 5.1 Hub BFF

The Hub BFF is optimized for tablet workflows. It hides backend complexity and returns screen-ready payloads.

Responsibilities:

- Compose demo, engineer, product, and task state into a single `HubSnapshot`.
- Return only valid actions for each card.
- Translate internal states into simple labels and colors.
- Enforce role-specific permissions for POC, engineer, and admin.
- Provide idempotent write endpoints for tablet actions.

Example response shape:

```json
{
  "hubId": "hub_blr_indiranagar",
  "health": {"status": "attention", "blockedCount": 3},
  "actionCards": [
    {
      "taskId": "task_123",
      "title": "Product missing cable",
      "severity": "red",
      "primaryAction": "Find replacement"
    }
  ],
  "demoCards": [
    {
      "demoId": "demo_142",
      "time": "09:30",
      "customerName": "Customer A",
      "product": "Product X",
      "label": "Ready to leave",
      "color": "green",
      "primaryAction": "Dispatch"
    }
  ]
}
```

### 5.2 Hub Orchestrator

The Hub Orchestrator owns the state machines for demos, assignments, and product handoffs.

Responsibilities:

- Validate state transitions.
- Write transactional updates to PostgreSQL.
- Emit immutable `HubEvent` records.
- Create or resolve POC tasks based on state changes.
- Maintain invariants:
  - One active product unit can be assigned to only one active demo.
  - One engineer can be in only one active demo at a time.
  - A demo cannot be dispatched unless engineer and product are both ready.
  - A product cannot return to `AVAILABLE` without a return scan/checklist.

### 5.3 Assignment Engine

The Assignment Engine recommends the best engineer and product unit for each demo.

Inputs:

- Demo scheduled time, priority, product type, customer location, expected duration.
- Product availability, readiness, condition, reservation status.
- Engineer shift, current status, skills, current/last known location, workload.
- Hub operating constraints and manual POC overrides.

Scoring model:

```
score = skill_match
      + product_readiness
      + engineer_availability
      + travel_feasibility
      + load_balance
      + priority_boost
      - conflict_penalty
      - lateness_risk
```

The engine should explain recommendations in human terms:

- "Neha is available and certified for Product X."
- "Aman is still returning from another demo."
- "Unit PX-104 is ready; PX-102 is missing charger."

This is important because the POC must trust the recommendation without understanding scheduling algorithms.

### 5.4 Product Inventory Service

Responsibilities:

- Manage product catalog and physical units.
- Track unit status and condition.
- Enforce QR/barcode scan on handoff and return.
- Attach checklists and required accessories to each product type.
- Capture photos during exception flows.

Key invariant:

> A physical product unit's operational state changes only through scan-backed handoff, checklist completion, admin correction, or explicit POC override.

### 5.5 Engineer Presence Service

Responsibilities:

- Track check-in/check-out.
- Maintain latest engineer status.
- Consume mobile app heartbeats where available.
- Degrade gracefully when location is unavailable.
- Expose availability to Assignment Engine and Hub Console.

Presence should not be the source of truth for completed work; explicit engineer actions and hub scans are.

### 5.6 Task and Exception Service

This service converts operational risk into simple POC action cards.

Examples:

| Trigger | Task shown to POC | Suggested actions |
|---|---|---|
| Product not ready 30 min before demo | "Product X not ready for 10:00 demo" | Prepare now, choose replacement, delay demo |
| Engineer not checked in | "Engineer not at hub" | Reassign, call engineer, mark delayed |
| Return overdue | "Product PX-104 not returned" | Ping engineer, mark issue, assign backup unit |
| Demo running long | "Demo may delay next assignment" | Reassign next demo, extend buffer |
| Product failed return check | "Product damaged" | Send to repair, attach photos, use spare |

### 5.7 Realtime Gateway

Responsibilities:

- Maintain WebSocket/SSE connections for tablet and engineer clients.
- Push hub-scoped updates within seconds.
- Use Redis or event-stream projections to avoid querying PostgreSQL for every refresh.
- Support reconnect by sending a fresh `HubSnapshot` plus missed events if needed.

For the tablet, correctness matters more than microsecond latency. If event replay is uncertain, send a full snapshot.

### 5.8 Notification Service

Channels:

- In-app push for engineer app.
- POC console alerts.
- Optional WhatsApp/SMS fallback for critical exceptions during rollout.

Notification policy:

- P0: dispatch-blocking issue for demo starting soon.
- P1: assignment or schedule change.
- P2: FYI updates visible on board but not interruptive.

---

## 6. Data Storage

### 6.1 PostgreSQL: transactional source of truth

Use PostgreSQL for strongly consistent entities:

- hubs
- product catalog
- product units
- demos
- engineers
- shifts
- assignments
- tasks
- users and roles

Important constraints:

- Unique active assignment per demo.
- Partial unique index to prevent one product unit from being assigned to multiple active demos.
- Partial unique index to prevent one engineer from being assigned to multiple overlapping active demos.
- Foreign keys for catalog and hub ownership.

### 6.2 Event stream: audit and projections

Every state transition emits a `HubEvent`.

Example event types:

- `DEMO_CREATED`
- `PRODUCT_RESERVED`
- `ENGINEER_ASSIGNED`
- `CHECKLIST_COMPLETED`
- `PRODUCT_CHECKED_OUT`
- `DEMO_STARTED`
- `DEMO_COMPLETED`
- `PRODUCT_RETURNED`
- `TASK_CREATED`
- `POC_OVERRIDE_APPLIED`

Uses:

- Rebuild live board projections.
- Debug operational disputes.
- Feed analytics.
- Power notifications.

Partitioning key:

```
hub_id
```

Hub-local ordering is more useful than global ordering because operations are coordinated per hub.

### 6.3 Redis: live operational cache

Use Redis for:

- Current hub snapshot.
- Engineer presence.
- Short-lived assignment locks.
- Idempotency keys for client actions.
- WebSocket fan-out metadata.

Redis is not the durable source of truth. On cache loss, rebuild from PostgreSQL and recent events.

### 6.4 Object storage

Use object storage for:

- Return-condition photos.
- Damage reports.
- Product documents/manuals.
- Customer proof-of-demo attachments if needed.

---

## 7. Key Flows

### 7.1 Morning hub start

```
Admin/Scheduler       Hub Orchestrator       Assignment Engine       POC Tablet
      |                       |                      |                  |
      | creates/imports demos |                      |                  |
      +----------------------->|                      |                  |
      |                       | reserves products    |                  |
      |                       +---------------------->|                  |
      |                       | requests assignments |                  |
      |                       +---------------------->|                  |
      |                       |<----- recommendations +                  |
      |                       | creates tasks/events |                  |
      |                       +----------------------------------------->|
      |                       |                      |       live board |
```

Outcome: The POC opens the tablet and sees what is ready, what needs preparation, and what is blocked.

### 7.2 Product pickup and dispatch

```
Engineer App          Product Service       Hub Orchestrator       POC Tablet
     | scan product          |                    |                    |
     +----------------------->|                    |                    |
     |                       | validate unit      |                    |
     |                       +-------------------->|                    |
     |                       |                    | mark checked out   |
     |                       |                    | emit event         |
     |<-----------------------+                    |                    |
     | "Ready to leave"      |                    +-------------------->|
     |                       |                    | board updates      |
```

If the wrong product is scanned, the engineer sees "Wrong item" and the POC gets no noisy alert unless the demo is at risk.

### 7.3 Exception: product missing accessory

```
Engineer App       Task Service       Assignment Engine       POC Tablet
     | report issue      |                    |                  |
     +------------------->|                    |                  |
     |                   | create red task    |                  |
     |                   +--------------------------------------->|
     |                   | request options    |                  |
     |                   +-------------------->|                  |
     |                   |<--- replacement options                |
     |                   +--------------------------------------->|
     |                   | "Use spare PX-109"                    |
```

The POC sees clear choices:

1. Use spare product.
2. Delay demo.
3. Cancel demo.
4. Override and dispatch anyway.

### 7.4 Return and close

```
Engineer App       Product Service       Hub Orchestrator       Analytics
     | scan return       |                    |                   |
     +------------------->|                    |                   |
     | complete checklist|                    |                   |
     +------------------->|                    |                   |
     |                   | pass/fail result   |                   |
     |                   +-------------------->|                   |
     |                   |                    | close demo/event  |
     |                   |                    +------------------->|
```

If return check passes, the product becomes available. If it fails, a repair/damage task is created.

---

## 8. APIs

### 8.1 POC tablet APIs

```
GET  /hubs/{hubId}/snapshot?date=YYYY-MM-DD
GET  /hubs/{hubId}/events?afterCursor=...
POST /hubs/{hubId}/tasks/{taskId}/actions
POST /hubs/{hubId}/demos/{demoId}/dispatch
POST /hubs/{hubId}/demos/{demoId}/override
```

All write APIs accept:

```json
{
  "idempotencyKey": "uuid-from-client",
  "actorId": "user_123",
  "reason": "optional for risky actions",
  "payload": {}
}
```

### 8.2 Engineer APIs

```
POST /engineers/{engineerId}/check-in
GET  /engineers/{engineerId}/assignments/today
POST /assignments/{assignmentId}/scan-pickup
POST /assignments/{assignmentId}/status
POST /assignments/{assignmentId}/scan-return
POST /assignments/{assignmentId}/issue
```

### 8.3 Admin APIs

```
POST /demos/import
POST /demos
PATCH /demos/{demoId}
POST /products/units
PATCH /products/units/{unitId}
POST /engineers/{engineerId}/skills
POST /hubs/{hubId}/rules
```

---

## 9. Consistency and Concurrency

### 9.1 Why not make the event stream the only source of truth?

The product can be event-sourced later, but v1 should use PostgreSQL as the transaction boundary because the critical invariants are relational:

- Is this product already assigned?
- Is this engineer already busy?
- Is this demo already closed?

The event stream is still mandatory for audit, projections, and analytics.

### 9.2 Concurrency controls

- Use database transactions for assignment, dispatch, return, and override.
- Use optimistic locking with `version` on demos, assignments, and product units.
- Use partial unique indexes to prevent double-booking.
- Use idempotency keys for all client writes.
- Use short Redis locks only as a fast path for assignment attempts; database constraints remain final authority.

### 9.3 Conflict example

Two POCs try to assign the same spare product:

1. Both see unit `PX-109` as available.
2. Both submit assignment.
3. First transaction commits.
4. Second transaction violates active assignment uniqueness.
5. BFF returns a friendly message: "PX-109 was just assigned. Use PX-112 instead?"

---

## 10. Reliability and Offline Behavior

### 10.1 Tablet behavior

- Cache the latest `HubSnapshot` locally.
- If offline, show a clear "Offline - last updated 10:42" banner.
- Disable risky write actions while offline unless explicitly supported.
- Reconnect by fetching a fresh snapshot, not by assuming all missed events applied cleanly.

### 10.2 Engineer app behavior

- Queue low-risk actions locally during short network drops:
  - status updates
  - issue drafts
  - checklist progress
- Require online validation for high-risk actions:
  - product pickup
  - product return
  - assignment acceptance
- If scan happens offline, store scan proof locally and reconcile when online, but mark it as "Needs sync".

### 10.3 Failure scenarios

| Failure | User impact | System response |
|---|---|---|
| Realtime gateway down | Board stops live-refreshing | Tablet polls snapshot endpoint every 10 s |
| Redis cache lost | Slower board load | Rebuild snapshot from PostgreSQL/events |
| Event stream delayed | Analytics/replay lag | Core operations continue through PostgreSQL |
| Engineer mobile offline | POC sees stale engineer state | Mark engineer as "last seen X min ago"; allow call/reassign |
| Product scan device unavailable | Pickup blocked | Manual code entry with POC approval |
| PostgreSQL unavailable | Writes blocked | Read-only board from cache; show incident banner |

---

## 11. Security and Permissions

### 11.1 Roles

| Role | Permissions |
|---|---|
| POC | Run hub board, assign/reassign, dispatch, override with reason, close exceptions |
| Demo engineer | View own assignments, update own status, scan pickup/return, report issues |
| Hub admin | Configure products, shifts, hub rules, imports |
| Ops manager | Cross-hub visibility, reporting, escalations |
| Support auditor | Read-only access to events and history |

### 11.2 Controls

- SSO or passwordless login for POCs where possible.
- Device/session binding for hub tablets.
- RBAC enforced at API layer and service layer.
- Audit log for every override, manual scan, cancellation, and damage update.
- PII minimization on hub board; show only customer details required to run the demo.

---

## 12. Observability

### 12.1 Product metrics

- Demos completed on time.
- Demo delay reasons.
- Product utilization by type and hub.
- Product return SLA.
- Engineer utilization.
- Manual overrides per hub.
- POC action-card resolution time.

### 12.2 System metrics

- Snapshot API latency and error rate.
- WebSocket connection count and fan-out latency.
- Event consumer lag.
- Assignment failure/conflict rate.
- PostgreSQL transaction latency.
- Redis cache hit rate.
- Mobile sync queue depth.

### 12.3 Alerts

Alert on business-impacting symptoms, not just infrastructure:

- More than N demos blocked within next 60 minutes.
- Product return overdue and no spare available for upcoming demo.
- Realtime update lag > 30 seconds for active hub.
- Assignment engine failing or returning no feasible option.
- Hub tablet offline during operating hours.

---

## 13. Scaling

### 13.1 Per-hub sizing

For 80 demos/day:

- Average operational events per demo: ~25.
- Events per hub/day: ~2,000.
- Peak burst: morning prep and return windows.

Even 300 demos/day per hub is modest technically. The hard problem is not raw throughput; it is correctness, realtime visibility, and exception handling.

### 13.2 Multi-hub scaling

Partition by `hub_id`:

- Event stream partition key: `hub_id`.
- Redis keys prefixed by hub.
- Snapshot projections built per hub.
- PostgreSQL tables indexed by `(hub_id, date/status)`.

This keeps hot operations local and makes it easy to isolate a noisy hub.

### 13.3 Read model

The live board should not assemble itself from many tables on every refresh. Maintain a materialized `HubSnapshot` projection:

```json
{
  "hubId": "hub_123",
  "businessDate": "2026-05-21",
  "version": 9182,
  "demos": [],
  "engineers": [],
  "products": [],
  "tasks": []
}
```

The POC tablet loads this snapshot and then receives incremental updates.

---

## 14. Rollout Plan

### Phase 1: Single-hub visibility

- Import demos.
- Track product units.
- POC tablet live board.
- Manual assignment and status updates.
- Basic engineer mobile workflow.

Success criteria:

- POC no longer needs WhatsApp to answer "what is happening now?"
- Every demo has visible owner, product, and state.

### Phase 2: Controlled execution

- QR pickup/return.
- Readiness checklists.
- Exception action cards.
- Realtime notifications.
- Audit log and daily reports.

Success criteria:

- Product handoff and return are system-recorded.
- Blockers are surfaced before they become missed demos.

### Phase 3: Assisted orchestration

- Assignment recommendations.
- Delay risk detection.
- Spare product recommendations.
- Cross-hub manager dashboard.

Success criteria:

- POC handles most conflicts through suggested actions.
- Manual overrides decline as planning quality improves.

### Phase 4: Optimization

- Route-aware scheduling integrations.
- Demand forecasting.
- Automated staffing/product capacity planning.
- Customer-facing status where useful.

---

## 15. Key Design Decisions and Tradeoffs

| Decision | Chosen approach | Alternative | Why |
|---|---|---|---|
| POC interface | Tablet-first live board | Generic admin dashboard | The operator is non-technical and time-constrained |
| Source of truth | PostgreSQL + event audit | Pure event sourcing | Simpler v1 consistency for assignment and inventory constraints |
| Realtime updates | WebSocket/SSE + snapshot fallback | Polling only | Realtime matters, but snapshot fallback keeps UX reliable |
| Product handoff | QR/barcode scan | Manual status buttons only | Reduces disputes and missing product errors |
| Assignment | Recommendation with POC override | Fully automatic dispatch | Human remains in control for messy real-world exceptions |
| Offline support | Readable tablet, queued low-risk mobile actions | Full offline operation | Full offline dispatch risks inventory conflicts |
| Notifications | In-app first, fallback for critical issues | WhatsApp as primary | Moves operations out of chat while preserving rollout safety |

---

## 16. What Makes This Fit the Constraint

The system is not just a backend scheduler. It is an operations cockpit designed around the POC:

- The tablet starts on the live hub board, not a menu.
- Every card says what is wrong and what to press next.
- Colors, icons, scans, and checklists replace training.
- The assignment engine explains recommendations in plain language.
- Manual override exists because hubs are physical operations, not perfect workflows.
- WhatsApp can remain as fallback during rollout, but the source of truth moves into the hub platform.

The technical architecture supports this by making hub state realtime, auditable, strongly consistent where it matters, and simple enough for a non-technical operator to trust during peak demo volume.
