# EM Playbook — Cost vs. Return on AI Coding Assistants (Founder-Facing)

> Companion to [EM-AI-CODING-ASSISTANT-ADOPTION-MEASUREMENT.md](./EM-AI-CODING-ASSISTANT-ADOPTION-MEASUREMENT.md), which covers adoption and delivery measurement. This doc is specifically about **the money conversation with founders**.

> **The core move**: don't try to win with a big ROI multiple. Win with **(a) fully-loaded actual cost, (b) AI spend as a percentage of engineering payroll, (c) the break-even threshold expressed in hours saved per engineer, and (d) a value range with every assumption visible.** Founders discount confident single-point ROI numbers, because they've seen vendors produce them. They trust a number with a stated downside case.

---

## 1. Why the naive ROI pitch fails

The pitch founders have already heard and dismissed:

> *"200 engineers × 25% acceptance rate × 30% time saved = 50 engineers of free capacity!"*

It fails because it multiplies an engagement metric by an assumed conversion, then treats the result as cash. Three specific holes any competent founder will find:

1. **Time saved on typing code isn't time saved on shipping.** Coding is often not the bottleneck; review, requirements, testing and deploys are.
2. **Saved time isn't automatically reinvested in output.** Some of it is, some isn't.
3. **The cost side is understated** — usage overage, review load, CI compute and enablement time are usually missing.

So build the argument the other way round: start from cost (which is a fact), then show the *smallest* return that justifies it (which is arithmetic), then show what you actually measured (which is evidence).

---

## 2. The cost side — get this exactly right

Founders will forgive an uncertain return estimate. They will not forgive an understated cost.

### 2.1 Cost components

| # | Component | Where the number comes from |
| --- | --- | --- |
| 1 | **Seat/subscription cost** | Invoice. Cursor Teams: Standard $40/user/mo monthly or $32 annual; Premium (5× included usage) $120/mo or $96 annual; Enterprise is custom with pooled usage. Claude Code: Team/Enterprise seats, or Console pay-as-you-go. |
| 2 | **Variable usage / on-demand overage** | The component that surprises people. Cursor: `/teams/spend`, and sum `requestsCosts` from `/teams/filtered-usage-events`. Anthropic Console: `GET /v1/organizations/cost_report` (daily buckets, ~31-day windows, groupable by workspace/description — note Priority Tier spend is excluded and must be tracked via the usage endpoint). Claude Code also emits `claude_code.cost.usage` over OpenTelemetry. |
| 3 | **Idle seats** | Paid seats with no activity. Report separately as *recoverable waste* — it makes you the person managing the budget, not defending it. |
| 4 | **Enablement & platform cost** | Loaded engineer-hours: wiring MCP servers, rules/context files, telemetry pipeline, dashboards, security review, champion time. One-off setup amortised over 12 months + a monthly run-rate. |
| 5 | **Increased review load** | If PR volume rises, reviewer hours rise. Estimate from PR count × median review time × loaded rate, and report the *delta* vs. baseline. |
| 6 | **CI/compute delta** | More PRs and more test runs cost real money. Pull from your CI bill. |
| 7 | **Quality cost (if any)** | If change failure rate or escaped defects rose, that's a genuine cost line. One production incident can exceed a year of seat spend, so if the guardrails are flat, *say so explicitly* — it's a load-bearing part of your argument. |

### 2.2 Two denominators you need

- **Loaded cost per engineer**: total comp × overhead multiplier (typically 1.25–1.4 for benefits, hardware, software, space, employer taxes).
- **Loaded cost per productive hour**: loaded annual cost ÷ ~1,800 productive hours/year (2,080 gross minus leave, holidays, meetings-heavy overhead). State the divisor on the slide; someone will ask.

---

## 3. The return side — three tiers, labelled by how defensible they are

Never blend these into one number. Label each tier on the slide.

### Tier 1 — Measured, from your own systems (strongest)

