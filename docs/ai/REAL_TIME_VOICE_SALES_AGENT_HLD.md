# Real-Time Voice Agent for Inbound Sales — High-Level Design

**Audience:** Staff / Principal engineers designing a production voice agent.  
**Hard SLO:** time-to-first-audio (**TTFA**) **p95 &lt; 800 ms** after end-of-utterance; engineering path to **p95 500–600 ms**.  
**Product:** Answer inbound sales calls, greet by context, qualify, answer product questions, book meetings, escalate to humans.

---

## 0. Executive Summary

### 0.1 One-sentence architecture

> **PSTN/SIP → co-located Voice Media Plane → streaming VAD/turn detector → streaming STT → speculative Dialog Orchestrator → streaming LLM → streaming TTS → media out**, with **greeting audio prefetched on ring**, **CRM enrichment off the hot path**, and a **speech-to-speech (S2S) lane** as the path from ~700–800 ms cascaded TTFA down to 500–600 ms.

### 0.2 Latency contract (what TTFA means)

| Metric | Definition | Target |
|---|---|---|
| **TTFA** | Wall clock from **end-of-utterance (EOU)** decision → **first PCM/μ-law frame** heard by caller | **p95 &lt; 800 ms**, stretch **p95 500–600 ms** |
| **Answer delay** | SIP INVITE → first greeting frame | **p95 &lt; 400 ms** (pre-rendered greeting) |
| **Barge-in cancel** | Caller speech onset → TTS stop + media flush | **p95 &lt; 120 ms** |
| **Turn correctness** | False cut-in / late cut rate (eval set) | &lt; 3% / &lt; 5% |

TTFA is **not** full-response completion time. Callers forgive long answers; they do not forgive dead air after they stop talking.

### 0.3 Why naive cascaded stacks miss 800 ms

Classic `record → STT final → LLM full → TTS full` is 2–5 s. Even a “streaming” stack fails the SLO if any hop waits for finals:

| Stage (naive streaming) | Typical |
|---|---:|
| Endpointing wait after silence | 400–800 ms |
| STT finalize | 150–300 ms |
| LLM time-to-first-token | 200–500 ms |
| TTS time-to-first-byte | 150–300 ms |
| Media jitter buffer | 40–80 ms |
| **Sum** | **~1.0–2.0 s** |

Beating 800 ms requires **overlapping stages**, **speculative generation before EOU is firm**, **region co-location**, and treating **turn-taking as the primary latency subsystem**.

### 0.4 Design bets

| Bet | Consequence |
|---|---|
| Thin synchronous media path | No Kafka, no CRM writes, no full RAG on the TTFA path |
| Speculative dialog | Start LLM on high-confidence partial transcripts; cancel/revise on barge-in or transcript revision |
| Prefetch greeting | Ring-phase SIP → pre-rendered or cached TTS for “Thanks for calling …” |
| Tools after first audio | Calendar/CRM tools run after (or interleaved after) first audible tokens |
| Cascaded v1, S2S v2 | Ship controllable cascaded stack under 800 ms; use S2S / duplex models for 500–600 ms |

---

## 1. Problem & Scope

### 1.1 Product goals

Inbound caller dials a sales number (or clicks-to-call). The agent:

1. Answers immediately with a natural greeting (optionally personalized by ANI / campaign).
2. Qualifies (need, company size, timeline, budget band) without sounding like an IVR tree.
3. Answers product / pricing / availability questions from a bounded knowledge base.
4. Books a meeting or routes a hot lead to a human closer.
5. Captures structured CRM fields + full transcript asynchronously.

### 1.2 In scope

- Telephony edge (SIP / WebRTC), media plane, turn-taking, STT, dialog orchestration, LLM, TTS, tools, session state, observability, eval for latency + sales quality.
- Multi-tenant SaaS shape (per-tenant prompts, voices, calendars, knowledge).

### 1.3 Out of scope

- Outbound dialer / predictive dialing compliance stack (separate product).
- Full contact-center ACD replacement (queues, skills routing beyond warm handoff).
- Video / Emotion AI pipelines beyond lightweight prosody cues for turn-taking.

### 1.4 Assumed scale (design for, don’t overbuild day one)

| Dimension | Day-1 | Steady |
|---|---:|---:|
| Concurrent calls | 500 | 20,000 |
| Concurrent regions | 1 | 3 (US / EU / APAC) |
| Avg call length | 4–6 min | same |
| Tool calls / call | 1–3 | 2–5 |

