# ServiceLane — Car Service AI Agent Design

**Audience:** Engineers building a customer-facing service agent for automotive aftersales.  
**Product:** Conversational agent that owns the car-servicing journey — identify, recommend, book, track, cancel, escalate.  
**Companion code:** [`car-service-agent/`](../../car-service-agent/)

---

## 1. Problem

Car owners still book servicing through phone trees, WhatsApp chaos, or dealer portals that don't know their mileage, history, or symptoms. The goal is a **Service AI Agent** that:

1. Knows the customer and their vehicles.
2. Recommends the right package from usage + symptoms.
3. Finds a real workshop slot and books it.
4. Tracks job status and can cancel / escalate.

Non-goals for v1: parts inventory optimization, technician scheduling internals, insurance claims, autonomous payment capture.

---

## 2. Architecture

```
┌──────────────┐     ┌─────────────────────┐     ┌──────────────────┐
│ Chat UI / CLI│────►│ Service Agent       │────►│ Tool Registry    │
│ / API        │◄────│  • intent parse     │◄────│ 14 domain tools  │
└──────────────┘     │  • session memory   │     └────────┬─────────┘
                     │  • booking FSM      │              │
                     │  • reply composer   │              ▼
                     └─────────────────────┘     ┌──────────────────┐
                                                 │ Domain Store     │
                                                 │ customers, cars, │
                                                 │ packages, slots, │
                                                 │ appointments     │
                                                 └──────────────────┘
```

### Design bets

| Bet | Consequence |
|---|---|
| Tools are authoritative | LLM (optional) never invents bookings; store mutations only via tools |
| Deterministic planner first | Demo + tests work without an API key; LLM can later rewrite tone or plan |
| Session slot-filling FSM | Booking is a multi-turn workflow, not a single prompt |
| Read/write tools with clear risk | Status/history are free; book/cancel require collected slots + confirmation |

---

## 3. Agent loop

ReAct-shaped, but with a typed planner:

```
User utterance
    → parse intent + entities (name, vehicle, city, package, symptoms, appt id)
    → absorb into SessionState
    → dispatch intent handler
         → call tools (identify, recommend, find_slots, book, …)
         → update session / pending_confirmation
    → compose reply + suggestion chips
```

### Booking state machine

```
IDENTIFIED → VEHICLE_SELECTED → PACKAGE_SELECTED → SLOT_SELECTED → CONFIRM → BOOKED
     ↑              ↑                 ↑                  ↑
  customer       model/reg         recommend/         find_slots
  lookup                           package pick
```

Confirmation gate (`pending_confirmation`) prevents accidental writes.

---

## 4. Tool catalog

| Tool | Mutates? | Purpose |
|---|---|---|
| `identify_customer` | no | Resolve name/phone/email → customer + vehicles |
| `list_vehicles` / `select_vehicle` | no | Vehicle resolution |
| `recommend_services` | no | Mileage + recency + symptom heuristics |
| `list_packages` / `estimate_cost` | no | Catalog & pricing |
| `find_centers` / `find_slots` | no | Availability |
| `book_appointment` | **yes** | Reserve slot + create appointment |
| `list_appointments` / `get_appointment_status` | no | Tracking |
| `cancel_appointment` | **yes** | Release slot |
| `get_service_history` | no | Past jobs |
| `escalate_to_human` | yes (ticket) | Advisor handoff |

In production, mutating tools should sit behind IAM, idempotency keys, and audit logs — same stance as the [Agentic Support Copilot](./AGENTIC-SUPPORT-COPILOT.md) write path.

---

## 5. Recommendation policy (v1)

Deterministic signals (easy to explain and test):

- km / months since last service → Basic vs Comprehensive
- Symptom keywords → Brake / AC / Diagnostic / Tires
- Urgency band: low / medium / high / critical

Production upgrade path: garage history embeddings + OEM schedule graph + bay utilization, with the heuristic layer kept as a fallback.

---

## 6. UX contract

- Brand-first chat surface (**ServiceLane**), not a dashboard.
- One job: converse and complete servicing actions.
- Suggestion chips mirror next legal moves in the FSM.
- Optional tool-trace panel for debugging (dev / support).

---

## 7. Production hardening path

| Layer | v1 (this repo) | Next |
|---|---|---|
| Planner | Rule + entity parse | LLM tool-calling planner with schema validation |
| Memory | In-process session | Redis session + long-term vehicle profile |
| Systems of record | In-memory store | DMS / dealer CRM / bay calendar APIs |
| Safety | Confirm gate on book/cancel | Policy engine, spend limits, PII masking |
| Channels | Web + CLI | WhatsApp, voice (see voice sales HLD patterns) |
| Eval | Pytest flow suites | Scenario eval set: book, reschedule, angry cancel, symptom triage |
| Observability | Tool trace in response | OpenTelemetry spans per tool + booking conversion metrics |

---

## 8. Example happy path

```
User: I am Ananya Sharma
Agent: Found you… City + Creta listed
User: My City needs service, AC is weak
Agent: Recommends AC Service (+ periodic if due)
User: Book it
Agent: Lists Koramangala / Indiranagar slots
User: slot 1
Agent: Confirmation card
User: yes
Agent: Booked appt_… with estimate and center details
```

---

## 9. Why this shape

A car-service agent fails when it chatters without writing to the workshop calendar, or when it writes without collecting the right slots. Separating **intent → tools → confirmation → mutation** keeps the agent useful on day one and safe to connect to a real DMS later.
