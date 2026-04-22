# Grab — Lead Engineer Interview Questions

**Date:** April 21, 2026
**Role:** Lead Engineer

---

## Coding / DSA Round

### Q1. Product of Array Except Self

**Problem:**
Given an integer array, return a new array where each element at index `i` is the product of all elements in the input array **except** the element at index `i`.

**Constraints to note:**
- Must not use division.
- Expected to solve in O(n) time and O(1) extra space (excluding the output array).

**Example:**
```
Input:  [1, 2, 3, 4]
Output: [24, 12, 8, 6]
```

**Key concepts:** Prefix products, suffix products, two-pass traversal.

---

### Q2. Word Search (LeetCode 79)

**Reference:** [https://leetcode.com/problems/word-search/description/](https://leetcode.com/problems/word-search/description/)

**Problem:**
Given an `m x n` grid of characters and a string `word`, return `true` if the word exists in the grid. The word can be constructed from letters of sequentially adjacent cells (horizontally or vertically neighboring). The same cell may not be used more than once.

**Example:**
```
Board:
[['A','B','C','E'],
 ['S','F','C','S'],
 ['A','D','E','E']]

Word: "ABCCED" → true
Word: "SEE"    → true
Word: "ABCB"   → false
```

**Key concepts:** Backtracking, DFS on a 2D grid, visited/unvisited state management.

---

### Q3. Collision Point in a Hash Function

**Problem:**
Given a hash function and a set of keys, find two distinct keys that map to the same hash bucket — i.e., identify a **hash collision**.

**Variants asked:**
- Detect if any collision exists in the current set.
- Find the first pair of keys that collide.
- Design a hash function that minimises collisions.

**Key concepts:** Birthday paradox, pigeonhole principle, separate chaining vs open addressing, load factor, universal hashing.

---

---

## System Design Round

### Design an Email Scheduling & Delivery System

**Problem Statement:**
Design a scalable system that allows users to schedule emails to be sent at a specific time (or on a recurring schedule) and reliably delivers them to recipients.

**Scope / Areas expected to cover:**

| Area | What they likely probed |
|---|---|
| API Design | CRUD for schedules, recipient lists, templates |
| Scheduling | How to trigger jobs close to their scheduled time (cron, delay queues, time-wheel) |
| Fan-out | Handling large recipient lists (e.g. 10M recipients per schedule) |
| Delivery | Integration with SMTP providers (SES, SendGrid); retry logic; idempotency |
| Reliability | At-least-once vs exactly-once semantics; deduplication |
| Scale | Throughput estimates, Kafka/queue sizing, worker pool design |
| Failure handling | Dead-letter queues, provider failover, partial failures |
| Observability | Delivery status tracking (sent / bounced / opened), audit logs |
| Edge cases | DST / timezone handling, recurring rules (RRULE), mutable schedules mid-run |

**Key design decisions to discuss:**
- Dispatch SLA: how close to the scheduled time must the email be sent (P99 ≤ 60s is a reasonable target)
- Recurrence DSL: RFC 5545 RRULE vs cron (RRULE handles DST and complex rules like "last business day of month")
- Audience representation: store audience as a query definition, not a materialised list, to support up to 10M recipients
- Delivery semantics: at-least-once + per-recipient idempotency key (true exactly-once over SMTP is not achievable)
- Edit semantics: edits apply to the **next** run; in-flight runs are immutable

**High-level component flow:**
```
Client → Schedule API → DB (schedules + templates)
                           ↓
                     Scheduler Poller (cron / delay queue)
                           ↓
                     Fan-out Worker → Kafka: delivery.requested
                           ↓
                     Delivery Workers → SMTP Provider (SES / SendGrid)
                           ↓
                     Webhook Handler ← Provider callbacks (bounce, open, click)
                           ↓
                     Delivery Status DB → Analytics / Dashboard
```

**Reference HLD (detailed design already documented):**
[`docs/EMAIL_SCHEDULER_DELIVERY_HLD_STAFF_ENG.md`](../EMAIL_SCHEDULER_DELIVERY_HLD_STAFF_ENG.md)

---

## Notes

- Coding and system design were separate rounds.
- Q1 and Q2 in the coding round are standard LeetCode-style DSA problems.
- Q3 leans more toward data structure internals — testing understanding of hash table design, not just coding.
- The system design question closely matches a Staff/Lead-level HLD — capacity estimation and failure handling were expected.
