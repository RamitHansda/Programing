# Turing — PR Review & Code Comprehension Interview Prep

**From:** Nate (Turing) — “What to Expect: PR Review and Code Comprehension Interview”  
**Candidate:** Ramit Hansda  
**Format:** 60 minutes total (≈10 min intro/Q&A + ≈50 min exercise)  
**Exercise:** Review a PR (GitHub-style or shared IDE/doc) and discuss findings

---

## 0. What this round is actually testing

They are not asking you to rewrite the PR or prove LeetCode fluency. They are testing whether you can operate like a **senior/staff reviewer** under time pressure:

| Dimension (from Nate) | What “strong” looks like |
|---|---|
| **Code quality** | Find real bugs and design flaws; prioritize by severity; propose concrete fixes |
| **Testing strategy** | Spot missing / weak tests; suggest tests that would have caught the bugs |
| **Design & architecture** | Reason about API contracts, coupling, failure modes, scalability |
| **Communication** | Clear, kind, specific feedback; reasoned tradeoffs; collaborative tone |
| **Behavioral (likely)** | Incident ownership, disagreeing with reviewers, mentoring |

**Bar framing for you:** You have led payments, reconciliation, distributed locks, and AI guardrails. In review mode, show the same instincts: **correctness under failure**, **security/authz**, **idempotency**, **observability**, **tests that protect money/data**, and **coaching tone**.

---

## 0.5 Research: questions / prompts reported so far

**Honest finding:** There is **no public dump of the exact PR** used in Nate’s “PR Review and Code Comprehension” round. Candidates do not leak the live PR the way they leak LeetCode prompts. What *is* documented:

### A. Confirmed from Nate’s email (this exact round)

Discussion topics they say may come up:
- Refactoring
- API design
- Testing strategy
- Readability
- Reliability / security

Behavioral themes they flag:
- Incident ownership
- Disagreeing with reviewers
- Mentoring

### B. Turing vetting MCQ (code-review style) — widely leaked

From `vetting-engine.turing.com` Python MCQs, the recurring item:

> Given `found_it(nums, looking_for)` that does `for i, x in enumerate(nums)` and returns `True` if found — **What is/are the best code review comment(s)?** (select all that apply)
>
> Options typically include:
> - Unused variable `i` adds technical debt
> - Need text comments because readability is poor
> - Runtime can be improved with a better search algorithm
> - Cannot unit-test with PyTest

**What they’re testing:** judgment about which comments are *valid* vs noise (unused vars / algorithm vs “must add comments” / “untestable”).

### C. Reported Turing live / tech-interview questions (Glassdoor, LinkedIn, Reddit)

These are from Turing vetting / stack interviews — **not always the PR-review round**, but they show what Turing interviewers actually ask:

**GitHub / PR workflow**
- How do you create a PR from branch creation to merge?
- Someone pushed directly to `master` and it’s already merged — what do you do?

**Code quality / review behavior**
- How do you approach code reviews and ensure code quality?
- What are your strategies for ensuring code quality and maintainability?
- How do you handle technical debt?
- How do you balance shipping fast vs code quality?
- How do you mentor junior engineers? / Tell me about mentoring

**Security / reliability (common follow-ups when reviewing code)**
- Your JS app is flagged for security vulnerabilities — what measures would you take?
- How do you prevent security vulnerabilities in a JS app?
- Authentication and session management after logout?
- Web security / JWT / APIs (reported in Turing Soft Dev Glassdoor)

**Stack / comprehension adjacent**
- Python internals: decorators, generators, threading, deep vs shallow copy, pickle
- TypeScript: debug bad type inference; write a user-profile interface
- Docker: containerize Node for multiple envs; image versioning; GitHub Actions + Docker CI
- DSA coding still appears in other Turing rounds (arrays/strings, max subarray, reverse linked list, etc.)

### D. Seeded-bug patterns from public “code review interview” writeups

These are **not Turing-branded leaks**, but they match Nate’s format (hand you a PR, discuss bugs/design/tests/security). Highest-signal seeded issues candidates are expected to catch:

