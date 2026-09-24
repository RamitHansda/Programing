# Part 1 — Intro & Strong Opinion (~3 min)

**Say out loud at 0:00:** "Hi, I'm Ramit Hansda."

Then speak roughly this arc. Do not read verbatim — hit the beats.

---

### Beat 1 — Who I am (45–60s)

I'm an Engineering Manager with 10+ years in fintech and financial systems. At Skydo I lead a team of about 12 on our payments and settlement platform — roughly 10,000 international transactions a day — where correctness, idempotency, and reconciliation are non-negotiable. I also led ISO 27001 and SOC 2 as CIO. Before that I was a VP at Goldman Sachs owning distributed market-risk compute.

I'm interviewing for DMG because Team Aurora sits on a **Zero Touch critical path** — the same class of problem I've spent my career on: when the system fails, a human shouldn't have to paper over it, and when we change it, we must be able to prove we didn't break the promise.

### Beat 2 — How I operate as an EM (60–75s)

Three operating principles:

1. **Outcomes over motion.** Sprint green boards don't impress me if service signals are red. I manage to customer-visible reliability and cycle time, not story points burned.
2. **Technical taste without being the bottleneck.** I read designs and PRs on the critical path myself. I don't rewrite my team's code — I raise the bar, name the risk, and leave the fix with the owner.
3. **Systems over heroics.** If the same incident or slip happens twice, we fix the system: ownership, SLOs, review norms, or the AI/config that makes the mistake cheap to catch.

Weekly rhythm in practice: IC-owned 1:1s, a small protected capacity for reliability (~20%), written decisions on hard tradeoffs, and a hiring/growth bar that I refuse to soften when we're behind.

### Beat 3 — Strong opinion a peer might disagree with (45–60s)

**Strong opinion:** On a Zero Touch critical path, I will bias to **smaller, observable, reversible changes** over a "complete" architecture rewrite — even when the V2 design is elegant.

A thoughtful peer might say: *if ReschedulingEngine is foundational, bite the bullet and ship the right model once.* I disagree when the blast radius is Zero Touch. Incomplete observability, missing rollback, or unclear cascade semantics are not "follow-ups" — they are product bugs. I'd rather land V2 behind a flag with proven invariants than merge a beautiful design the on-call can't reason about at 2am.

That's how I intend to show up for Team Aurora.

---

**Transition:** "Moving to part two — technical review."