Concurrency drives **warm connection pools and media plane capacity**, not the TTFA algorithm. TTFA is mostly **per-call critical-path engineering**.

---

## 2. Requirements

### 2.1 Functional

| ID | Requirement |
|---|---|
| F1 | Answer inbound PSTN/SIP and browser WebRTC calls. |
| F2 | Streaming duplex audio with barge-in (caller can interrupt agent). |
| F3 | Stateful sales dialog: greet → discover → qualify → answer → book / escalate. |
| F4 | Tools: CRM lookup/update, calendar slots, knowledge retrieval, human transfer. |
| F5 | Recording + consent handling; immutable audit of prompts, tools, and outcomes. |
| F6 | Warm handoff: transfer audio + context package to human SDR/AE. |

### 2.2 Non-functional

| ID | Requirement |
|---|---|
| NF1 | **TTFA p95 &lt; 800 ms**; path to **500–600 ms**. |
| NF2 | Answer delay p95 &lt; 400 ms. |
| NF3 | Availability 99.9% call setup; media plane degrade without dropping in-call audio when control plane blips. |
| NF4 | Per-tenant isolation for prompts, knowledge, voice, PII, and recordings. |
| NF5 | Cost observability per minute / per call / per tenant; model + STT + TTS unit economics. |
| NF6 | Replayable call traces for latency debugging (timestamps on every hop). |

---

## 3. Latency Budget

### 3.1 Cascaded target budget (≤ 800 ms)

Budget is measured from **EOU commit** (turn detector says “caller finished”) to **first outbound media frame**.

| Hop | Budget (ms) | Notes |
|---|---:|---|
| Turn detector → orchestrator signal | 10–20 | In-process or shared-memory / local gRPC |
| Speculative prompt already warm | **0–40** | LLM often already streaming from partials |
| LLM TTFT (remaining) | 120–220 | Small/fast model; warm TCP+TLS; cached system prompt KV |
| First tokens → TTS | 10–20 | Token aggregator (phrase / phoneme boundary) |
| TTS TTFB | 80–150 | Streaming neural TTS; pre-warmed session |
| Media encode + jitter | 20–40 | Prefer 20 ms packets; minimal jitter buffer on egress |
| **Total** | **~240–490 ms** typical when speculative hit | Leaves headroom for misses |

When speculation **misses** (late EOU or major transcript revision):

| Hop | Budget (ms) |
|---|---:|
| STT final / revision settle | 40–80 |
| LLM TTFT cold relative to turn | 180–280 |
| TTS TTFB | 100–180 |
| Media | 20–40 |
| **Total** | **~340–580 ms** |

p95 &lt; 800 ms is achievable on cascaded **if** EOU is not naively “700 ms of silence” and speculation is on by default.

### 3.2 Path to 500–600 ms p95

| Lever | Savings | Tradeoff |
|---|---|---|
| Aggressive speculative LLM from partials (start at ~0.6–0.8 confidence) | 80–150 ms | More cancel/revise; need cheap abort |
| Semantic EOU (prosody + ASR confidence) vs fixed silence | 150–300 ms vs naive VAD | Harder eval; language-specific tuning |
| Streaming TTS with first-phoneme &lt; 80 ms | 40–80 ms | Voice quality / vendor lock |
| Co-locate media + STT + LLM + TTS in one AZ; no public internet hops | 30–80 ms | Capacity planning per region |
| **Speech-to-speech duplex model** for chitchat / FAQ turns | 100–250 ms | Weaker tool use; need hybrid router |
| Prefetched “filler” only as last resort (&lt; 5% of turns) | masks 200 ms | Hurts naturalness if overused |

**Recommended end state:** hybrid router — **S2S for low-tool turns**, **cascaded speculative stack for tool-heavy / compliance-sensitive turns**. That is the realistic path to p95 500–600 ms without giving up CRM actions.

### 3.3 What must never sit on the TTFA path

- Full CRM write or Salesforce round-trip
- Vector RAG over large corpora (pre-retrieve or use tiny hot FAQ index)
- Kafka publish / analytics
- Cold model load, cold TTS voice load, DNS / TLS handshake
- Waiting for “perfect” final transcript before starting LLM

---

## 4. High-Level Architecture

