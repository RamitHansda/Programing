# AI leverage metrics — what to instrument first

Treat AI coding assistants as a **product rollout**, not a license purchase.

Success statement:

> Within a quarter, Team Aurora ships the same or more customer-visible change with lower PR cycle time, without raising change-failure or Zero Touch human-touch rate.

## Scorecard (week-2 dashboard)

| # | Metric | Type | Source |
| --- | --- | --- | --- |
| 1 | Weekly active AI users / eligible eng | Input | Cursor / Claude analytics |
| 2 | % active repos with `CLAUDE.md` + rules | Input | CI repo scan |
| 3 | Agent/composer share vs tab-only | Depth | Cursor by-user analytics |
| 4 | PR cycle time (open → merge) | Outcome | GitHub |
| 5 | Time to first review | Outcome | GitHub |
| 6 | Revert / change-failure rate | Guardrail | Git + deploy |
| 7 | Rework: lines changed again within 21 days | Guardrail | Git |
| 8 | Zero Touch human-touch rate | Guardrail | Product / ops metric |

## Rules for reading the scorecard

- Never optimize (1)–(3) without watching (6)–(8).
- If cycle time drops and reverts rise → pause breadth rollout; fix enablement (context files, review norms).
- "% code written by AI" is optional color, not a goal. Attribution is noisy; do not manage people to it.

## 30 / 60 / 90

- **30 days:** context files in every active repo; baseline DORA/flow metrics captured; champions named.
- **60 days:** depth metrics moving (skills/commands in use); one internal MCP or tool integration if it removes real toil.
- **90 days:** keep, adjust, or pull back — written decision using this scorecard.
