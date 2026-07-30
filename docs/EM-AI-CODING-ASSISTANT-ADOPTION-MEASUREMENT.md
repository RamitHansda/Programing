# EM Playbook — Driving & Measuring AI Coding Assistant Adoption (Cursor, Claude Code)

> **The question**: *"As an EM, how do you make sure AI coding assistants like Claude Code and Cursor are actually being used successfully by your team — and how do you measure it?"*
>
> **The one-line answer**: Treat it as a **product rollout with an outcome metric**, not a tool licence purchase. Adoption is the *input*; the *outcome* is faster delivery of working software at an equal-or-better quality bar. Measure both, and never let the input metric become the goal.

---

## 1. Define "successful" before measuring anything

Most teams fail here. They measure "is the tool being opened" and declare victory. Write down the success statement first:

> *Within two quarters, our team ships the same or more customer-visible change per sprint, with lower cycle time and no regression in change-failure rate, while engineers report that AI assistance meaningfully removes low-value work.*

That statement forces four measurement dimensions:

| Dimension | Question it answers | Nature |
| --- | --- | --- |
| **Adoption** | Are people using it at all? | Leading input |
| **Depth / capability** | Are they using it *well* (agents, rules, context, MCP) vs. autocomplete only? | Leading input |
| **Delivery outcome** | Are we shipping faster? | Lagging outcome |
| **Quality guardrail** | Are we breaking more things or creating rework? | Counter-metric |
| **Cost & value** | Is the spend justified? | Efficiency |

**Rule I hold to:** every adoption metric must be paired with a guardrail metric, otherwise the team optimises the number instead of the outcome.

---

## 2. The metric tree

### 2.1 Adoption (leading)

| Metric | Target shape | Why |
| --- | --- | --- |
| Weekly active users / eligible engineers | ≥ 85% by end of Q1 of rollout | Basic penetration |
| Active days per engineer per week | ≥ 3 | Distinguishes habit from experiment |
| Seat utilisation (licences used vs. paid) | ≥ 90% | Cost hygiene; reclaim idle seats |
| Sessions per active day | Trend, not target | Detects "opened once, gave up" |
| Time-to-first-value for a new joiner | < 3 days | Onboarding health |

### 2.2 Depth of use (leading, and the most predictive one)

Autocomplete-only usage produces marginal gains. The step-change comes from agentic and context-engineered usage.

| Metric | Source |
| --- | --- |
| Agent/Composer usage share vs. Tab-only usage | Cursor per-user `agent-edits` vs. `tabs` |
| Share of engineers whose repos have rules/context files (`.cursor/rules`, `AGENTS.md`, `CLAUDE.md`) | Repo scan in CI |
| MCP server adoption (engineers connected to internal MCP tooling) | Cursor MCP adoption endpoint |
| Custom commands / skills / plans usage | Cursor by-user `commands`, `skills`, `plans` |
| Automated code review coverage (e.g. Bugbot reviews per PR) | Cursor Bugbot analytics |
| Claude Code sessions and active time per engineer | Claude Code analytics / OTel |
| Diversity of task types delegated (tests, migrations, refactors, docs, incident triage) | Quarterly survey + spot checks |

### 2.3 Delivery outcome (lagging — this is what you report upward)

Standard DORA + flow metrics, sourced from Git/CI, **not** from the AI vendor:

- **Cycle time**: first commit → merged, and PR opened → merged (review latency broken out separately).
- **Throughput**: merged PRs per engineer per week, plus median PR size (watch that throughput isn't just PR-splitting).
- **Deployment frequency** and **lead time for change**.
- **Time to resolve** for bug tickets and incidents.
- Optional: share of sprint committed-vs-delivered stability.

### 2.4 Quality guardrails (counter-metrics — non-negotiable)

- **Change failure rate** and **revert rate** (`git revert` + hotfix commits).
- **Rework / churn**: lines changed again within 21 days of merge. AI-heavy code that churns is a red flag.
- **Escaped defects** per release, and **incident count/severity**.
- **Review latency and review depth** — if PRs get 3x bigger and review time drops, review has become rubber-stamping.
- **Test coverage delta** and **flaky test rate**.
- **Security/SAST findings per KLOC** and new dependency additions per sprint (agents love adding libraries).

### 2.5 Cost & value

- Spend per active engineer per month; spend per merged PR.
- Model mix (are people burning premium models on trivial tasks?).
- Per-user spend limits as a guardrail, reviewed monthly.
- Compare against a crude value proxy: (cycle-time reduction × loaded engineering cost). Directional only — never present it as precise ROI.

### 2.6 Perception (the S, P and E of SPACE)

Quarterly DevEx pulse, 5-point Likert, per-engineer anonymous:

1. AI assistance saves me meaningful time each week. *(also ask: roughly how many hours?)*
2. I trust the code it produces enough to put my name on the PR.
3. Reviewing AI-assisted PRs from teammates is no harder than reviewing hand-written ones.
4. I know how to give it the right context in this codebase.
5. Where does it help most / least? *(free text — this drives your enablement backlog)*

Self-reported time saved is a legitimate, well-established measure. Report it as perception, alongside — never instead of — Git-derived outcome data.

---

## 3. Where the data actually comes from

### 3.1 Cursor (Enterprise plans expose APIs)

| API | Useful endpoints | What you get |
| --- | --- | --- |
| **Analytics API** | `/analytics/team/dau`, `/analytics/team/models`, `/analytics/team/mcp`, `/analytics/team/conversation-insights`, `/analytics/team/bugbot-reviews` | DAU, model usage, MCP adoption, review analytics |
| **Analytics API (by user)** | `/analytics/by-user/{agent-edits,tabs,models,mcp,commands,skills,plans,ask-mode,top-file-extensions}` | Depth-of-use signals per engineer |
| **Admin API** | `/teams/daily-usage-data`, `/teams/filtered-usage-events`, `/teams/spend`, `/teams/audit-logs`, `/teams/user-spend-limit` | Usage, cost attribution, governance |
| **AI Code Tracking API** | `/analytics/ai-code/commits`, `/analytics/ai-code/changes` | Per-commit AI attribution with repo/branch metadata |

Docs: [Analytics API](https://cursor.com/docs/account/teams/analytics-api), [Admin API](https://cursor.com/docs/account/teams/admin-api), [AI Code Tracking API](https://cursor.com/docs/account/teams/ai-code-tracking-api), [Team analytics overview](https://cursor.com/docs/account/teams/analytics). Multi-team orgs use the [Organization Admin API](https://cursor.com/docs/account/organizations/organization-admin-api) for pooled usage and spend.

**Caveats you must know before quoting "% of code written by AI":**

- Attribution works by matching on-device diff signatures at commit time. The commit must be scored **on the same machine** where the code was authored.
- **Automated formatters can invalidate signatures**, undercounting AI contribution.
- Background/cloud agents and the CLI aren't attributed to commits yet — dashboard adoption covers them, commit-level line attribution covers Tab and Agent/Composer.
- Compute the AI share as `tabLinesAdded + composerLinesAdded`, **not** `totalLinesAdded − nonAiLinesAdded`: blank-line handling differs between those fields, so the subtraction can go negative. The API is alpha and field semantics may still change.

### 3.2 Claude Code

- **Dashboards**: `claude.ai/analytics/claude-code` for Team/Enterprise (usage + contribution metrics with GitHub integration, leaderboard, export); `platform.claude.com/claude-code` for Console/API customers (usage and spend).
- **Claude Code Analytics Admin API**: `GET /v1/organizations/usage_report/claude_code` with `starting_at` (`YYYY-MM-DD`), `limit` (max 1000), and cursor `page`. Returns **daily, per-user** records: sessions, lines of code, commits, pull requests, tool usage, tokens and estimated cost by model. Needs an Admin API key; unavailable for individual accounts; up to ~1 hour data delay. ([docs](https://platform.claude.com/docs/en/manage-claude/claude-code-analytics-api))
- **OpenTelemetry** for near-real-time, and the only path if you run Claude Code via Bedrock/Vertex/a gateway, or on a plan without API access. Set `CLAUDE_CODE_ENABLE_TELEMETRY=1` (push it org-wide through the managed settings file) and export to your existing collector. Key metrics: `claude_code.session.count`, `claude_code.lines_of_code.count`, `claude_code.commit.count`, `claude_code.pull_request.count`, `claude_code.token.usage`, `claude_code.cost.usage`, `claude_code.active_time.total`, `claude_code.code_edit_tool.decision`. For Prometheus, set `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=cumulative`. ([docs](https://code.claude.com/docs/en/monitoring-usage))

### 3.3 Your own systems (the source of truth for outcomes)

Vendor dashboards can only tell you about *their* tool. Outcome and quality metrics must come from systems the vendor doesn't control:

- **Git/GitHub**: PR lifecycle timestamps, size, review latency, reverts, churn, CODEOWNERS review depth.
- **CI/CD**: build success, deploy frequency, lead time, flaky tests, coverage.
- **Incident/ticket systems**: escaped defects, MTTR, change failure rate.
- **Optional platform layer**: an engineering-intelligence tool (DX, Jellyfish, Faros, minware, LinearB) if you want vendor metrics joined to Git metrics without building the pipeline yourself. Building it yourself is a few days of work: pull the vendor APIs nightly into a warehouse table keyed by `(date, engineer, repo)`, join to a Git-derived PR table, expose one dashboard.

---

## 4. Rollout design that makes measurement possible

Measurement fails when there's no baseline. Sequence it deliberately:

1. **Baseline first (4–6 sprints of history).** Extract cycle time, throughput, PR size, change failure rate, revert rate and rework *before* broad rollout. Git history means you can compute this retroactively — do it on day one.
2. **Staggered cohorts, not a big bang.** Roll out to 2–3 volunteers per squad first. This gives you a natural comparison group for a difference-in-differences read instead of a "everything changed at once" story that confounds with team growth, reorgs and roadmap shifts.
3. **Set the enablement bar, not a usage quota.** Required: rules/context files in each active repo (`AGENTS.md` / `CLAUDE.md` / `.cursor/rules`), internal MCP servers wired up, a shared prompt/playbook library, and a written policy on what may not be sent to a model.
4. **Name champions.** One per squad, explicitly in their goals. Weekly 30-minute demo slot where someone shows a real task end-to-end. Recorded. This moves the depth metrics far more than any dashboard.
5. **Publish the definition of done.** The author owns the code, regardless of who typed it. Reviews still apply. Tests still required. AI-generated code is not a review exemption — say this out loud, early.
6. **Review at 30 / 60 / 90 days** against the scorecard, then quarterly. At each review, one decision: expand, adjust enablement, or pull back.
7. **Close the loop on friction.** Every survey complaint becomes a backlog item — missing context files, slow monorepo indexing, a missing MCP integration, an unclear policy.

---

## 5. The scorecard I'd actually present

| # | Metric | Source | Cadence | Illustrative target |
| --- | --- | --- | --- | --- |
| 1 | Weekly active engineers / eligible | Cursor DAU; Claude Code analytics | Weekly | ≥ 85% |
| 2 | Active days per engineer per week | Vendor analytics | Weekly | ≥ 3 |
| 3 | Agentic usage share (vs. Tab-only) | Cursor by-user agent-edits/tabs | Monthly | ≥ 60% of actives |
| 4 | Repos with context/rules files | CI repo scan | Monthly | 100% of active repos |
| 5 | PR cycle time (P50/P75) | Git | Sprint | −25% vs. baseline |
| 6 | Merged PRs per engineer per week | Git | Sprint | ≥ baseline |
| 7 | Median PR size | Git | Sprint | Flat or lower *(guardrail)* |
| 8 | Change failure rate | CI + incidents | Monthly | ≤ baseline *(guardrail)* |
| 9 | Revert + hotfix rate | Git | Monthly | ≤ baseline *(guardrail)* |
| 10 | 21-day code churn | Git | Monthly | ≤ baseline *(guardrail)* |
| 11 | Review latency & comments per PR | Git | Monthly | Latency down, comments not collapsing *(guardrail)* |
| 12 | Escaped defects per release | Tickets | Monthly | ≤ baseline *(guardrail)* |
| 13 | Self-reported hours saved / week | Survey | Quarterly | Trend up |
| 14 | Trust in AI-assisted code (Likert) | Survey | Quarterly | ≥ 4.0 |
| 15 | Spend per active engineer | Admin/billing APIs | Monthly | Within budget envelope |

Report items 5–12 as the headline to leadership. Items 1–4 are *your* operating metrics — they explain the headline, they aren't the headline.

---

## 6. Anti-patterns (say these unprompted; it signals maturity)

- **Ranking individuals on AI usage or AI-authored lines.** The vendor leaderboard is an enablement tool for finding champions and people who are stuck, not a performance instrument. The moment it enters a perf review, your data is corrupted and trust is gone.
- **Mandating a "% of code written by AI" target.** It's a Goodhart's-law magnet, it rewards verbose generated code, and the attribution mechanism isn't reliable enough (formatters, background agents, per-machine signatures) to hang a target on.
- **Lines of code as productivity.** More lines with an assistant is trivially achievable and often actively bad.
- **Acceptance rate as a quality signal.** High acceptance can mean the suggestions are good, or that people have stopped reading them carefully.
- **Vanity ROI arithmetic.** "20% acceptance × 200 engineers = 40 engineers of free capacity" doesn't survive contact with reality. Quote cycle time and quality; quote perceived time saved as perception.
- **Ignoring review load.** Generation capacity rising while review capacity is fixed just moves the bottleneck. Watch review latency and PR size, and be willing to invest in automated review to rebalance.
- **Measuring only the tool.** If your entire dashboard comes from the vendor, you're measuring engagement, not engineering.

---

## 7. Common failure modes and the intervention

| Symptom in the data | Likely cause | Intervention |
| --- | --- | --- |
| High DAU, no cycle-time change | Autocomplete-only usage | Depth enablement: agent workflows, task-type playbooks, live demos |
| Adoption plateaus at ~50% | Poor context setup or a bad first experience | Repo rules/context files, monorepo indexing fixes, 1:1 pairing with holdouts |
| Cycle time down, churn and reverts up | Review bar slipping | Reassert PR-size limits, add automated review, tighten test requirements |
| Reverts flat but review latency doubles | Reviewers are the new bottleneck | Smaller PRs, automated first-pass review, rebalance reviewer load |
| Cost spiking, outcomes flat | Premium models on trivial tasks; runaway agent loops | Per-user spend limits, model-selection guidance, cost visibility per engineer |
| A few power users, long tail idle | Knowledge concentration | Champions programme, shared prompt library, reclaim genuinely unused seats |
| Engineers report low trust | Weak context, unfamiliar domains | Better rules files, MCP access to internal docs/APIs, agree where *not* to use AI |

---

## 8. Interview-ready answer (2–3 minutes, STAR shape)

**Framing (15s)**
> I treat it as a product rollout with an explicit outcome metric, not a licence purchase. Adoption is my input metric; my outcome metric is delivery speed at an unchanged quality bar. And I pair every adoption metric with a guardrail, because otherwise the team optimises the dashboard instead of the outcome.

**What I do (60s)**
> First, baseline before rollout — cycle time, throughput, PR size, change failure rate, revert and rework rates for the prior 4–6 sprints, all from Git and CI, which I can compute retroactively.
>
> Second, stagger the rollout by cohort so I have a comparison group rather than one big confounded before/after.
>
> Third, set an enablement bar rather than a usage quota: every active repo gets rules/context files, internal MCP servers are wired up, there's a shared playbook of what works per task type, and a champion per squad with a weekly demo slot. Depth of use is what actually moves outcomes — an engineer using only tab-completion gets a fraction of the value of one running agentic workflows with good context.
>
> Fourth, I'm explicit that the author owns the code. AI-generated code is not a review exemption.

**How I measure (45s)**
> Three data planes. Vendor telemetry for adoption and depth — Cursor's Analytics and Admin APIs give per-user agent-versus-tab usage, MCP adoption, model mix and spend; Claude Code exposes a daily per-user analytics API plus OpenTelemetry metrics for sessions, lines, commits, PRs and cost. Git and CI for outcomes — cycle time, throughput, PR size, change failure rate, reverts, 21-day churn, review latency, escaped defects. And a quarterly DevEx survey for perceived time saved and trust.
>
> I report the Git-derived outcomes upward; the vendor metrics are my operating instruments to diagnose *why* those outcomes moved.

**Guardrails and judgement (30s)**
> I deliberately don't set a "percentage of code written by AI" target, and I don't rank individuals on usage. Attribution isn't reliable enough — formatters invalidate diff signatures, background agents aren't attributed to commits yet — and more importantly it's a Goodhart's-law magnet. The failure mode I watch hardest is generation capacity outrunning review capacity: cycle time falling while PR size, churn and reverts climb. That's not a productivity win, it's deferred cost, and I'd rather catch it in month two than in an incident review.

**Result line (if you have one — use your real numbers)**
> On my last team, [X]% weekly active within a quarter, P50 PR cycle time down [Y]%, change failure rate flat, and reverts unchanged — which is the combination that let me argue for expanding the seat budget.

---

## 9. Likely follow-up questions

**"What if adoption is high but delivery hasn't improved?"**
Almost always shallow usage or a bottleneck elsewhere. Split actives by agentic vs. tab-only and compare their cycle times. If both are flat, the constraint isn't code authoring — it's review latency, environment/CI slowness, or requirements churn. I'd then point the AI investment at the actual constraint (test generation, CI triage, automated review) rather than pushing more authoring adoption.

**"An engineer refuses to use it. What do you do?"**
I care about outcomes, not tool compliance. I'd sit with them once to separate a genuine bad experience (poor context setup, an unhelpful domain, a real quality concern) from principled objection. Bad experience is my problem to fix. If their delivery and quality are strong without it, that's fine — but I'd ask them to stay conversant, because reviewing teammates' AI-assisted code is now part of the job. What I would *not* do is set a usage quota.

**"How do you handle the quality and security risk?"**
The author owns the code — that's the policy. Then: review requirements unchanged, PR-size limits so review stays real, automated review as a first pass, SAST and dependency scanning in CI, and a written data policy on what can't be sent to a model. I watch new-dependency additions per sprint, because agents add libraries readily.

**"How do you justify the cost?"**
Spend per active engineer and per merged PR, against cycle-time movement and quality staying flat. I'd present it as a directional efficiency argument, not a fabricated ROI multiple, and I'd bring seat-reclamation and model-mix discipline to the same conversation so it's clear the spend is managed.

**"Would you use the leaderboard in performance reviews?"**
No. It's for finding champions and finding people who are stuck. Using it for evaluation would corrupt the metric within a week and cost me the team's trust in every other metric I publish.

---

## 10. Reference links

- Cursor: [Team analytics](https://cursor.com/docs/account/teams/analytics) · [Analytics API](https://cursor.com/docs/account/teams/analytics-api) · [Admin API](https://cursor.com/docs/account/teams/admin-api) · [AI Code Tracking API](https://cursor.com/docs/account/teams/ai-code-tracking-api) · [Organization Admin API](https://cursor.com/docs/account/organizations/organization-admin-api)
- Claude Code: [Analytics dashboards](https://code.claude.com/docs/en/analytics) · [Analytics Admin API](https://platform.claude.com/docs/en/manage-claude/claude-code-analytics-api) · [OpenTelemetry monitoring](https://code.claude.com/docs/en/monitoring-usage)
- Frameworks worth naming in an interview: **DORA** (delivery performance), **SPACE** (multi-dimensional productivity), **DX Core 4** (unifies DORA/SPACE/DevEx into speed, effectiveness, quality, business impact).