| Pattern | Example |
|---|---|
| AuthZ / IDOR | Client `isInternal` flag bypasses ownership; wallet/order ID of another user |
| User enumeration | Different responses for unknown vs known email |
| Unthrottled side effects | Resend-email / notify endpoint as spam cannon |
| Broken rate limiter | Window never resets because rejected requests rewrite timestamp |
| Non-atomic money / state | Update status then debit; crash mid-way |
| Race / TOCTOU | Check balance then reduce without conditional update |
| Deploy-order hazard | Migration drops column while old instances still read it |
| Missing / weak tests | Only happy path; no authz/replay/concurrency tests |
| N+1 / unbounded input | Loop queries; `limit=1000` default |
| Nit bait | Naming / `console.log` — strong candidates deprioritize these |

### E. What this means for you

Prepare for: **one seeded PR** + discussion of the topics in §A + 1–2 behavioral from Nate’s list. Do **not** expect a memorized LeetCode ID. Expect to be scored on **severity ranking, fix quality, test suggestions, and tone** — not bug count.

---

## 1. Timebox for the 50-minute exercise

Use this clock. Interviewers notice structured pacing.

| Minutes | What you do |
|---|---|
| **0–3** | Clarify context: what does the PR claim to do? Which service? Who is the user? What must not break? |
| **3–8** | Skim PR description, commits, file list, tests. Form a mental model. |
| **8–25** | Deep pass: correctness → security → concurrency → API/design → tests → maintainability |
| **25–35** | Organize findings by severity; write/speak top comments as you would on GitHub |
| **35–50** | Discuss with interviewer: walk severity order; propose fixes; answer design/test follow-ups |

**Opening line (say this):**

> “I’ll treat this like a real PR: first I’ll understand intent and blast radius, then I’ll look for correctness and security blockers, then tests and design, and I’ll prioritize comments so we can discuss the highest-risk items first.”

---

## 2. Review framework (memorize this order)

When you open any PR, walk these layers **in order**. Stop and comment when something fails a layer.

### Layer 1 — Intent & blast radius
- Does the PR description match the diff?
- Is the change scoped (one concern) or a kitchen-sink refactor?
- What is the production blast radius: money, auth, PII, data migration, public API?

### Layer 2 — Correctness bugs
- Happy path vs edge cases (null, empty, duplicates, large inputs, timezones)
- Error handling: swallowed exceptions, wrong status codes, partial failure
- Off-by-one, wrong comparison (`==` vs `.equals`), incorrect boolean logic
- Race conditions, TOCTOU, check-then-act
- Idempotency under retries / webhook replay
- Transaction boundaries (debit then fail to update status = inconsistency)

### Layer 3 — Security & privacy
- AuthN vs AuthZ confusion (logged-in ≠ allowed)
- IDOR / broken object-level authorization (user A acting on user B’s resource)
- Trusting client flags (`isInternal`, `isAdmin`, role from request body)
- Injection (SQL/NoSQL/command), XSS, SSRF, path traversal
- Secrets in logs/code; PII in clear logs
- Missing rate limits on sensitive endpoints

### Layer 4 — API & design
- Backward-compatible contracts? Breaking field renames?
- Coupling: domain logic in controller? leaking persistence models?
- Naming, single responsibility, hidden side effects
- Pagination, filtering, consistency of error shape
- Scalability: N+1 queries, unbounded lists, sync calls on hot path

### Layer 5 — Reliability & ops
- Timeouts, retries, backoff, circuit breakers
- Metrics/logs/traces for the new path
- Feature flags / rollout / kill switch for risky changes
- Migration safety (expand/contract, dual-write, backfill)

### Layer 6 — Tests
- Are there tests at all?
- Do tests assert behavior or just “doesn’t throw”?
- Missing negative cases: auth failure, insufficient funds, concurrency, replay
- Flaky patterns: sleeps, order-dependent tests, shared mutable state
- Suggest **the one test that would have caught the critical bug**

