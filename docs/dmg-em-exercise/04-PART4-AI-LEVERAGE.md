# Part 4 — AI & Leverage (~7 min)

**Split:** Walk your AI config on screen (~3 min) → multiply Team Aurora's velocity + what you'll instrument first (~4 min)

**Screen-share:** the `dmg-ai-config` repo (this is also what you paste into the submission form).

---

## Opening

"Moving to the final part. I'll walk the AI configuration I'd actually put on Team Aurora, then how I'd use it to multiply velocity — and what I'd measure so we know it's real."

---

## Config walk (~3 min) — narrate while scrolling

Open files in this order:

### 1. `CLAUDE.md` (root contract)

"This is the always-on contract. Short on purpose — every line costs tokens every turn. It states: stack assumptions, Zero Touch invariants, what the agent must never do (secrets, silent schema breaks, unbounded cascade changes), and the definition of done: tests + observability for anything on the critical path."

### 2. `AGENTS.md` (how agents behave)

"Role rules: prefer smallest diff, cite files before editing, stop and ask when an invariant is touched. Author owns the code — AI is not a review exemption."

### 3. `.cursor/rules/` (repo-enforced taste)

"Cursor rules for PR size, logging/correlation IDs, and migration expand/contract. These catch the mistakes I saw when teams adopt AI without a standard."

### 4. Skills / commands

"Two skills I'd load first for Aurora: **pr-review** (invariant checklist) and **incident-triage** (signal → hypothesis → blast radius). Commands for design-review and ship-check so juniors don't invent process each time."

### 5. Contrast to a weak Sample CLAUDE.md (if you show theirs)

Quick critique pattern:

- Too long / essay-like → agents ignore it
- No **never** rules tied to incidents → decorative
- No link to tests/metrics → speed without a guardrail
- Generic "be helpful" → wasted context

"I'd delete half of a sample file and replace it with three dated never-rules from real outages."

---

## Multiply velocity (~4 min)

### How I'd use AI on Team Aurora (concrete)

1. **Design → ADR draft** — engineer pastes constraints; agent produces options + risks; human decides. Cuts blank-page time, not judgment.
2. **Invariant tests first** — for ReschedulingEngine changes, agent scaffolds property/simulation tests for cascade bounds *before* feature code.
3. **PR review assist** — agent runs the invariant checklist; human still owns approve/request-changes.
4. **Incident first draft** — timeline, suspect diffs, customer impact template from logs — on-call edits, doesn't start from zero.
5. **Context packing** — every active repo gets `CLAUDE.md` + rules; that's the enablement bar, not a usage quota.

### What I will *not* do

- Mandate "% of code written by AI"
- Let AI merge to Zero Touch paths without human review
- Celebrate autocomplete adoption as success

### What I'd instrument first (the scorecard)

Treat AI like a **product rollout**:

| Layer | First metrics |
| --- | --- |
| **Adoption (input)** | Weekly active engineers; repos with `CLAUDE.md` / rules present |
| **Depth (input)** | Agent/composer use vs tab-only; skill/command usage |
| **Outcome (lagging)** | PR cycle time (open→merge); time-to-first-review |
| **Guardrail (non-negotiable)** | Change-failure / revert rate; rework within 21 days; Zero Touch human-touch rate |

**First dashboard I'd build in week two:** cycle time + revert rate + human-touch rate, split by AI-assisted vs not (directional). If cycle time drops and reverts rise, we pull back enablement — we don't celebrate the tool.

### Closing line

> "At Skydo I drove Claude/Cursor/Windsurf adoption to about 90% with standards and guardrails — roughly 25% better PR cycle time with rework flat. On Aurora I'd do the same: make the config the contract, measure outcomes not vibes, and keep Zero Touch correctness as the hard stop."

---

## Optional 15s — LinkedIn / config repo reminder (if form fields come up later)

Don't waste Loom time on this unless asked — those go in the form:

- LinkedIn: https://linkedin.com/in/ramit-hansda
- AI config: your public `dmg-ai-config` GitHub URL
