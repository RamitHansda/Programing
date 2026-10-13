# Sample CLAUDE.md critique (Part 4 backup)

If DMG asks you to critique their Sample CLAUDE.md instead of sharing yours, use this rubric on screen:

## What good looks like

1. **Short** — fits in a quick scroll; agents ignore novels
2. **Never-rules with reasons** — tied to failure modes, not vibes
3. **Definition of done** — tests + observability + rollback
4. **Stop conditions** — when to ask a human
5. **Domain invariants** — for Aurora: idempotency, cascade budget, freezes, explainability

## Common sample-file failure modes

| Smell | Fix |
| --- | --- |
| "Be helpful and write clean code" | Delete; zero behavioral constraint |
| 2,000+ words of architecture essay | Move to `docs/`; keep contract thin |
| No security/PII rule | Add never-rule #1 |
| No link to how success is measured | Point to `METRICS.md` / SLOs |
| Encourages large autonomous refactors | Ban unbounded rewrites on critical path |

## Spoken closer

"I'd keep their sample's stack section, cut the essay, and replace the middle with three never-rules from real Zero Touch incidents — then add a ship-check command so the contract is used, not framed."