### Layer 7 — Maintainability & nitpicks (last)
- Style, naming, dead code — only after blockers
- Prefer “nit:” / “optional:” labels; don’t block on these

---

## 3. How to prioritize comments (speak this structure)

Use a severity rubric out loud. Interviewers love this.

| Severity | Examples | Merge decision |
|---|---|---|
| **Blocker** | Security hole, data corruption, money double-spend, broken authz, silent data loss | Request changes |
| **Major** | Missing transaction, race under load, weak error handling that misleads callers, no tests for critical path | Request changes or approve with required follow-up |
| **Minor** | API inconsistency, missing metrics, unclear naming in hot path | Approve with suggestions |
| **Nit** | Style, import order, comment wording | Optional |

**Spoken template for each finding:**

> “**Severity: Blocker.** In `checkout`, authorization trusts `isInternal` from the client, so any caller can bypass ownership checks. **Risk:** IDOR / unauthorized checkout. **Fix:** Derive trust from server-side auth context / service identity, never from a request flag. **Test I’d add:** unauthenticated and cross-user basket access must return 403.”

Tone rules:
- Critique the **code**, not the author
- Prefer questions when intent is unclear: “Was the intent that walletId can be supplied by the client?”
- Offer a concrete alternative, not just “this is bad”
- Acknowledge what’s good (1 sentence) before diving into blockers

---

## 4. Practice PR #1 — Checkout / wallet (classic traps)

This style of PR is common in code-review interviews (payments-adjacent, authz bugs, races). Practice finding issues **before** reading the answer key.

### Diff (Java / Spring-ish)

```java
@PostMapping("/checkout/{basketId}")
public String checkout(
    @PathVariable String basketId,
    @RequestParam boolean isInternal,
    @RequestParam(required = false) String walletId,
    Authentication auth) {

  boolean canAccess = true;
  if (!isInternal || auth == null) {
    List<Basket> baskets = basketRepository.findByUsername(auth.getPrincipal().getName());
    boolean ownsBasket = false;
    for (Basket b : baskets) {
      if (b.getId() == basketId) {  // (A)
        ownsBasket = true;
      }
    }
    if (!ownsBasket) canAccess = false;
  }

  if (!canAccess) return "NOT-ALLOWED";

  if (walletId == null) {
    walletId = basketRepository.findById(basketId).get().getUserWallet();  // (B)
  }

  Wallet wallet = walletService.getWallet(walletId);
  Optional.ofNullable(wallet).orElseThrow();

  Basket basket = basketRepository.findById(basketId).get();  // (C)
  if (wallet.getBalance() > basket.getTotal()) {              // (D)
    basketRepository.changeStatus(basketId, "SEND_FOR_DELIVERY");
    walletService.reduceBalance(walletId, basket.getTotal()); // (E)
  } else {
    return "INSUFFICIENT_FUNDS";
  }
  return "OK";
}
```

### Answer key (prioritized)

**Blockers**
1. **AuthZ bypass via `isInternal`** — client-controlled flag skips ownership. Fix: remove flag; use roles/service auth.
2. **IDOR on `walletId`** — caller can pass another user’s wallet. Fix: wallet must belong to authenticated user / basket owner; ignore client walletId or validate ownership.
3. **Reference equality `(A)`** — `==` on Strings; ownership check can falsely fail (or worse depending on interning). Use `.equals`.
4. **Non-atomic money movement `(E)`** — status updated before debit; crash → free delivery. Need single transaction / outbox + idempotency key; fail closed.
5. **Race / TOCTOU** — balance check then reduce without lock/conditional update → overdraft under concurrency.

**Major**
6. **NPE when `auth == null` and `isInternal` false** — condition `!isInternal || auth == null` still enters branch and NPE’s on `auth.getPrincipal()`.
7. **`.get()` without empty check `(B)(C)`** — NoSuchElementException instead of 404.
8. **Strict `>` vs `>=` `(D)`** — exact balance fails checkout.
9. **Stringly-typed API** — `"OK"` / `"NOT-ALLOWED"` instead of proper HTTP status + error body; clients can’t distinguish reliably.
10. **No idempotency** — retry double-charges.

