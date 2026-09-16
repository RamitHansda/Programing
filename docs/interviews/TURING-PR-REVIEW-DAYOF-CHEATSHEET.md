# Turing PR Review — Day-of Cheat Sheet

**Round:** PR Review & Code Comprehension · 60 min (10 intro + 50 exercise) · Nate / Turing

## 90-sec open
Founding Eng/Lead @ Skydo: payments+settlement (10K+/day), idempotency/recon/locks/async. Agentic copilot with read-only tools + HITL. GS risk compute at scale. I review for correctness under failure, authz, and tests that protect users — collaborative tone.

## Clock (50 min)
0–3 intent/blast radius · 3–8 skim · 8–25 deep pass · 25–35 prioritize comments · 35–50 discuss

**Say:** “I’ll prioritize blockers (security/correctness), then tests/design, nits last.”

## Scan order
1. Intent matches diff?
2. **Correctness** — nulls, edges, error paths, transactions
3. **Security** — authz/IDOR, client-trusted flags, injection, PII/secrets
4. **Concurrency** — check-then-act, retries, idempotency, webhook replay
5. **API/design** — breaking changes, coupling, N+1, unbounded input
6. **Reliability** — timeouts, metrics, rollout
7. **Tests** — missing negative/replay/concurrency cases
8. Nits

## Comment template
`[Blocker|Major|Minor|Nit] Problem → risk → fix → test`

## Classic bugs to hunt
- Client flag bypasses auth (`isAdmin` / `isInternal`)
- IDOR on resource/wallet/order IDs
- `==` on strings / wrong equals
- Debit vs status update order (crash = inconsistency)
- Balance check without atomic conditional update
- `.get()` on empty Optional
- Webhook with no signature + no idempotency
- Breaking API / limit=1000 defaults
- Tests that only cover happy path

## Discussion one-liners
- **Refactor:** behavior-preserving + characterization tests; prefer separate PR
- **API:** additive, authz at boundary, don’t leak persistence models
- **Tests:** happy + authz deny + replay/concurrency + side-effect failure
- **Reliability:** retry, die-after-side-effect, who can call, what is logged

## Behavioral (30–45s each)
**Incident:** stop bleed → quantify → reconcile → permanent fix (state machine/idempotency/alerts) → postmortem with owners  
**Disagree:** race scenario + scoped lock; ego < incident; thank when wrong  
**Mentor:** teach one pattern/thread; nits light; promote repeated feedback to lint/docs; AI PRs still need authz/money/tests scrutiny

## Ask them
1. Production-bound service assumptions?  
2. Written comments vs verbal first?  
3. What does a strong review look like on your team?

## Avoid
Style-only reviews · vague “cleaner” · silent full rewrite · ignoring authz while debating names · “exactly-once” handwave
