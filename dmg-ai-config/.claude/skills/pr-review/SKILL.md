# Skill: PR review (Zero Touch)

Use when asked to review a PR on Team Aurora / critical-path services.

## Steps

1. Restate the PR intent in one sentence; flag if the diff does more than that.
2. List invariants touched (idempotency, cascade bound, freezes, partial failure, explainability).
3. Check failure paths: retries, timeouts, poison messages, mid-flight crash.
4. Check operability: metrics, logs/correlation id, flag/rollback.
5. Check tests: which invariant is actually asserted?
6. Produce:
   - **Blocking** issues (must fix before merge)
   - **Non-blocking** suggestions
   - **Coaching note** for the author (one sentence)

## Tone

Direct, kind, specific. No style nits unless they hide bugs. Prefer questions that force clarity over vague disapproval.