```
                         ┌──────────────────────────────────────────────┐
                         │              CONTROL PLANE                    │
                         │  Tenant config · Prompts · Voices · Policies │
                         │  Knowledge publish · Eval · Billing           │
                         └──────────────────────▲───────────────────────┘
                                                │ warm cache (Redis)
┌────────────┐    SIP/WebRTC     ┌──────────────┴───────────────┐
│  Caller    │◄────────────────►│     Voice Edge / SBC          │
│  PSTN/App  │   RTP / SRTP     │  SIP, recording consent, ANI  │
└────────────┘                  └──────────────┬────────────────┘
                                               │ media fork
                                  ┌────────────▼────────────┐
                                  │   Voice Media Plane     │
                                  │  (sticky per call)      │
                                  │  jitter · mix · barge-in│
                                  └────────────┬────────────┘
                         audio in              │              audio out
                  ┌────────────────────────────┼────────────────────────────┐
                  ▼                            ▼                            ▲
         ┌────────────────┐          ┌─────────────────┐          ┌────────────────┐
         │ VAD + Turn     │          │ Dialog          │          │ Streaming TTS  │
         │ Detector       │─────────►│ Orchestrator    │─────────►│ (+ voice cache)│
         │ (EOU, barge)   │ partials │ speculative LLM │ tokens   └────────┬───────┘
         └────────┬───────┘          │ + tools         │                   │
                  │                  └────────┬────────┘                   │
                  ▼                           │                            │
         ┌────────────────┐                   │                     RTP to edge
         │ Streaming STT  │───────────────────┘
         └────────────────┘          tools (after first audio)
                                     ┌────────────────────────────┐
                                     │ CRM · Calendar · FAQ · ACD │
                                     └────────────────────────────┘

Async (not on TTFA path): transcript store · CRM sync · analytics · eval traces · billing
```

### 4.1 Component map

| Component | Role on hot path | Latency notes |
|---|---|---|
| **Voice Edge / SBC** | SIP signaling, RTP, recording, ANI | Prefetch greeting on INVITE |
| **Media Plane** | Per-call worker affinity; mix; barge-in cancel | Sticky pod; no hop through shared MQ |
| **Turn Detector** | VAD + semantic EOU + interruption | Largest lever on perceived latency |
| **Streaming STT** | Partials every 100–200 ms | Partial-stable events drive speculation |
| **Dialog Orchestrator** | State machine + prompt assembly + cancel | In-memory call FSM; Redis for failover |
| **LLM Gateway** | Streaming completion; abort; fallback | Warm pools; pinned low-latency model |
| **TTS** | Stream PCM/μ-law; voice session reuse | Pre-warm voice on call answer |
| **Tool Runtime** | CRM / calendar / FAQ / transfer | Start after TTFA or in parallel when safe |
| **Session Store** | Call state, transcript buffer | Redis; not on first-token critical path after warm |

---

## 5. Critical Path Flows

### 5.1 Call answer (greeting) — not the same as TTFA

```mermaid
sequenceDiagram
    participant PSTN
    participant Edge as Voice Edge
    participant Media as Media Plane
    participant Orch as Orchestrator
    participant CRM

    PSTN->>Edge: SIP INVITE (ANI, DNIS)
    Edge->>Orch: Ring event
    par Prefetch
        Orch->>Orch: Select greeting template + voice
        Orch->>Media: Preload greeting audio (cache hit preferred)
    and Enrichment (non-blocking)
        Orch->>CRM: Lookup ANI / campaign (budget 150ms, soft fail)
    end
    Edge->>PSTN: 200 OK + media
    Media->>PSTN: First greeting frames (<400ms from INVITE)
    Note over Orch,CRM: Personalization splice if CRM returns in time;<br/>else generic greeting, personalize next turn
```

**Rule:** Never delay answer for CRM. Generic greeting &gt; silence. Personalize on turn 2 if needed.

### 5.2 Steady-state turn (TTFA path)