**Tests that should exist**
- Cross-user basket → 403
- `isInternal=true` from external client still denied
- Wallet belonging to another user → 403
- Exact balance succeeds
- Concurrent checkouts don’t overdraft
- Retry with same idempotency key charges once
- Missing basket → 404

**Design comments**
- Move domain logic out of controller into `CheckoutService`
- Return domain result types / Problem Details, not magic strings
- Add metrics: checkout_success, checkout_insufficient_funds, checkout_authz_denied

**Good review opener:**

> “Thanks for the checkout endpoint — clear happy path. I have a few blockers around authorization and the debit/status ordering before we merge.”

---

## 5. Practice PR #2 — Async job / webhook handler

```python
def handle_payment_webhook(payload: dict):
    payment_id = payload["payment_id"]
    status = payload["status"]

    payment = db.query(Payment).filter_by(id=payment_id).first()
    payment.status = status
    db.commit()

    if status == "SUCCEEDED":
        notify_user(payment.user_id, "Payment succeeded")
        fulfill_order(payment.order_id)

    return {"ok": True}
```

### Issues to catch
| Sev | Issue |
|---|---|
| Blocker | No signature verification → forged webhooks |
| Blocker | No idempotency → duplicate fulfill on retries |
| Major | Null payment → AttributeError |
| Major | notify/fulfill after commit without outbox → lost side effects or double side effects on retry |
| Major | Trusting partner status blindly without state machine (SUCCEEDED → FAILED?) |
| Minor | No structured logging / metrics / dead-letter |
| Tests | Replay webhook twice; invalid signature; unknown payment_id; illegal transition |

**Staff-level spoken fix:**

> “I’d verify the signature first, load payment under a row lock or conditional update keyed by `(payment_id, event_id)`, enforce allowed transitions, persist an outbox event for notify/fulfill, and make fulfill idempotent on `order_id`.”

---

## 6. Practice PR #3 — “Simple” API redesign

```ts
// OLD: GET /users/:id/orders?page=1&size=20
// NEW: GET /orders?userId=&cursor=&limit=

export async function listOrders(req, res) {
  const { userId, cursor, limit = 1000 } = req.query;
  const orders = await db.orders.findMany({
    where: { userId },
    take: Number(limit),
    ...(cursor ? { skip: 1, cursor: { id: cursor } } : {}),
  });
  res.json(orders);
}
```

### Issues
- Breaking API without versioning / migration plan
- Missing authz: any caller lists any `userId`
- Default `limit=1000` unbounded risk; no max clamp
- Returns persistence model (overexposes fields)
- Cursor pagination incomplete (no `nextCursor`)
- No tests for authz or pagination stability

---

## 7. Discussion topics — model answers

### Refactoring
> “I’d ask whether the refactor is required for this change. If yes, keep behavior-preserving refactors in a separate commit/PR when possible. If we must mix, I want characterization tests first so we don’t ship a silent behavior change.”

### API design
> “Public APIs need compatibility, explicit error contracts, and authz at the boundary. Internal DTOs shouldn’t leak. Prefer additive changes; use expand/contract for renames.”

### Testing strategy
> “I optimize for tests that fail when user-visible or money-visible behavior breaks. For this PR I’d require: one happy path, one authz denial, one concurrency or replay case, and one failure-injection around the external side effect.”

### Readability
> “If I need a paragraph to explain a function, that’s a signal to rename, split, or add a short intent comment at the boundary — not novel-length comments on every line.”

### Reliability / security
> “My default questions: what happens on retry? what happens if the process dies after side effect? who is allowed to call this? what is logged?”

---

## 8. Behavioral — spoken answers (your stories)

### Q1: Tell me about incident ownership

**Story:** payments / settlement mismatch or partner timeout after debit (Skydo).

