# Team Aurora — CLAUDE.md

Short contract for AI coding agents. Prefer smallest correct change. Author owns every line that merges.

## Mission

You assist engineers on **Zero Touch critical-path** services (including ReschedulingEngine). Failures here create customer-visible breakage or force human touch. Optimize for correctness, operability, and reversible change — not cleverness.

## Stack (adjust per repo)

- Languages: Kotlin/Java or Go for services; TypeScript for internal tools
- Data: PostgreSQL + Redis; event bus (Kafka/SQS) for disruptions
- Deploy: Kubernetes; feature flags required for behavior changes on critical path

## Definition of done

A change is not done when it "works on my machine." Done means:

1. Happy path + at least one failure path covered by tests
2. Structured logs with a correlation / disruption id
3. Metrics or alerts touched if behavior on the critical path changed
4. Rollback or flag-off path described in the PR body

## Never (production-learned constraints)

- **Never** commit secrets, tokens, or customer PII into prompts, fixtures, or logs. Redact.
- **Never** widen a transactional boundary "for simplicity" on schedule writes — call out the race.
- **Never** introduce unbounded cascade / retry loops in rescheduling logic. Every resolver needs a budget (depth, time, or mutation count) and a safe stop.
- **Never** break expand/contract migrations: old readers/writers must survive one release.
- **Never** silently swallow errors on the Zero Touch path (return success / ack on failure).
- **Never** delete or rewrite large modules in one PR. Split by invariant.

## When touching ReschedulingEngine-like code

Before editing, state which invariants you are preserving:

1. Idempotent processing of the same disruption
2. Bounded cascade
3. Respect for freezes / overrides
4. Recoverable partial failure
5. Explainable mutations (reason codes)

If the task would violate an invariant, **stop and ask** — do not "best-effort" it.

## How to work

1. Read relevant files before proposing edits; cite paths.
2. Prefer tests that lock an invariant over tests that lock implementation detail.
3. Keep PRs small enough to review in one sitting.
4. If unsure between two designs, present both with blast radius — do not pick silently on critical path.

## Out of scope for the agent

- Merging to main
- Changing org-wide policy
- Declaring an incident mitigated without human confirmation