```mermaid
sequenceDiagram
    participant C as Caller
    participant M as Media Plane
    participant T as Turn Detector
    participant S as STT
    participant O as Orchestrator
    participant L as LLM
    participant V as TTS

    C->>M: RTP audio
    M->>T: frames
    M->>S: frames
    S-->>O: partial transcripts (streaming)
    O->>L: speculative generate (when score ≥ τ)
    T-->>O: EOU commit
    Note over O,L: If already streaming, continue;<br/>else start / revise with final text
    L-->>O: first tokens
    O->>V: stream text (phrase flush)
    V-->>M: first audio frames
    M-->>C: RTP (TTFA measured here)
    C->>M: barge-in speech
    M->>V: CANCEL
    M->>L: ABORT
    M->>O: reset turn
```

### 5.3 Speculative generation state machine

```
PARTIAL(score) ──► if score ≥ τ_start: SPECULATING
SPECULATING ──transcript revises heavily──► ABORT + restart
SPECULATING ──EOU──► COMMIT (continue stream)
IDLE ──EOU without speculation──► START (cold TTFT path)
ANY ──barge-in──► ABORT (flush TTS jitter, clear half-spoken sentence)
```

**τ_start** typically 0.65–0.85 depending on language and noise. Tune on an eval set of real sales calls; do not guess in production without measurement.

### 5.4 Tool-bearing turns without blowing TTFA

Pattern: **speak first, act second** (or **act in parallel when result not needed for first sentence**).

Example: “Sure — let me check Tuesday afternoon for you.”

1. Commit opener tokens immediately (cached phrase templates or LLM with tool deferred).
2. Fire `calendar.search` in parallel.
3. Stream continuation once tool returns (or speak a short bridge if tool &gt; 400 ms).
4. Never block first audio on the tool.

For hard dependencies (“What’s my quote?”), use a **bounded retrieval cache** (tenant FAQ / price card in Redis) sized for &lt; 20 ms local hit; only miss path hits heavier RAG.

---

## 6. Turn-Taking & Barge-In (the real product)

### 6.1 Endpointing stack

| Layer | Purpose |
|---|---|
| Energy VAD | Cheap speech/silence |
| Neural VAD | Robust to noise / music on hold |
| ASR-stability | Partials stop changing → likely done |
| Prosody / pause model | Distinguishes thinking pause vs turn end |
| Dialog priors | After agent asked a yes/no, shorter EOU OK |

**Anti-pattern:** fixed 700 ms silence timeout as the only EOU signal. That alone can make 800 ms TTFA impossible.

### 6.2 Barge-in

- Media plane detects caller voice activity while agent is speaking.
- Immediate: stop TTS consumer, flush egress jitter buffer, send RTP comfort noise / clean cut.
- Orchestrator: abort LLM stream; mark agent utterance truncated in transcript.
- Policy: ignore sub-120 ms blips (coughs) via confirmation window to avoid jittery cutoffs.

### 6.3 Backchannels

Optional short acknowledgements (“mm-hmm”) from a **pre-rendered clip bank** while still listening — not counted as full agent turns, and disabled when they increase false barge-in.

---

## 7. Dialog Orchestrator (sales-specific)

### 7.1 Call FSM (explicit, not free-form forever)

```
RING → GREET → DISCOVER → QUALIFY → ANSWER_OBJECTION → SCHEDULE → CONFIRM → CLOSE
                              │                │
                              └──── ESCALATE ──┘
```

LLM generates **within a stage**, with stage-specific tools and max tokens. Explicit stages:

- Cap latency (smaller prompts).
- Improve compliance (required disclosures in GREET / SCHEDULE).
- Make analytics (“qualification rate”) measurable.

### 7.2 Prompt & context packing for speed

Keep the **hot system prompt** short and stable for KV-cache hits:

- Persona + stage instructions + safety
- Last N turns (compressed)
- Compact CRM facts (company, prior opportunity stage)
- Retrieved FAQ snippets (≤ 1–2 KB)

Move long playbooks and full CRM history to **async summarization** between turns, not into every completion.

### 7.3 Knowledge for sales answers

| Tier | Store | Latency | Use |
|---|---|---:|---|
| L0 Hot FAQ / pricing cards | Redis / in-proc | &lt; 5–20 ms | Top objections, plans, guarantees |
| L1 Hybrid retrieval | Local ANN + BM25 | 30–80 ms | Broader product docs |
| L2 Deep RAG | Central index | 100–300 ms | Rare; never blocks TTFA opener |

Retriever runs **speculatively** with STT partials when stage = ANSWER_OBJECTION.

---

## 8. Model & Vendor Topology

### 8.1 Cascaded (v1 — ship this)