> “At Skydo I owned payments end-to-end. When we saw settlement mismatches after a partner timeout, I didn’t wait for a perfect root cause to start mitigation. I (1) stopped the bleeding — paused the affected payout path / enabled fail-closed behavior, (2) quantified blast radius from ledger vs partner files, (3) reconciled impacted txns with an exception queue, (4) shipped the permanent fix: clearer state machine + idempotent retry + alerting on aged pending states, and (5) wrote the postmortem with action items owned by name and date. Ownership for me means you stay with the customer impact until the system is safer than before the incident — not just until the page is green.”

**Metrics to mention if asked:** 10K+ txns/day; reconciliation as source of truth; idempotency + locks on critical sections.

### Q2: Disagreeing with a reviewer / author

> “I separate blocking issues from preferences. Example: a teammate wanted to skip distributed locking on a settlement path for latency. I disagreed on the review because duplicate payouts were worse than a few extra milliseconds. I explained the failure mode with a concrete race scenario, proposed a scoped lock + metrics, and offered to pair on the implementation. We shipped with the lock. When I *am* the one being challenged, I ask what failure mode I’m missing; if they’re right, I thank them and change the code — ego is cheaper than an incident.”

### Q3: Mentoring through code review

> “I use reviews to teach patterns, not just gate merges. With juniors I’ll leave one ‘teaching’ thread: for example, why check-then-act fails under concurrency, and show the conditional update. I keep nits light, celebrate good structure, and move repeated feedback into team docs or lint rules so we don’t re-litigate style. At Skydo I also set norms for AI-assisted PRs — reviewers must still check authz, money paths, and tests — so speed doesn’t bypass judgment.”

### Q4: How do you approach code review day-to-day?

> “Risk-based. I start with security, data integrity, and API contracts; then concurrency and failure handling; then tests; then design clarity. Style is automated. I aim for first review within a day, prefer small PRs, and I’m explicit about blocker vs suggestion so authors aren’t guessing.”

---

## 9. 90-second intro (use in the first 10 minutes)

> “I’m Ramit — 10+ years in distributed systems, mostly payments and financial platforms. Most recently as founding engineer / Eng Lead at Skydo I owned payments and settlement — idempotency, reconciliation, distributed locking, async workflows — at about 10K+ international transactions a day. I also built an agentic support copilot with strong guardrails around tools and auditability. Before that at Goldman I worked on large-scale market risk systems. I care a lot about correctness under failure and about reviews that are both rigorous and kind — looking forward to walking through the PR with that lens.”

### Questions you can ask Nate / the interviewer
1. “Is this PR meant to be reviewed as production-bound for a payments/API service, or more as a general backend exercise?”
2. “Any product constraints or SLAs I should assume?”
3. “Do you want GitHub-style written comments, or a verbal walkthrough first?”
4. “What does a strong review look like on your team — depth vs speed tradeoff?”

---

## 10. Day-of checklist

**Night before**
- [ ] Rehearse the 7-layer framework out loud once
- [ ] Re-do Practice PR #1 from memory; write 5 comments with severity
- [ ] Rehearse 3 behavioral answers (incident, disagreement, mentoring)
- [ ] Rehearse 90-sec intro

**During the round**
- [ ] Clarify intent & blast radius first
- [ ] Speak severity labels
- [ ] Always pair bug → fix → test
- [ ] Keep tone collaborative
- [ ] Timebox: leave 15 min for discussion

**Avoid**
- Only nits / style debates
- Rewriting the entire PR silently
- Vague “this could be cleaner”
- Claiming “exactly-once” without at-least-once + idempotent business effect
- Blocking on preferences when a real security bug is unmentioned

---

## 11. Quick reference card

```
ORDER: Intent → Correctness → Security → Concurrency → API/Design → Reliability → Tests → Nits

COMMENT: [Severity] What’s wrong → Why it matters → Concrete fix → Test to add

BLOCKERS: authz/IDOR, injection, money/data races, non-atomic side effects, secret leakage
MAJORS: missing txns/idempotency, weak errors, no critical tests, breaking APIs
```

**One-liner if stuck:**

> “I’m going to trace one request through authz, state change, side effects, and retries — that’s where the expensive bugs usually hide.”
