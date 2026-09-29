# Leadership Discussion Prep — Staff Signal Follow-up

**Candidate:** Ramit Hansda  
**This conversation:** Hiring manager round with **Ritesh Sinha**, Tue Sep 30, 2026, 6:30–7:30pm IST, video. Day-of script, clock, and questions for him: [`docs/interviews/TURING-RITESH-SINHA-HM-PREP.md`](../interviews/TURING-RITESH-SINHA-HM-PREP.md). Use this file for the design spine and the six stories. Use that file to run the hour.

**Where you stand:** The prior round already credited technical reasoning, systems thinking, concurrency, testing discipline, and architectural judgment. Treat that as banked. This conversation is where you show you can **name the invariant, lead the decision, and carry it across people who do not report to you.**

**How to use this doc**

1. Practice the [answer shapes](#1-two-answer-shapes) until they are automatic.
2. Deliver the [payout design](#3-worked-design-cross-border-payout) out loud in four minutes, then again in ninety seconds.
3. Deliver the six [leadership stories](#5-leadership-stories) in the Context → Responsibility → Decision → Result → Learning shape. One metric, one lesson, then stop.
4. Keep the [day-of card](#8-day-of-card) in front of you.

Deeper references already in this repo (use them if they pull on a thread, do not recite them):

- Settlement recon and invariants: [`docs/skydo-tech/SKYDO_RECONCILIATION_PRINCIPAL_DESIGN.md`](../skydo-tech/SKYDO_RECONCILIATION_PRINCIPAL_DESIGN.md)
- Design clock: [`docs/SYSTEM_DESIGN_INTERVIEW_STEPS.md`](../SYSTEM_DESIGN_INTERVIEW_STEPS.md)
- Story bank (metrics): [`docs/em-interview/DEZERV-EM-15-STORIES-FROM-RESUME.md`](./DEZERV-EM-15-STORIES-FROM-RESUME.md)

**Metrics you may use (already on the resume / prior prep — do not invent new ones):** team of 12 · mentored 8 · 10K+ international txns/day · incidents about −30% · unreconciled TPV ~0.6% → &lt;0.02% · onboarding hours → minutes · ISO 27001 + SOC 2 · Goldman team of 9, VP within a year · Moneyview manual ops −60% · design-review coverage on major changes ~30% → ~100%.

---

## 0. What they will probe next

| Feedback | What a strong answer does in the first minute |
|---|---|
| Go deeper on APIs, events, data contracts, failure, retries, idempotency, observability, scaling, durability | Names the contract and the failure before naming the queue |
| Define invariants before the solution — rollback, state transitions, persistence, at-most-once | Writes the illegal transitions and the durability boundary before any box |
| Cover failures and trade-offs without being asked | Volunteers three failures and the option you rejected |
| Be more concise: assumptions, design, risks, trade-offs, decisions | Uses that five-beat spine and stops |
| AI judgment: critique the output, validate failure paths, own correctness | Shows a review gate on the money path, with you as the owner of the merge |
| Leadership: influence without authority, conflict, mentoring, architecture, ambiguity, learning from failure | One story per theme, in Context → Responsibility → Decision → Result → Learning |
| Culture: collaboration, humility, accountability, feedback, stakeholders | Evidence from a story, not a list of adjectives |

Opening line if they ask how you want to use the time:

> I’ll keep technical answers in five beats — assumptions, design, risks, trade-offs, decision — and I’ll start from the invariant, not the diagram. For leadership examples I’ll use context, responsibility, decision, result, and what I changed afterward.

---

## 1. Two answer shapes

### Design and architecture

Say these headers out loud. They are the outline, not decoration.

| Beat | One sentence you are trying to land | Time |
|---|---|---|
| **Assumptions** | Scale, consistency bar, and what is outside your transaction | 20s |
| **Design** | Invariants, then API, event, state, and durability boundary | 90s |
| **Risks** | Three failures you are volunteering, each with the state you enter | 45s |
| **Trade-offs** | What you gave up, and the option you considered | 30s |
| **Decision** | The rule a teammate can apply without you in the room | 20s |

If they interrupt, finish the current beat in one sentence, then follow them. Do not restart from the diagram.

### Behavioral

| Beat | What belongs here | What to leave out |
|---|---|---|
| **Context** | Stakes, who was in the room, why it was stuck | A company history |
| **Responsibility** | The outcome you owned, including people who did not report to you | A task list someone assigned |
| **Decision** | The call you made and the alternative you set aside | Every option you brainstormed |
| **Result** | One number and who felt it | A tour of the implementation |
| **Learning** | The practice you installed so the same miss cannot recur | “I learned to communicate better” with no mechanism |

Ninety seconds is the target. If they want depth, they will ask. The learning beat is the Staff signal: you changed the system, not only your effort.

---

## 2. Invariants before solutions

Before you propose components, write the rules the design is not allowed to break. If a later box violates a rule, the box is wrong.

Use this set whenever the prompt moves money, mutates state, or calls something you do not control.

| Invariant class | Question you answer first | Staff-level statement |
|---|---|---|
| **State transitions** | Which edges are legal, and which are terminal? | `SETTLED → INITIATED` is illegal. Recovery is a new `REVERSAL` linked to the original id. |
| **Persistence** | What must be durable before any side effect? | The intent row, idempotency key, and outbox event commit in one database transaction. The partner call happens after that commit. |
| **Rollback** | What can a database transaction undo, and what cannot? | A failed local transaction rolls back. Once the partner may have moved money, recovery is compensation, with its own idempotency key. |
| **Execution semantics** | Where is at-most-once required, and where is at-least-once acceptable? | Transport is at-least-once. The business rule is **at most one successful money movement per intent**. If the partner cannot dedupe, we also cap ourselves at **at most one submit** until an external source of truth answers. |
| **Idempotency** | What is the key, what body does it bind, and who stores it? | One key for the life of the intent. Same key + same body returns the original result. Same key + different body is a conflict. Retries reuse the key. |
| **Ownership of the unknown** | What state do we enter when the outcome is not knowable? | `UNKNOWN` / suspense with an owner and an age. We do not guess success, and we do not fire a second submit with a new key. |

Say this before the diagram:

> I’ll lock five invariants, then design to them. One: legal transitions only, and terminals don’t reopen. Two: intent, key, and outbox commit before any partner call. Three: local rollback stays inside the database transaction; external money is compensated, not undone. Four: at most one successful money movement per intent; if the partner can’t dedupe, at most one submit until we learn the outcome. Five: an unknown outcome is an owned state with a deadline, not a retry storm.

That paragraph is the difference they asked for. The boxes come after it.

---

## 3. Worked design: cross-border payout

Use this when they go back to system design, or when a leadership story needs a technical spine. It is the Skydo problem: customer intent, our ledger, a banking or FX partner outside our transaction, 10K+ international payments a day.

### 3.1 Ninety-second version

> **Assumptions.** A payout is correct or it is visibly unresolved. Ten thousand international payments a day. The partner sits outside our database transaction, and a timeout does not mean failure. Latency of a few seconds is acceptable; a double payout is not.
>
> **Design.** Invariant: at most one successful money movement per intent. `POST /payouts` with an `Idempotency-Key` writes the intent, the request hash, and an outbox row in one Postgres transaction, state `INITIATED`. A worker submits to the partner with that same key and moves to `SUBMITTED` or `UNKNOWN`. Webhooks and polls apply with compare-and-set. Terminal states are `SETTLED`, `FAILED`, and `REVERSED`. Events on the outbox: intent recorded, submitted, unknown, settled, failed, reversed. Partition and lock on `payment_id`.
>
> **Risks.** Client retry returns the stored result. Worker crash after commit and before the partner call is a safe retry with the same key. Partner timeout enters `UNKNOWN` and we poll; we do not submit again with a new key. Duplicate or out-of-order webhooks no-op if the transition is illegal.
>
> **Trade-offs.** I give up synchronous “you are paid” on the API thread, and I give up any claim of network exactly-once. I considered auto-refund on timeout and rejected it, because the partner may have succeeded.
>
> **Decision.** Unknown means stop and reconcile. The rule for the team: never a second submit, never a new key, until an external source of truth answers.

### 3.2 Four-minute version

Walk the beats in order. Do not add a second use case unless they ask.

#### Assumptions

- Product: create a cross-border payout and learn its terminal state.
- Volume: 10K+ international payments/day, spiky at cut-off, not millions of QPS. The hard problem is partial failure, not raw throughput.
- The partner (bank, FX, SWIFT/HDFC-style settlement) does not share our transaction. Their timeout, duplicate webhook, and malformed id are in scope.
- Caller may retry. Queues may redeliver. Workers may die mid-call.
- Consistency: the customer and Finance must see one explainable state. A few seconds of “in progress” is acceptable. Two payouts for one intent is not.
- Out of scope for this pass: KYC decisioning, FX pricing, notification copy. Say that so you can go deep on the contract.

#### Design

**Invariants** — the five from [section 2](#2-invariants-before-solutions). State them before the API.

**State machine** — write this on the board before components:

```
INITIATED → SUBMITTED → SETTLED
    │            │
    │            ├→ UNKNOWN → SETTLED | FAILED | (still UNKNOWN, owned)
    │            └→ FAILED
    └→ FAILED

SETTLED → REVERSAL_PENDING → REVERSED
```

Illegal, and worth saying out loud: `SETTLED → INITIATED`, `FAILED → SUBMITTED` on the same id, `UNKNOWN →` a second submit with a fresh key.

| State | Enter when | Exit only when |
|---|---|---|
| `INITIATED` | Intent + key + outbox committed; no partner call yet | Worker claims the outbox |
| `SUBMITTED` | Partner accepted the request, or we sent it and received a synchronous ack | Webhook, poll, or timeout |
| `UNKNOWN` | Timeout, 5xx, or crash where the partner may have moved money | Partner query, statement, or file — same key |
| `SETTLED` | External source confirms credit | A reversal intent, not a rewind |
| `FAILED` | Partner reject **and** no evidence money moved | Terminal on this id |
| `REVERSED` | Compensating movement confirmed | Terminal |

**API**

| Call | Contract |
|---|---|
| `POST /v1/payouts` | Header `Idempotency-Key` (client-supplied, required). Body is the payout instruction. `201` + body on first accept. `200` + **the original body** on replay of the same key and same request hash. `409` if the same key arrives with a different hash. `422` on validation. The handler does not call the partner. |
| `GET /v1/payouts/{payment_id}` | Returns state, external refs, and `updated_at`. Safe to poll. |
| `POST /v1/payouts/{payment_id}/reversals` | A **new** intent with its own key and `original_payment_id`. Allowed from `SETTLED` only. |

**Data contract** (the row is the source of “have we already tried”):

| Column | Rule |
|---|---|
| `payment_id` | Internal id, partition and lock key |
| `idempotency_key` | Unique. Immutable for the life of the intent |
| `request_hash` | Hash of the canonical body. Binds the key to one instruction |
| `state` | One of the states above |
| `version` | Incremented on every transition. Compare-and-set |
| `external_ref` | Partner id once known. Nullable in `INITIATED` |
| `attempt_count` | How many submits we have made. Stays `0` or `1` when the partner cannot dedupe |
| `unknown_deadline` | When `UNKNOWN` must be owned by a human or a statement ingest |

**Persistence boundary.** One transaction:

1. Insert intent (`INITIATED`) with key and hash.
2. Insert outbox event `payout.intent_recorded`.
3. Commit.

Only a committed outbox row may be published. The worker calls the partner **after** that commit. That is the durability line. A crash before commit leaves nothing to retry. A crash after commit is a replay of the same intent.

**Events** (facts, not commands):

| Event | Produced when | Consumer obligation |
|---|---|---|
| `payout.intent_recorded` | Outbox commit | Worker may submit |
| `payout.submitted` | Partner ack recorded | Ledger pending entry; recon watches |
| `payout.unknown` | Timeout / ambiguous I/O | Alert + poller; no second submit |
| `payout.settled` / `payout.failed` / `payout.reversed` | Terminal transition committed | Ledger journal, notification, recon ladder |

Consumers are idempotent on `(payment_id, event_id)`. Ordering is per `payment_id`. A late `submitted` after `settled` is ignored because the transition is illegal.

**Execution semantics, said precisely.**

- The queue delivers **at least once**. Handlers must tolerate duplicates.
- The money invariant is **at most one successful movement per intent**.
- When the partner honors the idempotency key, retries are safe: same key, same body.
- When the partner does **not** honor a key, the local rule tightens to **at-most-once submit**: `attempt_count` goes `0 → 1` in the same transaction that records “we are about to call,” and a timeout moves to `UNKNOWN` rather than `attempt_count = 2`. Learning the outcome is a **read** (status API, statement, MIS file), not another write.
- Exactly-once across our database and the partner is not a claim we make.

**Observability (business invariants, not CPU).**

| Signal | Page when |
|---|---|
| Count and age of `UNKNOWN` | Oldest item past deadline |
| Unreconciled amount / daily TPV | Drift above the band (historically we drove this from ~0.6% toward &lt;0.02%) |
| `409` idempotency conflicts | Sudden rise means clients are reusing keys for new instructions |
| Illegal transition attempts | Any sustained rate — a consumer is guessing |
| Outbox lag | Publish falling behind commit |

`payment_id` is the trace id. Dashboards show state, age, and partner, so Finance can query without Slack.

**Scaling.** At 10K/day the database is not the bottleneck. Partition work by `payment_id` so one payment is ordered. Scale workers on oldest-message age and `UNKNOWN` backlog. Keep the partner call off the request thread so a slow bank cannot pin API capacity. Recon is a separate subsystem: it observes the ladder (intent, debit, FX, remittance) and never shares a transaction with settlement. Details live in the recon design doc; in this conversation, name that split and move on.

**Durability.** Postgres is the source of “we accepted this intent.” The ledger is append-only for “money we believe moved.” Partner files and acks are external sources of truth. Reconciliation joins them. It does not invent a third balance that can be edited in place.

#### Risks you volunteer (do not wait)

Say three, then offer the rest.

| Failure | What is true | What we do |
|---|---|---|
| Client retries `POST` | Intent may or may not have committed | Same key + same hash returns the stored payout. No second row |
| Worker dies after commit, before the partner call | No evidence money moved | Redeliver outbox, submit with the **same** key |
| Partner timeout | Money may have moved | `UNKNOWN`. Poll or wait for statement. Do not submit with a new key |
| We crash after partner success, before we record it | Partner has the movement; we might still say `SUBMITTED` | Next poll/webhook applies `SETTLED` idempotently |
| Duplicate or reordered webhook | At-least-once partner callbacks | Apply if the transition is legal; no-op otherwise |
| Same key, different body | Client bug or key reuse | `409`. Do not overwrite the original instruction |
| Lock holder dies | Another worker might proceed | Fencing token / version check. Stale holder’s write fails the compare-and-set |
| Primary failover mid-commit | Transaction outcome uncertain to the client | Client retries with the same key. The unique key makes the outcome single |

Add if they lean in: poison message goes to a dead letter after bounded attempts **only if** it is a poison payload, not an `UNKNOWN` payout. An `UNKNOWN` payout is never dead-lettered; it is owned. A partial batch settles per payment id, not as one all-or-nothing partner transaction we do not actually have.

#### Trade-offs

| Choice | What it costs | Alternative I set aside |
|---|---|---|
| Record intent, then call the partner | The API returns “accepted,” not “paid” | Synchronous partner call on the request thread. A slow bank becomes an API outage, and a timeout is harder to reason about |
| At-least-once delivery + idempotent apply | Every handler pays for duplicates | Claiming exactly-once in the queue. The partner is still outside that claim |
| At-most-once **submit** when the partner cannot dedupe | Some intents sit in `UNKNOWN` until a human or a file arrives | Automatic retry. That is how double payouts happen |
| Compensation for reversal | A second business object, extra states | Distributed rollback / saga undo of a bank credit. The bank will not participate in our transaction |
| Outbox in the same transaction | A worker and a lag metric to run | Dual-write to the database and the broker. Crash between them drops or double-publishes the fact |
| Suspense queue with an owner | Ops cost on the long tail | Auto-fail or auto-refund on timeout. Both lie about money |

#### Decision

> The team rule: **one key, one submit unless the partner proves they dedupe, unknown is a state, reversal is a new intent.** I would rather a payout sit in an owned `UNKNOWN` past cut-off than move money twice. Reconciliation closes the loop; it does not sit on the customer path.

If they ask what you would deepen with more time: partner contract tests that replay production-shaped webhook storms and malformed ids, and the three-layer recon ladder. Both are things you have already operated, so say so briefly and stop.

---

## 4. Failure talk-track (use this unprompted)

When the design is on the board, take the turn yourself:

> Three failures I want on the table. First, the client retries the create call — the idempotency key and request hash make that a read of the original result. Second, we time out against the partner — that is `UNKNOWN`, not a retry with a new key, because we cannot tell whether money moved. Third, a webhook arrives twice or late — we apply it only if the state transition is legal, so duplicates and reordering are no-ops. The trade-off is that some payouts wait on a statement instead of completing in line. I accept that because the invariant is one movement per intent.

Then pause. Let them choose the branch.

Phrases that keep you concise:

- “The durability boundary is the commit of intent plus outbox.”
- “Rollback applies to the local transaction. After the partner call, the tool is compensation.”
- “At-least-once on the wire. At most one successful movement in the business.”
- “I’ll stop automatic retries when the outcome is ambiguous.”
- “The page is ‘unknown older than deadline’ and ‘unreconciled amount,’ not CPU.”

---

## 5. Leadership stories

Each story is written to be spoken. Practice until you can drop the headers and still hit all five beats. Keep the learning beat concrete.

### 5.1 Influence without authority — Goldman risk modernization

**Context.** Market-risk aggregation at Goldman served pricing, VaR, and stress workflows. Quants owned model correctness. Engineering teams in other regions owned operations. I led a team of nine; I did not lead the quants or the regional owners. A “tech win” on our multi-terabyte in-memory cluster could still break a risk run.

**Responsibility.** I owned the platform modernization — sharding, replication, fault tolerance, and the ingestion path for petabyte-scale market data — and I owned getting it adopted by people whose success metric was model fidelity, not our migration plan.

**Decision.** I stopped treating modernization as an internal refactor. We held joint design reviews with quants, wrote the input/output contract and the latency expectation down, and used ADRs for irreversible compute changes. Rollout was phased, with cutovers communicated early enough that another region could refuse a date. Where a change was hard to undo, we did not ship it on enthusiasm from our team alone.

**Result.** The modernization landed without breaking global risk workflows. Quant partners trusted the platform enough to stay on it. That cross-team outcome was part of why I was promoted to Vice President within a year.

**Learning.** In a specialist domain I lead by translating the contract, not by outranking the expert. The practice I kept: no irreversible compute change without a written contract the downstream owner has seen.

### 5.2 Conflict — compliance speed versus a new onboarding path

**Context.** Product wanted a new business-entity onboarding path shipped quickly. Engineering could see KYC and audit-trail gaps. Shipping raw put trust and the next audit at risk. Blocking the path entirely slowed growth. Both sides were arguing from a real constraint.

**Responsibility.** I owned the call on what engineering would stand behind, and I owned making that call with Product and Compliance in the room rather than letting it turn into a Slack deadlock.

**Decision.** I wrote one decision memo: regulatory and trust risk on one side, time-to-revenue on the other. The proposal was a feature-flagged beta for a whitelisted cohort, with full rollout only after the audit trail was reviewed. We aligned Product and Compliance in a single meeting. I did not treat it as a vote between functions.

**Result.** Beta in about two weeks, full rollout in about five, and zero compliance findings on that path at the next audit. We also kept the broader onboarding automation that took entity setup from hours to minutes across five entity types.

**Learning.** The job is to make the trade-off explicit — what ships, what waits, what is killed — and to write it down so the argument has an artifact. I still use a one-page decision memo whenever two functions are optimizing different risks.

### 5.3 Mentoring — raising the design bar on a team of 12

**Context.** At Skydo I led twelve engineers on payments and settlement. Architectural judgment was uneven, and money-path changes were landing with inconsistent rigor. If I reviewed everything myself, delivery would bottleneck on me.

**Responsibility.** I owned the growth of eight engineers directly, and I owned the standard for anything that could move or mis-state money.

**Decision.** Design-doc first for payment changes. ADRs for choices that were expensive to reverse. Senior and mid-level engineers paired on reviews so the standard was practiced, not announced. Growth plans in 1:1s named a skill — state machines, idempotency, operability — rather than “be more senior.”

**Result.** Design-review coverage on major changes moved from roughly 30% to effectively all of them. Promotions came out of that pipeline. I stayed out of the critical path for reviews that the rubric already covered.

**Learning.** Forums beat hero mentoring. The practice I would repeat: a written bar, a pairing mechanism, and a growth plan tied to a skill the team can see in the work.

### 5.4 Architectural decision — correctness under partial failure

**Context.** International payouts at Skydo crossed our ledger, a banking partner, and FX. None of them shared a transaction. Early on, the risk was designing for the happy path: call the partner, store the result, retry if it looks failed.

**Responsibility.** I owned the payments and settlement architecture end to end, including the failure semantics other teams would copy.

**Decision.** I set the invariants first. Durable intent and idempotency key before any side effect. Distributed locks with fencing on settle and payout. Partner I/O on a visible async path, not a fire-and-forget thread. A canonical state vocabulary — initiated, debited, converted, remitted, settled, failed, reversed — shared with Finance and Ops. Reconciliation as a three-layer ladder beside the hot path, not a nightly script that hoped the hot path was right. For long-running workflows we later evaluated an orchestrator against Postgres, queues, and a state machine; we piloted on one payout-retry workflow before expanding, and we accepted the operational cost of another stateful system only because the volume justified it.

**Result.** The platform ran 10K+ international transactions a day. Unreconciled amount moved from about 0.6% of daily TPV to under 0.02%. The same ladder cut the next banking-partner onboarding from an estimated 10+ weeks to about 4. Production incidents fell by about 30% once observability was tied to those business signals.

**Learning.** In money movement I decide the invariant and the recovery tool before I pick infrastructure. The rule I left the team: local transactions roll back; external money is compensated; unknown is a state.

### 5.5 Ambiguity — “rupees are missing”

**Context.** Finance reported that money was missing for a customer. Engineering, Ops, Finance, and the banking partner each meant something different by “settled.” The thread ran for days because the word was shared and the definition was not. We were already at 10K+ cross-border transactions a day.

**Responsibility.** I owned turning that into a problem we could decide, and I owned a vocabulary the other functions would actually use. No one had assigned a reconciliation project; the tickets were the assignment.

**Decision.** I wrote a one-page problem statement: what we observe, what we do not know, what done looks like. I split the fog into three questions — event model, timing model, identity model — and published seven states with entry and exit conditions. We replaced one opaque reconciliation job with a ladder: intent versus debit, debit versus FX, FX versus remittance. Every transition carried `correlation_id`, `external_ref`, and `source_system`.

**Result.** Unreconciled amount fell from about 0.6% of daily TPV to under 0.02%. Finance moved from ad-hoc tickets to the dashboard. The next partner integration reused the ladder.

**Learning.** Resolving ambiguity means installing a shared language and a query, so the next unknown does not start in Slack. I now refuse to design the matcher until the states and the join keys are written down.

### 5.6 Learning from failure — partner webhooks

**Context.** On an early partner integration I scoped failure handling from the happy-path docs. Tests covered the success webhook. They did not seriously cover retry storms or edge id formats. I shipped that.

**Responsibility.** I owned the integration and the miss. Containment and the permanent fix were mine, not the on-call’s.

**Decision.** Reconciliation caught the mismatch before it became lasting customer harm. I froze the risky path, fixed dedupe for the edge id formats, and then changed the system around the miss: partner contract tests that replay webhooks and malformed ids, an idempotency checklist on money-path pull requests, and a staging suite that replays production-shaped webhook storms. I stated the miss as an under-scoped failure model, not as a flaky partner.

**Result.** No lasting customer money loss. The operational firefighting on that path stopped. Later partners inherited the checklist, so the same class of bug did not depend on me remembering.

**Learning.** The partner’s failure mode is a design input. Optimistic third-party assumptions are how this class of incident gets created. I now require the timeout, the duplicate, and the malformed id to be tests before I call an integration done.

### Story map

| They ask about | Lead with | If they want another |
|---|---|---|
| Influence without authority | 5.1 Goldman quants and regional owners | 5.2 the decision memo across Product and Compliance |
| Conflict | 5.2 onboarding versus compliance | 5.6 naming your own miss in public |
| Mentoring | 5.3 design bar, eight engineers | The senior-IC behavior story in the Dezerv bank (receipts, 30/60/90, shadow the next reviews) |
| Architectural judgment | 5.4 invariants, ladder, incidents −30% | 5.5 vocabulary before the matcher |
| Ambiguity | 5.5 “settled” | 5.2 writing the decision memo |
| Failure | 5.6 webhooks | 5.4 what you changed in the platform afterward |
| Stakeholders | 5.2 or 5.5 | 5.1 |
| A time you were wrong | 5.6, and stop defending the original design | — |

Hard conversation backup (people conflict), if they want behavior rather than a roadmap fight. A strong IC was dismissive in design and PR reviews and it was costing psychological safety. You owned keeping the talent and protecting the team. You used a private conversation with specific threads, 30/60/90 behavior goals, and you shadowed the next two reviews with feedback the same day. Behavior shifted in about six weeks, the engineer owned it, and the team stayed. Learning: feedback without receipts feels like a character attack.

---

## 6. AI-assisted engineering judgment

They want to see that you can use the tools and still be the person who is right when the tool is wrong. Lead with ownership, then the gate, then the story.

### Ninety-second answer

> I treat model output as a draft from a fast teammate who has not operated our failure modes. On a money path I read it against the invariant before I read it for style. The checks I insist on: is the intent durable before the side effect, does a retry reuse the idempotency key, does a timeout enter an unknown state instead of a guessed one, and is there a test for the crash between commit and the partner call plus a test for a duplicate webhook. If those are missing, I rewrite that path. Boilerplate and tests around an already-specified contract are where I let the tool move fast. The merge is mine, so the correctness is mine. At Skydo I turned that into a team standard for Claude, Cursor, and Windsurf: what may enter a prompt, a ban on PII and secrets, and a review norm when the model wrote the first draft. We looked at whether velocity and defect rates both moved, not at license counts. The gate that never moved is human ownership of financial correctness.

### How you critique a model diff (say this if they role-play a review)

Walk the diff in this order. It shows judgment faster than commenting on names.

1. **Invariant.** Can you point to the sentence that says what must remain true? If the pull request only describes the happy path, stop.
2. **Persistence.** Is the idempotency row in the same transaction as the outbox? A comment that says “we should probably store the key” is an incomplete design.
3. **Timeout path.** Follow the exception handler. If it retries with a new UUID, or marks the payout failed, the draft is unsafe.
4. **Retry policy.** Bounded attempts, same key, jitter, and a terminal handoff to an owner. Infinite retry with a new key is the bug.
5. **Tests that should exist.** Crash after commit and before the external call. Duplicate request. Duplicate webhook. Same key with a different body. Illegal transition. You write or demand these even when the generated tests are green.
6. **Rollback fantasy.** If the draft opens a distributed transaction or “rolls back” a partner call, replace it with a compensating intent.
7. **Your name on the merge.** You can accept the scaffolding. You do not accept an unreviewed failure path because the tests the model wrote were passing.

### Story beat if they ask for an example (Context → Responsibility → Decision → Result → Learning)

**Context.** AI coding tools showed up ad hoc across the Skydo team. The upside was real on boilerplate. The risk was PII in prompts and pull requests whose failure behavior nobody could explain, on a platform that moves customer money.

**Responsibility.** I owned adoption for the engineering org, including the standard, not only my own usage.

**Decision.** I wrote down what is allowed in a prompt, kept secrets and payment data out, and set a review rule: if a model drafted the change, the author still walks the failure path in the pull request. We measured whether delivery and quality moved together.

**Result.** Boilerplate and test scaffolding got faster, review norms got clearer, and we saw fewer pull requests that the author could not explain. Financial correctness stayed behind a human gate.

**Learning.** The tool accelerates the parts of the change that are already specified. It does not own the invariant. I still read the timeout and the retry myself.

---

## 7. Cultural strengths

Answer with a story fragment, then the behavior. One or two sentences each. Do not stack all five into a speech.

| Strength | Evidence you can say |
|---|---|
| **Collaboration** | The recon vocabulary only worked because Finance, Ops, and the banking partner used the same seven states. I published the one-pager before writing the matcher. Unreconciled TPV went from ~0.6% to under 0.02%, and Finance stopped filing the same ticket. |
| **Humility** | On the early webhook integration I under-scoped malformed ids and retry storms. I say that as my miss. The fix was a checklist and contract tests, which is how I show the lesson stuck. |
| **Accountability** | I froze the risky path, owned the customer-impact question, and left a mechanism (idempotency checklist on money PRs) so the next miss did not depend on heroics. Incidents overall came down about 30% when we measured business signals, not when we asked people to be more careful. |
| **Openness to feedback** | The dismissive-senior situation taught me to bring receipts and a follow-up date. I also expect that on my own designs: a quant or a compliance partner can stop a cutover if the contract is wrong. The Goldman phased rollout existed so they could. |
| **Stakeholder communication** | Decision memos with the risk on both sides — the onboarding beta, the recon problem statement, the quant-facing ADRs. I would rather walk into one meeting with a written trade-off than negotiate it in fragments. |

If they ask for a weakness that is real and already compensated: you have shipped an integration on an optimistic reading of a partner. The compensation is the failure checklist and the habit of stating `UNKNOWN` before anyone asks. Say that cleanly. Do not dress it up as “I care too much.”

---

## 8. Day-of card

**First sentence of a design answer**  
“I’ll give you assumptions, the invariants, the design, the risks, the trade-off, and the decision. Stop me when you want a branch.”

**Invariants (memorize)**  
1. Legal transitions only. Terminals do not reopen.  
2. Intent + key + outbox commit before the side effect.  
3. Database rollback locally. Compensation after the partner may have moved money.  
4. At most one successful movement per intent. At most one submit if they cannot dedupe.  
5. Unknown is owned. It is not a guess and not a new key.

**Three failures, unprompted**  
Client retry. Partner timeout. Duplicate or late webhook.

**Decision line**  
“Unknown means stop and reconcile. One key. One submit unless they prove they dedupe. Reversal is a new intent.”

**Behavioral shape**  
Context → Responsibility → Decision → Result → Learning. Then stop.

**Story in your pocket for each ask**  
Authority: Goldman quants. Conflict: onboarding memo. Mentoring: design bar. Architecture: payout invariants. Ambiguity: “settled.” Failure: webhooks. AI: human gate on the timeout path.

**Culture, if the question is soft**  
Pick collaboration (shared states) or accountability (the webhook miss). One story. Do not list values.

---

## 9. Practice drills

Do these out loud. The feedback is about what you say unprompted and how short you can keep it.

1. **Four-minute payout.** Hit all five beats. Record it. Cut any sentence that does not state an invariant, a contract, a failure, a trade-off, or a decision.
2. **Ninety-second payout.** Same spine. If you cannot reach the decision line, you are still describing boxes.
3. **Interrupt drill.** Start the four-minute version. At ninety seconds, answer only: “What do you do on timeout?” You should be able to say `UNKNOWN`, same key, no second submit, poll or statement, compensation only after `SETTLED`.
4. **Six stories, ninety seconds each.** Force the learning beat to name a mechanism (ADR, decision memo, checklist, design-doc gate, shared state list).
5. **AI review drill.** Take any money-path function and narrate the seven-point critique in section 6 without looking. The pass condition is that you mention the crash-between-commit-and-call test before you mention style.
6. **Concision pass.** For each story, delete the implementation tour. Keep context, the decision, one number, one practice you installed.

When you can do drills 2, 4, and 5 cleanly, you are practicing the gap they named.
