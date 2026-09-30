# ServiceLane — Car Service AI Agent

A conversational **Service AI Agent** that takes care of car servicing for a customer:
identify the account, recommend the right package from mileage/symptoms, find workshop
slots, book/cancel appointments, track job status, and escalate to a human advisor.

```
Customer chat  →  Intent + slot fill  →  Tool calls  →  Domain store  →  Reply
                      ▲                      │
                      └──── session memory ──┘
```

## What it can do

| Capability | Example |
|---|---|
| Identify customer | `I am Ananya Sharma` / phone / email |
| Recommend service | `My brakes are squeaking` |
| Browse packages & quotes | `What services do you offer?` / `How much is AC service?` |
| Find slots | `Show available slots in Bengaluru` |
| Book end-to-end | Collect vehicle → package → slot → confirm |
| Track status | `Check status for appt_demo_rahul` |
| Cancel | `Cancel appt_…` |
| Service history | `Show service history` |
| Human handoff | `Talk to an advisor` |

Runs **fully offline** with a deterministic planner (no API key required). Tools remain
the source of truth for bookings and status.

## Quick start

```bash
cd car-service-agent
pip install -r requirements.txt

# Web UI + API
PYTHONPATH=. uvicorn api.app:app --reload --host 0.0.0.0 --port 8080
# open http://127.0.0.1:8080

# CLI
PYTHONPATH=. python scripts/cli.py

# Scripted demo
PYTHONPATH=. python scripts/cli.py --trace --script \
  "I am Ananya Sharma" \
  "Service my City" \
  "Book a service" \
  "slot 1" \
  "yes"
```

## Demo customers

| Name | City | Vehicles |
|---|---|---|
| Ananya Sharma | Bengaluru | Honda City, Hyundai Creta |
| Rahul Mehta | Mumbai | Tata Nexon EV (has in-progress `appt_demo_rahul`) |
| Priya Nair | Bengaluru | Maruti Swift |

## Project layout

```
car-service-agent/
├── agent/           # intents, session memory, orchestrator
├── domain/          # models + seeded in-memory store
├── tools/           # tool registry the agent can call
├── api/             # FastAPI + chat UI
├── scripts/cli.py   # terminal demo
└── tests/           # pytest coverage for core flows
```

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Chat UI |
| `GET` | `/health` | Liveness |
| `POST` | `/api/chat` | `{ "message", "session_id?" }` → agent reply |
| `POST` | `/api/session/reset` | Clear session |
| `GET` | `/api/tools` | Tool schemas |
| `GET` | `/api/demo/customers` | Seeded demo accounts |

## Tests

```bash
cd car-service-agent
PYTHONPATH=. pytest -q
```

## Design notes

See [`docs/ai/CAR_SERVICE_AI_AGENT.md`](../docs/ai/CAR_SERVICE_AI_AGENT.md) for architecture,
tool contracts, booking state machine, and production hardening path (LLM planner,
CRM/DMS connectors, HITL).
