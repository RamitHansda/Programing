# Hiring Manager Round — Ritesh Sinha

**When:** Tuesday, Sep 30, 2026, 6:30–7:30pm IST (60 minutes, video)  
**Interviewer:** Ritesh Sinha  
**Candidate:** Ramit Hansda

**Working assumption:** This is Turing, and Ritesh is Head of AI Architecture (Sr. Director). Public profile: he leads agentic systems, evals, RAG, prompt tooling, and NL2SQL at Turing; before that he was a Principal Software Engineering Manager at Microsoft on Copilot infrastructure (automated prompt tuning for M365 copilots, and a GDPR lineage graph at billion-node scale). This repo already has Nate’s Turing PR-review round. If your loop is a different Ritesh, keep the clock and the stories, and replace the “why this team” paragraph.

Story text and the payout design live in [`docs/em-interview/LEADERSHIP-DISCUSSION-PREP.md`](../em-interview/LEADERSHIP-DISCUSSION-PREP.md). Copilot depth, if he pulls the thread: [`docs/ai/AGENTIC-SUPPORT-COPILOT.md`](../ai/AGENTIC-SUPPORT-COPILOT.md).

---

## 0. Confirm the invite

Send this before the interview:

> Hi, confirming receipt. I will join the video interview on Tuesday, Sep 30, 2026, 6:30–7:30pm IST with Ritesh Sinha. Thank you.

Join five minutes early. One page of notes: the day-of card at the bottom of this file. Water, charged laptop, phone hotspot ready.

---

## 1. What this hour decides

He is deciding whether he would trust you with ambiguous, cross-team work on his staff. The earlier round already scored reasoning, concurrency, testing, and architecture. He will reopen design only to see whether you lead with the invariant, name the failure yourself, and stop.

What he is listening for, given his job:

| He has spent his career on | So show |
|---|---|
| Copilots and agentic systems | The support copilot as a **trust** problem: the model proposes, policy decides, money movement stays outside the autonomous loop |
| Evals and prompt infrastructure | You measured the system (first response, triage rate, SLA) and you gate autonomy on evidence |
| Platform correctness at Microsoft scale | Payments invariants: one movement per intent, unknown is a state, compensation after the partner may have moved money |
| Leading senior ICs | Mentoring and design-doc bar, told as how judgment spread, not as a headcount story |
| Distributed org | Written decisions: ADRs, the onboarding memo, the recon one-pager |

Staff framing for the whole hour: you have led twelve people. The work you want next is technical direction that other senior engineers adopt. Mentoring stays. You are not pitching an EM seat unless he opens that door.

---

## 2. Clock

| Min | You |
|---|---|
| 0–2 | Thanks, confirm the hour. Offer the agenda in one sentence. |
| 2–8 | Background, then why this team. |
| 8–25 | One deep project. Default to the copilot. Switch to payout if he asks how you handle irreversible side effects. |
| 25–48 | Two or three leadership stories. Let him pick. Have failure and influence ready. |
| 48–55 | His questions to you, or one more story if he is still probing. |
| 55–60 | Your questions. Close. |

Agenda line:

> Happy to use this hour on how I make decisions and work across people who don’t report to me. If a project is useful, I can go deep on the support copilot or on payout failure semantics. Where do you want to start?

---

## 3. Spoken open

### Background (about 70 seconds)

> I’m Ramit. For the last several years I’ve owned payments and settlement at Skydo, a cross-border platform at about 10,000 international transactions a day. The hard part was correctness when the bank is outside our transaction: idempotency, state transitions, and reconciliation. Unreconciled value moved from about 0.6% of daily volume to under 0.02%, and production incidents came down about 30%.
>
> Alongside that I designed an agentic support copilot: hybrid retrieval, read-only tools, human approval, and an audit log. First response went from about 90 minutes to about 10–12 minutes, and roughly 65% of tickets auto-triage. The agent cannot move money. I also set the team standard for AI coding tools, with a human still owning the failure path on anything financial.
>
> Before Skydo I was a VP at Goldman on distributed market-risk compute, team of nine, promoted within a year. I led twelve engineers at Skydo and mentored eight. The through-line is the same: write the invariant, then the system, and leave a practice other people can run.

### Why this team (about 40 seconds)

> Turing is building agents that have to be right on real work, with evals and tool use as the product, not a demo. That is the same shape as the copilot, at a different scale: a model that can propose, a policy layer that can refuse, and a record of why. I want a Staff seat where that judgment becomes a standard across teams. I’ve done the people leadership. The next scope I want is technical direction seniors adopt, especially where an agent action is hard to undo.

### If he asks why you would leave Skydo

> I’ve taken payments and the copilot from zero to a system Finance and Support actually trust. I want the next problem to be agent correctness at the scale where evals, data contracts, and failure handling are the platform. I’m proud of Skydo. I’m looking for a peer set that already lives in that problem.

### If he asks Staff versus manager

> Leading twelve people taught me how judgment spreads: design docs, ADRs, pairing, a written bar. I still want to mentor. The role I want is the one where my name is on the invariant and the standard, and senior engineers ship to it without me in every review.

---

## 4. Project he is most likely to pick

Lead with the copilot. Keep the payout story in your pocket for “what about irreversible actions?”

### Copilot, three minutes

**Context.** Support at Skydo was slow, and a wrong answer on a payments ticket is a trust and compliance problem. People wanted “put a model on the queue.”

