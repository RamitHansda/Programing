# /ship-check

Run a pre-merge Zero Touch checklist against the current diff or PR description.

Answer yes/no/n/a with evidence:

- [ ] Intent clear in PR body
- [ ] Invariants listed
- [ ] Failure path handled (no silent success)
- [ ] Tests cover at least one invariant
- [ ] Correlation / disruption id present in new log lines
- [ ] Metric or alert considered
- [ ] Feature flag or rollback path
- [ ] Migration expand/contract safe (if schema)
- [ ] Cascade/retry budget present (if resolver)

End with **SHIP**, **SHIP WITH FOLLOW-UPS**, or **DO NOT SHIP** and why.
