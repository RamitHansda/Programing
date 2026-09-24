# Part 2 — Technical Review (~12 min)

**Materials (from DMG HTML):** V2 Design Proposal for ReschedulingEngine · PR #1842  
**Split:** Design ~7 min → PR ~5 min  
**Mindset:** Review as you would with *your* engineers — curious, specific, decision-oriented. Not a teardown monologue.

**Screen-share the real docs.** Use this playbook so you don't freeze.

---

## Opening line

"Moving to part two. I'll review the V2 design first the way I would in a design review, then the first implementation PR."

---

## Design review (7 min) — forced structure

Walk the design in this order. Skip a section only if the doc already answered it clearly.

### 1. Problem & success metric (60s)

Say out loud:

- What failure mode does V2 eliminate that V1 cannot?
- What is the **Zero Touch** success metric? (e.g. % of disruptions resolved without human touch, p99 time-to-stable-schedule, cascade depth, customer-visible delay)
- What is explicitly **out of scope**?

**Red flag if missing:** a design that describes *how* without *how we'll know it worked*.

### 2. Invariants (90s) — this is where EMs earn trust

Name 3–5 invariants you expect on a rescheduling engine:

1. **Idempotency** — re-processing the same disruption must not thrash the schedule
2. **Bounded cascade** — a conflict resolver cannot unbounded-churn the plan
3. **Priority / freeze semantics** — human or policy freezes must be respected (Zero Touch ≠ ignore overrides)
4. **Partial failure safety** — mid-cascade crash leaves a recoverable, auditable state
5. **Explainability** — every change has a reason code a support engineer can read

Ask: *which of these does the V2 doc make testable?*

### 3. Consistency & data model (90s)

Probe:

- Source of truth for "current schedule" vs "proposed schedule"
- Optimistic concurrency / versioning / fencing tokens when two disruptions arrive
- Ordering: event time vs processing time
- Replay / backfill story

**Phrase to use:** "If two disruptions land 50ms apart for overlapping resources, what is the observable outcome?"

### 4. Operability (60s)

On Zero Touch, design review includes ops:

- Feature flag / dark launch / shadow mode?
- Rollback without stranding in-flight reschedules?
- Dashboards: cascade depth, unresolved conflicts, human-touch rate, SLO burn
- Kill switch if the engine oscillates

### 5. Decision (60s)

End the design segment with a clear call:

> "I'd approve this to proceed **with conditions** / **request a revision** / **approve as-is**."

Pick one. Conditions should be concrete (e.g. "add cascade budget + shadow mode before merge train").

---

## PR #1842 review (5 min) — forced structure

### 1. Does the PR match the design slice? (60s)

- Is this the *smallest* vertical slice that proves an invariant, or a drive-by rewrite?
- Test plan: which invariant is actually asserted?

### 2. Correctness under failure (90s)

Scan / narrate:

- Retries, timeouts, poison messages
- Locking / transactions / outbox
- What happens if the PR's new path throws halfway
- Logging: correlation id tying disruption → decisions → writes

### 3. Review quality bar (60s)

Say what you'd ask the author to change **before merge** — prefer 2–3 sharp asks over 10 nits.

Examples of sharp asks (adapt to the real PR):

- "Missing property test / simulation for cascade bound"
- "No metric for human-touch fallback rate"
- "Migration is expand/contract incomplete — old readers break"
- "Error path returns success to the caller — we'll silently skip reschedules"

### 4. People note (30s)

One coaching line:

> "The design intent is clear; the PR needs to make the invariant *machine-checkable*. That's the bar for Zero Touch."

---

## Live phrases that sound like a strong EM

- "I'm not debating taste — I'm asking what breaks at 2am."
- "Show me the rollback."
- "If we can't measure cascade depth, we can't claim Zero Touch."
- "I'd rather delay the API surface than ship without the audit trail."

## Anti-patterns (don't do these)

- Reading the doc aloud for 7 minutes
- "Looks good overall" with no decision
- Fixating on naming / formatting in a 5-minute PR review
- Pretending you read lines you didn't — narrate what you're looking *for*

---

**Transition:** "Moving to part three — team health."
