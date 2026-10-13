# Part 3 — Team Health Check (~8 min)

**Material:** Team Health Snapshot (sprint board + service signals)  
**Job:** Diagnose → name root causes → prescribe **first-week** actions. Not a 90-day strategy TED talk.

---

## Opening

"Moving to part three. I'll treat this snapshot the way I treat a new team's first week: separate symptoms from causes, then pick a small set of actions that create signal fast."

---

## Forced diagnosis structure (use on screen)

### Minute 0–2 — Observe without prescribing

Narrate what you see in two columns:

| Delivery (board) | Service (signals) |
| --- | --- |
| Carry-over / WIP / blocked age | Error rate, latency, SLO burn |
| Who owns what (or doesn't) | On-call noise / pages |
| Spike of "almost done" | Customer/Zero Touch human-touch rate |
| Missing acceptance criteria | Deploy frequency / change fail |

**Say:** "I'm looking for congruence. Healthy teams have board motion *and* calm signals. Distressed teams usually have one green and one red — that mismatch is the tell."

### Minute 2–5 — Name 2–3 root causes (not 10 symptoms)

Common patterns on a Zero Touch pod (map whatever the snapshot shows onto 2–3 of these):

1. **Unowned reliability** — features ship; SLOs / runbooks / error budgets are optional
2. **WIP thrash** — too much started, nothing finished; context-switch tax
3. **Hidden dependency debt** — blocked on another team with no escalation path
4. **Bus factor / heroics** — one person knows ReschedulingEngine; board depends on them
5. **Metric theater** — velocity celebrated while Zero Touch human-touch rate climbs
6. **Review / quality collapse** — PRs too large, review rubber-stamped under schedule pressure

**Root-cause test:** if fixing it wouldn't change next week's outcomes, it's not a root cause — it's a complaint.

Phrase:

> "The board says X, the service says Y — the connecting root cause is Z."

### Minute 5–8 — First-week actions (prescription)

Prescribe **≤5 actions**, each with owner, artifact, and success signal.

#### Template first-week plan (adapt to snapshot)

| # | Action | Why this week | Done looks like |
| --- | --- | --- | --- |
| 1 | **Listen tour** — 30-min 1:1s with every IC + partner EM/PM | Reality &gt; dashboard | Written themes doc (1 page) |
| 2 | **Error-budget / Zero Touch standup** — 15 min daily on human-touch + top pages | Make reliability visible | Named owner for each top-3 alert |
| 3 | **WIP limit** — finish 2 in-flight items before starting new | Kill thrash | Board matches the limit |
| 4 | **Critical-path page** — ReschedulingEngine: SLO, runbook link, on-call, rollback | Operability baseline | Link in team README |
| 5 | **One decision** — protect ~20% capacity for the top reliability bet *or* explicitly defer with written risk | Stop silent tradeoffs | ADR / Slack decision record |

**What you will *not* do in week one:** reorg, rewrite the roadmap, introduce three new processes, or "motivate" people with speeches.

---

## Closing decision line (record this)

> "My week-one job is clarity and signal: who owns Zero Touch health, what we stop starting, and one reliability bet we will finish. Culture speeches come after the system can breathe."

---

## If the snapshot looks surprisingly healthy

Still prescribe:

- Confirm the metrics aren't lagging or gamed
- Pressure-test bus factor and on-call load
- Ask what would break if the senior engineer left for two weeks

Healthy snapshots still need a first-week plan — just a lighter one.

---

**Transition:** "Moving to the final part — AI and leverage."