- **Throughput at constant quality.** Merged PRs (or closed tickets/story points) per engineer per week versus baseline, **with median PR size flat** and change failure rate, revert rate and 21-day churn flat. Without those controls the metric is gameable by splitting PRs, and a founder who has seen this before will ask.
- **Cycle-time reduction**, P50 and P75, first-commit-to-merge.
- **Unplanned-work reduction**: time-to-resolve on bugs and incidents; engineer-hours in unplanned work per sprint.
- **Defects caught pre-merge** by automated review × your own cost of an escaped defect.
- **Onboarding ramp**: time for a new hire to their first merged non-trivial PR. Easy to measure, directly monetisable, and founders feel this one.

### Tier 2 — Survey-based, discounted (directional)

Self-reported hours saved per week × loaded hourly rate × active engineers, **with an explicit haircut** (I use 50%) for self-report optimism and the fact that saved time is only partly reinvested in output. Applying a visible haircut buys more credibility than the number costs you.

### Tier 3 — Real but not monetised (mention, don't model)

Retention and satisfaction (replacing a mid-level engineer costs 6–12 months of salary in recruiting plus ramp), willingness to touch legacy services nobody owns, faster incident comprehension in unfamiliar code, reduced key-person risk. List these as qualitative upside and resist the temptation to put a number on them.

---

## 4. The three numbers that actually land with founders

### 4.1 AI spend as a percentage of engineering payroll

This single framing ends most debates. If fully-loaded AI tooling is 1–3% of engineering payroll, the question stops being *"is the ROI proven?"* and becomes *"is it plausible that this makes the team more than 1–3% more effective?"* — a much easier question, and one the founders will answer themselves.

### 4.2 Break-even expressed in hours per engineer

> *"At our current spend, this pays for itself if it saves each engineer N hours a month. Our measured signal is well above that."*

Compute `N = monthly fully-loaded AI cost per engineer ÷ loaded cost per productive hour`. In most teams N lands between 2 and 6 hours a month — under 1.5% of capacity. State it as the *floor*, not the claim.

### 4.3 Unit economics: cost per merged PR

`total AI spend ÷ merged PRs` nets cost against output automatically and is the right thing to trend monthly. It's also the metric that catches waste (spend rising while output is flat) before a finance review does. Show it next to loaded cost per merged PR so the ratio is obvious.

---

## 5. Worked example (20-engineer team — replace with your actuals)

**Assumptions stated up front:** 20 engineers, blended fully-loaded cost $120k/engineer/year ($10k/month), 1,800 productive hours/year → **$66.70 per productive hour**. Cursor Teams monthly billing, mixed seat types. All figures illustrative.

### Cost — monthly

| Line | Amount |
| --- | --- |
| 14 × Cursor Standard @ $40 | $560 |
| 6 × Cursor Premium @ $120 (heavy agent users) | $720 |
| Claude Code / API usage spend | $400 |
| Enablement & platform run-rate (16 loaded hrs @ $66.70) | $1,067 |
| **Total fully loaded** | **≈ $2,750/mo → ≈ $33k/yr** |
| *Of which recoverable: 2 idle Standard seats* | *$80/mo — being reclaimed* |
| **Per engineer** | **≈ $138/mo** |

### The three headline numbers

- **Share of engineering payroll**: $2,750 ÷ $200,000 = **1.4%**
- **Break-even**: $138 ÷ $66.70 = **2.1 hours per engineer per month** (~29 min/week, ~1.2% of capacity)
- **Cost per merged PR**: $2,750 ÷ 140 PRs = **$20/PR**, against a loaded cost of ~$1,430/PR — **1.4% uplift on the cost of producing a PR**

### Value — the range, with the downside shown

Measured: throughput per engineer **+11%** with median PR size flat, change failure rate flat, revert rate flat.

Gross capacity value = 11% × 20 engineers × $120k = **$264k/yr**. Applying a **50% attribution discount** (team also got better tooling and a stabler roadmap in the same period): **$132k/yr against $33k of cost ≈ 4.0×**.

Cross-check from the survey: 3.5 self-reported hours saved/week, haircut 50% → 1.75 hrs/wk × 4.33 × $66.70 × 17 actives ≈ **$103k/yr ≈ 3.1×**. Two independent methods landing at 3–4× is a much better slide than one method claiming 12×.

