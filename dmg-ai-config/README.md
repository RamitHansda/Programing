# DMG / Team Aurora — AI Configuration

Engineering Manager AI operating kit for a **Zero Touch critical-path** service (ReschedulingEngine and peers).

This is the configuration I would put on Team Aurora on day one: short contracts agents actually follow, never-rules tied to production risk, and skills for the two workflows that burn the most senior time — **PR review** and **incident triage**.

## Why this shape

| Principle | Practice here |
| --- | --- |
| Context is expensive | Root `CLAUDE.md` stays short; details live in skills/rules loaded on demand |
| Constraints &gt; essays | Dated **never** rules beat generic "be careful" |
| Author owns the code | AI is assistive; review and merge judgment stay human on Zero Touch paths |
| Measure outcomes | See `METRICS.md` — adoption is input; cycle time + change-failure are outcomes |

## Layout

```
CLAUDE.md                 # Always-on contract for Claude Code / compatible agents
AGENTS.md                 # Cross-tool agent behavior (Cursor, Copilot, etc.)
METRICS.md                # What an EM should instrument first
.cursor/rules/            # Cursor-enforced repo taste
.claude/skills/           # On-demand workflows
.claude/commands/         # Slash-style prompts for common EM/eng loops
hooks/                    # Optional guardrail hooks (document intent; wire per tool)
```

## Quick start for Team Aurora

1. Copy this repo (or subtree) into each active service repo, or keep as a org template.
2. Adjust stack names in `CLAUDE.md` to match the real service.
3. Require `CLAUDE.md` + `.cursor/rules` presence as the **enablement bar** (not a usage quota).
4. Wire the metrics in `METRICS.md` to Git + your APM within two weeks.

## Non-goals

- Mandating "% of code written by AI"
- Auto-merging to Zero Touch paths
- Replacing design review or on-call judgment