**Responsibility.** I owned the architecture and the decision about what the agent was allowed to do.

**Decision.** I wrote the invariants before the stack.

- The model may propose. A deterministic policy layer decides whether anything leaves the system.
- Tools are read-only. Refunds, KYC changes, and payouts are outside the autonomous loop.
- Low confidence escalates to a human. It does not auto-send.
- Every decision is reconstructable: retrieved evidence, tool calls, model version, policy version, human action.
- Auto-send has a kill switch, and it expands only after a measured gate. Retrieval over fine-tuning, because policy text changes and we need to point at the source.

**Risks I volunteer.** Retrieval miss, prompt injection through a ticket, a tool that looks read-only and is not, and a confident wrong draft. The mitigation is the same shape as payments: fail closed, attribute the source, and keep a human on the irreversible path.

**Result.** First response about 90 minutes to about 10–12 minutes. About 65% auto-triage. SLA breaches down about half. No autonomous money movement.

**Learning.** Autonomy is something you earn with an eval, not something you turn on because the demo looked good.

If he wants the diagram, go to assumptions → design → risks → trade-offs → decision, and stop at four minutes. Deeper component notes are in the copilot design doc. Do not tour all nine components.

### Bridge if he asks about side effects

> Same rule as a payout. Record the intent before the effect. If the outcome is ambiguous, stop and find out; do not retry with a new identity. If money may already have moved, the recovery is a new compensating action, not an undo. On the copilot I applied that by refusing the effect entirely: the agent never got a write tool.

Ninety-second payout, if he chooses systems instead: section 3.1 of the leadership prep.

---

## 5. Stories to have hot

Use Context → Responsibility → Decision → Result → Learning. Ninety seconds. One number. One practice you installed. Full wording is in the leadership prep.

| If he asks | Story | Learning line |
|---|---|---|
| Influence without authority | Goldman quants and regional owners (§5.1) | Irreversible compute changes waited on a written contract the downstream owner had seen |
| Conflict | Onboarding versus compliance (§5.2) | One memo: beta for a whitelisted cohort, full rollout after the audit trail |
| Mentoring seniors | Design-doc bar, eight engineers (§5.3) | Coverage of major reviews went from about 30% to essentially all of them, without every review coming to me |
| A hard technical call | Payout invariants and the recon ladder (§5.4) | Unknown is a state. Local transactions roll back. External money is compensated |
| Ambiguity | “Settled” meant four things (§5.5) | Shared states before any matcher. Unreconciled volume 0.6% to under 0.02% |
| A time you were wrong | Partner webhooks (§5.6) | Timeout, duplicate, and malformed id are tests before I call an integration done |
| How you use AI | Human gate on model diffs (§6) | I read the timeout and the retry myself. The merge is mine |
| Stakeholders / writing | Recon one-pager or the onboarding memo | I would rather one meeting with a written trade-off than a week of Slack |

Have failure (webhooks) and influence (Goldman or the compliance memo) ready even if he does not name them. Hiring managers often end on “tell me about a miss” and “tell me about a time you needed someone you don’t manage.”

Culture, if the question is soft: one story. Collaboration is the shared settlement vocabulary. Accountability is the webhook miss. Humility is saying the miss was an under-scoped failure model, not a flaky partner.

---

## 6. AI judgment, because this is his domain

If he asks how you review model-written code, use the ninety-second answer in the leadership prep, section 6. The line he should remember:

> On a money path, or any path that is hard to undo, I check four things before style: durable intent before the side effect, retry reuses the same key, timeout enters an unknown state, and there is a test for the crash between commit and the external call. If those are missing, I rewrite that path. The model can draft the scaffolding. I own the merge.

Connect it to his world in one sentence if it fits naturally:

> That is the same bar I would want on an agent that can call a tool: the eval and the policy own the action, the model does not.

---

## 7. Questions to ask him

Ask two. Three only if time is left. Skip compensation, level haggling, and visa. Those belong with the recruiter.

1. In your org, what does a Staff engineer own in the first six months that a senior engineer would not?
2. Where is the sharp edge right now: evals, agent side effects, retrieval quality, or the platform those sit on?
3. When an agent can take an action that is hard to undo, who is allowed to approve that, and what evidence do you require first?
4. What does a design review look like when the reviewers are in other time zones?
5. What would you want me to have written down by the end of month two?

Closing line:

> This was useful. I’m interested in the Staff scope we talked about, especially where agent actions have to be correct under failure. Happy to go deeper on anything we skipped.

---

## 8. Day-of card

**Offer:** “Decisions and cross-team work. Deep dive on the copilot, or payout failure semantics if you prefer.”

**Open, three beats:** payments correctness (10K/day, 0.6% → &lt;0.02%, incidents −30%) → copilot (90 min → 10–12, ~65% triage, no money tools) → Goldman, then Staff scope.

**Copilot invariants:** model proposes, policy decides, tools read-only, low confidence escalates, every decision reconstructable, kill switch, eval before more autonomy.

**Payout invariants if he switches:** legal transitions only; intent + key + outbox before the call; rollback locally, compensate after; at most one movement; unknown is owned.

**Stories:** influence = Goldman. Conflict = onboarding memo. Mentor = design bar. Failure = webhooks. AI = I still read the timeout path.

**Shape:** Context → Responsibility → Decision → Result → Learning. Then stop.

**Ask:** what a Staff engineer owns here that a senior does not, and what evidence you require before an agent action is hard to undo.