### Sensitivity table (this is the slide founders respect)

Annual value vs. $33k annual cost, at 20 engineers / $2.4M payroll:

| Throughput gain → | 5% | 10% | 15% |
| --- | --- | --- | --- |
| **25% attributed to AI** | $30k (**0.9×**) | $60k (1.8×) | $90k (2.7×) |
| **50% attributed** | $60k (1.8×) | $120k (3.6×) | $180k (5.5×) |
| **100% attributed** | $120k (3.6×) | $240k (7.3×) | $360k (10.9×) |

The point of this table is the top-left cell: **even in the pessimistic corner the programme roughly breaks even.** Leading with your downside case is what makes the rest of the numbers believable.

### The line to close on

> The entire annual programme costs about **a quarter of one engineer**. If it buys us even a tenth of an engineer's worth of capacity per five engineers, it's paid for. We're measuring considerably more than that, with quality metrics flat.

### If you're in a lower-wage market, adjust honestly

Tool cost is dollar-denominated; salaries aren't. For a team at ₹40L/year fully loaded (~$48k, ~$26.70/productive hour), the same $138/engineer/month means **AI spend is ~3.4% of payroll and break-even is ~5.1 hours per engineer per month**, roughly 2.5× the US bar. Still small, but don't quote US vendor case-study ratios to your founders — recompute with your own wage base, and be the one who points this out.

---

## 6. The deck (7 slides + appendix)

1. **The decision** — what you're asking for: renew, expand to N seats, change seat mix, or cut. State the number and the date.
2. **What we spend today** — the fully-loaded table, trended over the last 3–6 months, with idle seats and recoverable waste called out by you first.
3. **As a share of engineering payroll** — one number, one chart. This is the reframe slide.
4. **What changed in delivery** — baseline vs. now: cycle time, throughput, with the quality guardrails shown flat on the same slide. Cohort comparison if you have one.
5. **Break-even** — "pays for itself at ~30 min/engineer/week; here's what we measured."
6. **Value range and sensitivity** — the table from §5, with assumptions visible and the downside corner highlighted.
7. **Risks and controls** — quality guardrails, spend controls, seat right-sizing, data policy, and the pre-committed trigger for pulling back.

**Appendix**: methodology, data sources, and a short list of *what I deliberately don't measure and why* (percentage of code written by AI, individual usage rankings, acceptance rate as quality). Founders reading that appendix conclude you can't be fooled by the vendor dashboard — which is the real thing you're selling in this meeting.

---

## 7. Cost-control levers to show you're managing it, not just spending

Bring these unprompted; they change the tone of the meeting from justification to stewardship.

- **Seat right-sizing.** Pull per-user usage and match it to seat type: anyone routinely paying on-demand overage on a $40 Standard seat is cheaper on a $120 Premium seat with 5× included usage; anyone well under the Standard allowance should not be on Premium. Re-run monthly.
- **Spend limits.** Team-wide monthly limits on Teams plans; per-member limits on Enterprise (`/teams/user-spend-limit`). Set them before you need them.
- **Seat reclamation.** Monthly sweep for zero-activity seats, with a light-touch check-in first — an idle seat is usually a stuck engineer, not a saving.
- **Model-mix guidance.** Premium models on trivial tasks is the most common source of avoidable spend. Publish simple defaults.
- **Prompt caching** where you're on token billing — track `cache_read_input_tokens` vs. `uncached_input_tokens` for cache efficiency.
- **Pooled usage** (Enterprise) so heavy and light users average out instead of individually overflowing.
- **A monthly one-page spend review** with cost per active engineer and cost per merged PR. Nothing defuses a "why is this going up?" question like already having the trend on a slide.

---

## 8. Founder objections and how to answer them

**"Just give me the ROI number."**
> "3–4×, and I'll show you the assumptions because the number moves a lot depending on two of them. Here's the sensitivity table — even in the pessimistic corner we break even. I'd rather give you a range I can defend than a point estimate that falls apart under one question."

