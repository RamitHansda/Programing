# Skill: Incident triage (first draft)

Use when on-call needs a fast structured draft from symptoms.

## Steps

1. Capture: start time, detector (alert/customer), severity, customer impact hypothesis.
2. Timeline: known events only — no speculation unmarked as speculation.
3. Blast radius: which Zero Touch path? human-touch forced? data incorrect or delayed?
4. Suspect changes: recent deploys/flags/config; link PRs.
5. Immediate actions: mitigate first (rollback/flag/rate-limit), then diagnose.
6. Comms: one paragraph for stakeholders; one for engineering.
7. Follow-ups: empty checklist for SEV items — do not invent owners.

## Never

- Declare root cause without evidence
- Paste secrets or raw PII into the draft
- Recommend "restart all pods" as the first idea without checking recent change
