# MoneyView EM — Stakeholder Round Spoken Answers
**Chetan Sharma (Head, Strategic Partnerships) · Tue 13 Oct 2026 · 15:30–15:45 IST**  
**Meet:** meet.google.com/qoq-poas-bjo

**How to use:** Practice #1 and #3 out loud once. In the room, hit the beats — don’t recite. Cap every answer at ~90 seconds unless he asks to go deeper.

**Glance card:** `MONEYVIEW-EM-STAKEHOLDER-CHETAN-DAYOF-CHEATSHEET.md`

**Audience shift from Subrata:** Chetan is partnerships / payments integrations / ops / growth — speak **business effect and predictability**, not system design.

---

## 1. “Tell me about yourself” / “Walk me through your background”

**SAY THIS (~60–75 sec):**

> Sure. I’m Ramit — Engineering Manager, a bit over ten years, mostly fintech.
>
> At Skydo I lead twelve engineers on international payments and settlement — about ten thousand plus transactions a day. A big part of the job is partner integrations, reconciliation, and making sure Ops isn’t drowning in exceptions. I also serve as CIO and led ISO 27001 and SOC 2.
>
> Before that I was a VP at Goldman leading nine engineers on market-risk systems.
>
> And I’m a MoneyView alum — Senior SDE in 2019–20 on payment demand generation and reconciliation. Millions of debit instructions a day; we cut manual operations about sixty percent.
>
> I’m excited to come back as an EM because the partnership and integration surface is much larger now — and that’s exactly the Eng–business seam I’ve been operating on.

**If he asks for shorter (~25 sec):**

> EM at Skydo on payments and settlement — twelve people, ten-K-plus a day, partner integrations and recon. Goldman VP before that. MoneyView alum on debit and recon pipelines. Looking to return as a hands-on EM who makes Eng a strong partner for Partnerships and Ops.

---

## 2. “Why MoneyView?” / “Why come back?”

**SAY THIS (~45 sec):**

> Three short reasons.
>
> First — I already know the failure modes that hit partners and ops here: gateway flakiness, debit SLAs, recon gaps, exception queues. That muscle is sharper after Skydo.
>
> Second — the company grew the right way. Personal loans plus lending partners, WhizDM on book, UPI, gold, cards — the partnership and tech-integration surface is the job now, not a side quest.
>
> Third — I want an EM seat where Eng success is measured in predictable go-lives and owned exceptions — not slide decks. That’s how I’ve been running.

**Don’t say:** “I miss the old team,” comp, “easier than Skydo,” vague culture praise.

---

## 3. “How do you work with non-engineering stakeholders / Partnerships / Ops?”

**SAY THIS (~60–75 sec) — default lead answer for this round:**

> My rule is no surprises, no jargon, and status in business impact.
>
> Practically: a short weekly written update — green / yellow / red on the initiatives that matter to you, not Jira. If something turns yellow or red, you get what changed, the mitigation, and the new ETA — plus a clear decision if I need one from Partnerships or Product.
>
> When dates are at risk I don’t just say no. I bring options: cut scope and hit the date, phase the partner go-live, or accept a slip with a hard commit. You choose with eyes open.
>
> On partner integrations specifically, I treat “API works in staging” as incomplete. Done means error model agreed, reconciliation path, ops runbook, and a clear owner for exceptions. That’s how we keep business growth from becoming ops debt.

**One-liner if interrupted:**

> Predictable written status, early risk with options, and partner go-lives that include recon and ops — not just the happy-path API.

---

## 4. “Partner wanted it faster” / “Unrealistic timeline” / “Push back”

**SAY THIS (~60 sec):**

> Classic example: we were asked to integrate a new banking partner in two weeks.
>
> I didn’t argue opinions. I walked Product and the business through the real checklist — API certification, error handling, reconciliation, compliance review, ops readiness. The math wasn’t two weeks.
>
> We aligned on about five weeks with a phased go-live: limited corridor first, then expand. Business got a date they could trust; Eng didn’t silently cut recon.
>
> Lesson: make hidden complexity visible, then offer levers. “Yes, if” beats politics.

**MoneyView bridge:**

> Same pattern for an LSP or payment partner — I’d rather reforecast early with Partnerships than burn Ops for a month after a fake go-live.

---

## 5. “Ops / recon / money missing / partner mismatch”

**SAY THIS (~60–75 sec):**

> At Skydo we had a fog where Finance said money was missing for a customer, and Eng, Ops, Finance, and the bank each meant something different by “settled.”
>
> I owned turning that into a decidable problem: one shared state vocabulary — initiated, debited, converted, remitted, settled, failed, reversed — and a reconciliation ladder instead of one opaque job. Partner files as external truth; exceptions aged in an owned queue.
>
> Unreconciled amount went from about point-six percent of daily TPV to under point-zero-two percent.
>
> And at MoneyView years earlier, the same instinct: idempotent retries and SLA-based scheduling so debit work had deadlines, not just queues — manual ops down about sixty percent.
>
> Lesson for your seat: shared language across Partnerships, Ops, and Eng is as important as the code.

---

## 6. “Growth vs control” / “Product vs compliance” / conflict

**SAY THIS (~45–60 sec):**

> Product wanted faster onboarding of new entity types; Compliance needed stronger controls.
>
> I didn’t pick a side. One-page tradeoff: risk if we ship full vs time-to-value if we wait. Proposed a flagged beta with kill criteria and audit logging.
>
> Beta in about two weeks, full in about five, zero audit findings on that path.
>
> Lesson: quantify the tradeoff and give a reversible path. Absolute no and blind yes both fail when partners and regulators are in the loop.

---

## 7. “First 90 days” / “How would you partner with us?”

**SAY THIS (~45–60 sec):**

> Days one to thirty: listen. Map the partner and integration landscape you care about, the exception queues Ops actually lives in, and where Eng commitments have been surprising Partnerships.
>
> Days thirty to sixty: install a simple operating rhythm with you — written status on partner-facing work, explicit owners for exceptions, and design review on anything that moves money with a partner.
>
> Days sixty to ninety: one visible win — fewer aged exceptions, a cleaner go-live checklist, or a recon gap closed — so trust is earned with evidence, not promises.
>
> Success looks like: Partnerships stops being surprised, and Ops can see stuck work without Slack archaeology.

---

## 8. Questions for Chetan (pick 1–2 — leave time)

1. From your seat, what’s the hardest Eng collaboration pain right now — partner go-live speed, exception/ops volume, or honesty of SLAs and dates?
2. What does a great Engineering Manager look like to Strategic Partnerships in the first ninety days?
3. Where do lending-partner or payments integrations most often slip — technology, process, or unclear ownership across teams?
4. As MoneyView has added products and partners, what should Eng protect at all costs so growth doesn’t create silent ops debt?

**Ask Rishika (if process):** remaining rounds, who owns each, timeline, which team/org the req maps to.

---

## Closing line (if natural · ~15 sec)

> I’m especially motivated by this round because it’s the seam I already lived here as an IC and now run as an EM — making Eng predictable for Partnerships and Ops. I’d love to keep going through the loop.

---

## Quick “don’ts” in his presence

| Tempting | Better |
|---|---|
| Deep system design | One sentence + business outcome |
| “We’ll try our best on the date” | Options with costs |
| Blame the partner bank / LSP | Design for partner failure; own our side |
| Fake current MV partner KPIs | Alumni + Skydo numbers only |
| Pure people-manager talk | Delivery predictability + ops/partner outcomes |