**"So can we hire fewer engineers?"**
Be careful and be honest. It shows up as **absorbed scope and deferred hiring**, not as layoffs — and only if quality metrics hold. If you have evidence, say "we took on X additional scope this quarter without adding headcount." If you don't, say so; over-promising here is how EMs lose credibility permanently, and the number is easy to check against next quarter's delivery.

**"How do I know this is the tool and not just a better quarter?"**
Cohort comparison (staggered rollout) plus a difference-in-differences read; guardrail metrics flat, which rules out the "shipping faster by cutting corners" explanation; and per-repo breakdown. Then state the limit plainly: it's a quasi-experiment, not an RCT, which is why you apply an attribution discount.

**"Why is usage spend growing month over month?"**
Split growth into more engineers, deeper usage per engineer (good — pair it with the outcome metric), and inefficient model choice (bad — actionable). Show cost per merged PR: if spend grows while that stays flat, spend is tracking output.

**"Couldn't we use the free/cheap tier?"**
Compare cost per merged PR across tiers if you piloted both. The usual finding is that tighter usage limits push engineers back to shallow autocomplete, which is where most of the value disappears. Frame the decision as buying depth of use, not access.

**"What about vendor lock-in and price rises?"**
Portability lives in your artefacts — rules/context files, MCP servers, prompt playbooks — which are largely transferable. Keep at least a credible second option evaluated. And note that at 1–3% of payroll, even a large price rise is a smaller risk than a workflow regression.

**"What if it makes quality worse?"**
That's why the guardrails are on the same slide as the wins, and why you pre-commit the pull-back trigger: e.g. if change failure rate or 21-day churn rises materially for two consecutive months, you tighten review requirements and PR-size limits before expanding further.

---

## 9. Anti-patterns

- **Acceptance-rate arithmetic** (`acceptance % × lines × wage`). It's the fastest way to lose a technical founder.
- **A single-point ROI multiple with hidden assumptions.** Assume it will be quoted back at you next quarter.
- **Ignoring the cost lines that make you look bad** — usage overage, review load, CI compute, idle seats. Report them yourself, first.
- **Treating "% of code written by AI" as a value metric.** The attribution is unreliable (per-machine diff signatures, formatters invalidating them, background agents not attributed to commits yet) and it isn't a measure of value even when accurate.
- **Promising headcount reduction to win the budget.** You will be held to it.
- **Presenting adoption metrics as the outcome.** DAU is not a return; it explains a return.
- **No downside case.** A slide with only upside reads as advocacy. The sensitivity table is what makes it read as analysis.

---

## 10. If you don't have baseline data yet

Don't fabricate a before/after. Instead:

1. **Reconstruct the baseline from Git history** — cycle time, PR size, throughput, revert rate and churn are all computable retroactively for the last 4–6 sprints. Do this first; it takes hours, not weeks.
2. **Run a 6–8 week cohort pilot** with a comparison group rather than a team-wide flip, so attribution is defensible.
3. **Go to the founders early with the measurement plan, not the result.** Agreeing the metrics and the decision criteria *before* the numbers exist removes any suspicion that you picked the metrics that flattered the outcome — and it's the single highest-leverage thing you can do for the credibility of the eventual pitch.

---

## 11. References

- Cursor: [Teams pricing](https://cursor.com/docs/account/teams/pricing) · [Admin API (spend, usage events, per-user spend limits)](https://cursor.com/docs/account/teams/admin-api) · [Analytics API](https://cursor.com/docs/account/teams/analytics-api) · [Pooled usage (Enterprise)](https://cursor.com/docs/enterprise/pooled-usage)
- Anthropic: [Usage & Cost Admin API](https://platform.claude.com/docs/en/manage-claude/usage-cost-api) · [Cost report endpoint](https://platform.claude.com/docs/en/api/admin/cost_report) · [Claude Code Analytics API](https://platform.claude.com/docs/en/manage-claude/claude-code-analytics-api) · [Claude Code OpenTelemetry](https://code.claude.com/docs/en/monitoring-usage)
- Frameworks to name-drop if asked how you chose the metrics: **DORA**, **SPACE**, **DX Core 4**.
