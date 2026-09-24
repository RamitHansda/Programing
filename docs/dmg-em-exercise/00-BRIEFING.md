# 5-Minute Brief — DMG EM Exercise

## Who you are in this exercise

You are the **new Engineering Manager for Team Aurora**, a DMG pod that owns a service on the **Zero Touch critical path**.

- **Zero Touch** (working definition): the path where customer or partner outcomes complete without human intervention. Failures here don't just page someone — they break the product promise.
- **Team Aurora** owns (at least) **ReschedulingEngine** — the service that re-plans work when reality diverges from the schedule (delays, capacity loss, priority shifts, cascading conflicts).
- DMG's culture signal in the brief: **use AI at every step**. The Loom is where they hear *your* judgment; the AI config repo is where they see *how* you operationalize leverage.

## What they are evaluating (inferred from the structure)

| Signal | Where it shows up |
| --- | --- |
| Self-awareness + operating system as an EM | Part 1 |
| Technical taste on a critical-path design + PR | Part 2 |
| Diagnosis under ambiguity (board + service signals → root causes → week-1 actions) | Part 3 |
| Whether AI is a real multiplier with instrumentation, not a vibe | Part 4 |
| Can you use AI as a teammate (they literally tell you to) | Whole package + submitted config repo |

They say first take is fine. **Clarity of thinking > polish.**

## Timing map (stick to it)

```
0:00–0:30   Name + role framing
0:30–3:00   Part 1 — Intro & strong opinion
3:00–15:00  Part 2 — Design (7) then PR (5)
15:00–23:00 Part 3 — Team health
23:00–30:00 Part 4 — AI config walk + leverage plan
```

If you run long, cut stories — not the diagnosis or the decision.

## Your unfair advantages (use them)

From your actual track record — don't invent new ones:

1. **Critical-path ownership** — Skydo payments/settlement at 10K+/day; Goldman risk compute
2. **Reliability as a management system** — incidents ~−30%; recon 0.6% → &lt;0.02% TPV
3. **AI adoption as an EM product** — Claude/Cursor/Windsurf standards org-wide; ~90% adoption; ~25% PR cycle time ↓ with flat rework
4. **Compliance / trust** — ISO 27001 + SOC 2 as CIO (maps to Zero Touch trust)

## Strong opinion (Part 1 — pick one and commit)

**Recommended:** *"On a Zero Touch critical path, I would rather ship a smaller, observable, reversible change than a 'complete' V2 that the team can't operate at 2am."*

Why it works: a thoughtful peer might argue for long-term architecture purity or "do it right once." Your counter is that **operability is part of correctness** on Zero Touch.

Alternatives (if you prefer):

- *"AI-generated code that touches money/schedule correctness gets *stricter* review, not lighter review — even when velocity is the goal."*
- *"I will kill roadmap work in week one if the service signals say we're burning reliability for features."*

## How to use AI during prep (they want this)

1. Feed this package + the full HTML materials to your assistant
2. Ask it to steelman the V2 design and the PR before you record
3. Ask it to generate counter-arguments to your strong opinion
4. Use your [`dmg-ai-config`](../../dmg-ai-config/) as the artifact you screen-share in Part 4

## Submission artifacts

1. **Loom URL** (unlisted is fine if the form accepts it)
2. **LinkedIn:** https://linkedin.com/in/ramit-hansda
3. **AI config repo URL** → public GitHub repo of `dmg-ai-config/`
4. Form: https://forms.office.com/r/NSJqRhxFsh