| Role | Choice criteria |
|---|---|
| STT | Streaming partials, &lt; 200 ms partial lag, strong telephony 8 kHz / 16 kHz |
| LLM | Fast TTFT, good instruction following, streaming cancel, tool calling |
| TTS | Streaming first-byte &lt; 150 ms, telephony codecs, barge-in friendly |

Prefer **same-cloud / same-region** private connectivity. Public multi-hop SaaS chains are the usual reason “streaming” stacks still measure 1.2 s.

### 8.2 Speech-to-speech (v2 — path to 500–600 ms)

Use S2S when:

- Stage is GREET / DISCOVER / simple FAQ
- No pending high-stakes tool
- Language supported

Fall back to cascaded when:

- Calendar book / CRM mutate / transfer
- Compliance phrasing must be exact
- S2S confidence low / noisy line

Router sits in the orchestrator; both lanes share the same call FSM and transcript store.

### 8.3 Warmth & capacity (latency killers if ignored)

- Persistent HTTP/2 or gRPC streams to STT/LLM/TTS; no per-turn handshake.
- Per-AZ warm pools sized to peak concurrent calls × safety factor.
- Pin “sales-fast” model; do not A/B heavy models on the live TTFA path without a shadow lane.
- Pre-load TTS voice on call answer (not on first user utterance).

---

## 9. Data Model (minimal)

### 9.1 Call session (Redis)

```json
{
  "call_id": "c_...",
  "tenant_id": "t_...",
  "stage": "QUALIFY",
  "ani": "+1...",
  "dnis": "+1...",
  "crm_contact_id": "optional",
  "facts": {"company": "...", "seats": 25, "timeline": "this_quarter"},
  "turns": [{"role": "user|agent", "text": "...", "t0": 0, "t1": 0}],
  "speculative": {"llm_stream_id": "...", "status": "SPECULATING"},
  "consent_recording": true,
  "region": "us-east-1"
}
```

### 9.2 Immutable events (async log)

`call.started`, `turn.eou`, `llm.first_token`, `tts.first_byte`, `media.first_frame`, `tool.invoked`, `barge_in`, `handoff`, `call.ended` — each with monotonic timestamps for TTFA reconstruction.

---

## 10. APIs & Protocols

| Surface | Protocol | Notes |
|---|---|---|
| Carrier / CPaaS | SIP + RTP (PCMU/PCMA/OPUS) | Edge termination |
| Browser click-to-call | WebRTC | Same media plane |
| Orchestrator ↔ STT/LLM/TTS | gRPC streaming | Deadline + cancel |
| Tools | Internal gRPC / HTTP | Timeouts 300–800 ms; never on TTFA opener |
| Admin config | HTTPS | Prompts, voices, business hours |

**Media Plane affinity:** `call_id → pod` via consistent hash or signaling sticky. Migrating a live call across pods is expensive; prefer drain + reject new calls on bad pods.

---

## 11. Reliability & Failure Modes

| Failure | Detection | Mitigation |
|---|---|---|
| STT stall | Partial silence &gt; 400 ms while VAD=speech | Fail over STT vendor; speak brief apology only if &gt; 1 s dead air |
| LLM TTFT spike | First token &gt; 350 ms | Abort to backup model; optional 1× cached bridge phrase (&lt; 5% turns) |
| TTS stall | No audio 200 ms after tokens | Switch TTS; fall back to secondary voice |
| Tool timeout | Deadline exceeded | Continue dialog; offer callback / human |
| Media pod death | Heartbeat / RTP timeout | SIP re-INVITE to warm standby (rare); else graceful drop + callback |
| Hallucinated price/promise | Policy classifier on outbound text | Block + regenerate from L0 price cards; escalate if repeated |

**Degradation order:** lose personalization → lose deep RAG → lose tools → keep greeter + qualifier + human transfer. Never lose barge-in cancel.

---

## 12. Observability (latency is a product feature)

### 12.1 RED + hop traces

For every turn, emit a **hop timeline**:

```
eou_ts → orch_ts → llm_ttft_ts → tts_ttfb_ts → media_first_frame_ts
```

Dashboards:

- TTFA p50/p95/p99 by tenant, region, stage, model, language
- Speculation hit rate / abort rate
- Barge-in cancel latency
- Tool latency vs whether it blocked speech (should be ~0% blocking)

### 12.2 Continuous eval

| Suite | What |
|---|---|
| Latency replay | Recorded RTP → measured TTFA in staging with same models |
| Turn-taking | False cut-in / late response on labeled set |
| Sales quality | Qualification field accuracy, booking success, script adherence |
| Safety | No fabricated pricing; consent present; PII handling |

Gate releases on **p95 TTFA** and **turn-taking error rate**, not only on win-rate of the LLM judge.

---

## 13. Security, Privacy, Compliance

- Recording consent gated before media fork to object storage.
- PII minimization in model logs (store raw audio/transcripts in tenant-scoped encrypted buckets; send redacted text to vendors when contract requires).
- Tenant keys for prompts and knowledge; no cross-tenant KV-cache bleed.
- Tool allowlists per stage (e.g. no `crm.delete` ever from voice agent).
- Audit: who changed greeting/prompt/voice; model version per call.

---

## 14. Capacity Sketch (steady 20k concurrent)

Assumptions: 20k calls, bidirectional audio ~50 packets/s, orchestrator CPU light vs media/STT/TTS.

| Pool | Rough sizing intuition |
|---|---|
| Media plane | CPU-bound RTP + VAD; autoscale on concurrent calls + packet lag |
| STT / TTS / LLM | Vendor concurrency quotas + self-hosted replicas; warm to peak |
| Orchestrator | Stateless horizontally; Redis for session |
| Redis | Session + L0 FAQ; memory sized to active calls × session blob |

Cost control: route simple turns to smaller LLM; cache greetings and common FAQ audio; cap max agent talk-time per call.

---

## 15. Rollout Plan

| Phase | Deliverable | TTFA expectation |
|---|---|---|
| **P0** | Cascaded streaming + fixed neural EOU + greeting prefetch + hop traces | Measure baseline (often 900–1400 ms) |
| **P1** | Speculative LLM from partials + TTS pre-warm + co-location | **p95 &lt; 800 ms** |
| **P2** | Semantic EOU + L0 FAQ + tool-after-audio discipline | p95 ~650–750 ms |
| **P3** | Hybrid S2S router for low-tool stages | **p95 500–600 ms** on eligible turns; overall p95 ≤ 600–700 ms |
| **P4** | Multi-region media + tenant SLOs + human warm-transfer polish | Hold SLO under geo load |

Feature-flag speculation and S2S per tenant. Shadow-compare TTFA before enabling.

---

## 16. Key Design Tradeoffs

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Pipeline | Cascaded speculative → hybrid S2S | S2S-only from day 1 | Tooling, compliance phrasing, vendor maturity |
| EOU | Multi-signal semantic | Fixed silence timer | Silence timer alone breaks 800 ms |
| CRM on answer | Async soft enrich | Block answer on lookup | Answer delay SLO |
| Tools | After first audio | Wait for tool then speak | Protects TTFA |
| State | Explicit sales FSM + LLM | Fully free-form agent | Latency, measurability, compliance |
| Filler audio | Rare fallback | Always mask with “um” | Sounds fake; hides real regressions |
| Transport | Sticky media plane, no MQ on hot path | Event-driven media | Milliseconds matter |

---

## 17. Interview / Whiteboard Cheat Sheet

**Open with:** “Voice sales TTFA is a turn-taking + pipeline-overlap problem, not an LLM problem.”

**Draw:** Edge → Media → (VAD/EOU ∥ STT) → speculative Orchestrator → LLM → TTS → RTP.

**Say the budget:** ~120–220 LLM + ~80–150 TTS + ~40 media, with speculation making EOU→LLM ≈ 0 on the happy path.

**Name the killers:** silence-only endpointing, waiting for STT final, cold TLS, CRM on the greeting, tools before first audio, cross-region vendors.

**Close with path to 500–600:** semantic EOU + speculation + co-location + hybrid S2S for non-tool turns.

---

## 18. Summary

A real-time inbound sales voice agent that clears **p95 TTFA &lt; 800 ms** is a **co-located, fully streaming, speculative cascaded stack** with **greeting prefetch**, **semantic turn-taking**, and **tools off the first-audio path**. The path to **500–600 ms** is not “a faster LLM alone” — it is **better EOU**, **higher speculation hit rate**, **sub-100 ms TTS TTFB**, and a **hybrid speech-to-speech lane** for low-tool dialog stages, all measured with per-hop traces on every turn.
