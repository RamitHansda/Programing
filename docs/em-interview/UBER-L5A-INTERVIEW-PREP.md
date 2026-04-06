# Uber L5A Senior Software Engineer — Interview Prep Guide
**Interview Date:** Saturday, April 11, 2026 | Uber Bengaluru Office

---

## Interview Day Flow (Your Format)

```
BPS (Business Phone Screen)
       ↓ [if shortlisted]
C1: DSA Round
       +
C2: Machine Coding (Functional Coding)
```

> **Critical fact:** BPS is eliminatory. C1 and C2 happen the same day if you pass BPS.

---

## STEP 1 — BPS (Business Phone Screen)

### What It Is
An initial technical discussion with engineering leadership. Based on real L5A experiences:
- ~60 minutes total
- First 15 minutes: **past work + backend concept grilling** (your distributed systems, payments background is your edge here)
- Remaining 45 minutes: **1 coding question** (LeetCode Medium level)
- Focus on: DSA correctness + clean code structure + naming conventions

### What They Look For in the Code
- Working, runnable solution
- Modular structure — clean class/method separation
- Good naming conventions (variables, methods, classes)
- Basic OOP awareness even in a coding screen

### Backend Concepts They May Ask (Tailored to Your Profile)
Since you have a payments/distributed systems background, expect:

**Distributed Systems**
- How do you ensure idempotency in payment systems? *(You built this at Skydo — talk about idempotency keys, deduplication)*
- Explain distributed locking — when and why you'd use it *(You implemented this at Skydo)*
- How does a distributed job scheduler work? *(You architected one at Skydo)*
- What is the difference between at-least-once vs exactly-once delivery?
- CAP theorem — what trade-offs did you make in your systems?

**Databases & Consistency**
- When would you choose PostgreSQL vs Redis vs Kafka for a queue?
- How do you handle distributed transactions (2PC, Saga pattern)?
- What is a write-ahead log? How does it relate to durability?

**Your Story Angle for BPS**
> "I designed and scaled a payments & settlement platform processing 10,000+ international transactions/day with strict idempotency, reconciliation, and failure recovery guarantees. Let me walk you through the architecture..."

---

## STEP 2 — C1: DSA Round

### Format (Based on Real L5A Interview Reports)
- 60 minutes: ~5 min intro + 50 min coding + 5 min Q&A
- 1–3 questions: typically 1 medium + 1 follow-up + possibly 1 more
- Must write **fully working code** with correct test case passing
- Communicate your approach before coding — explain brute force → optimize

### High-Probability Topic Areas at Uber

#### 1. Sliding Window + Deque (HIGH PRIORITY — actual Uber question)
**Problem:** Longest Continuous Subarray With Absolute Diff ≤ K
- Brute force: O(N³) or O(N²), then optimize to O(N) with two deques (max deque + min deque)
- LeetCode: https://leetcode.com/problems/longest-continuous-subarray-with-absolute-diff-less-than-or-equal-to-limit/

```java
public int longestSubarray(int[] nums, int limit) {
    Deque<Integer> maxDeque = new ArrayDeque<>(); // decreasing
    Deque<Integer> minDeque = new ArrayDeque<>(); // increasing
    int left = 0, result = 0;
    for (int right = 0; right < nums.length; right++) {
        while (!maxDeque.isEmpty() && nums[maxDeque.peekLast()] <= nums[right])
            maxDeque.pollLast();
        while (!minDeque.isEmpty() && nums[minDeque.peekLast()] >= nums[right])
            minDeque.pollLast();
        maxDeque.addLast(right);
        minDeque.addLast(right);
        while (nums[maxDeque.peekFirst()] - nums[minDeque.peekFirst()] > limit) {
            left++;
            if (maxDeque.peekFirst() < left) maxDeque.pollFirst();
            if (minDeque.peekFirst() < left) minDeque.pollFirst();
        }
        result = Math.max(result, right - left + 1);
    }
    return result;
}
```

#### 2. DP on Grids (HIGH PRIORITY — actual Uber question)
**Problem:** Largest 1-Bordered Square (LC 1139)
- Brute force: O(N⁴), optimize to O(N³) using prefix sums for rows/cols
- Key insight: precompute `left[i][j]` (consecutive 1s to the left) and `top[i][j]` (consecutive 1s upward)

```java
public int largest1BorderedSquare(int[][] grid) {
    int m = grid.length, n = grid[0].length;
    int[][] left = new int[m][n], top = new int[m][n];
    for (int i = 0; i < m; i++)
        for (int j = 0; j < n; j++)
            if (grid[i][j] == 1) {
                left[i][j] = (j > 0 ? left[i][j-1] : 0) + 1;
                top[i][j]  = (i > 0 ? top[i-1][j]  : 0) + 1;
            }
    for (int size = Math.min(m, n); size >= 1; size--)
        for (int i = size-1; i < m; i++)
            for (int j = size-1; j < n; j++)
                if (left[i][j] >= size && top[i][j] >= size &&
                    left[i-size+1][j] >= size && top[i][j-size+1] >= size)
                    return size * size;
    return 0;
}
```

#### 3. Dynamic Programming — Classic Patterns
Practice these DP patterns (all from real Uber reports):
- **Kadane's Algorithm** variants (max subarray, grid variants)
- **Interval DP** — merge intervals, balloon burst
- **0/1 Knapsack variants**
- **LCS / Edit Distance**

#### 4. Graphs — BFS/DFS
- Word Ladder, Number of Islands, Course Schedule (topological sort)
- Shortest path: Dijkstra, Bellman-Ford (when to use which)

#### 5. Heaps / Priority Queues
- K closest points, Top-K elements, Merge K sorted lists
- **Meeting Rooms II** (minimum rooms needed) — directly maps to machine coding

#### 6. Monotonic Stack
- Next greater element, First discount to the right
- Largest rectangle in histogram

### DSA Communication Template
```
1. "Let me understand the problem — [restate it]"
2. "Constraints: input size N = ?, value range = ?"
3. "Brute force: [explain O(?) approach]"
4. "Optimization: [explain pattern being used]"
5. "Let me code it up..."
6. "Test case: [walk through example]"
7. "Edge cases: empty array, single element, all same values"
```

---

## STEP 3 — C2: Machine Coding (Functional Coding)

### What It Is
Write a **fully working, object-oriented, production-quality solution** to a real-world problem in 45–60 minutes. This is **NOT a system design round**. Think: DSA + clean OOP.

### Critical Lessons from Real Uber Candidates
> "Treat it like DSA only. Once working code is done, inject design patterns and SOLID principles."
> "Ask clarifying questions first. Understand the problem like a product spec."
> "DO NOT jump to code without understanding. DO NOT overthink design patterns first."

### The Actual Question Pattern (Reported for L5A Backend)
**Train/Platform Scheduling** — a variant of **Meeting Rooms III (LC 2402)**

Problem summary:
- N platforms, M trains arriving with (arrival_time, duration)
- Find: which platform gets assigned + when
- Handle: waiting queue when all platforms are occupied
- Query: given platform + time, get the train scheduled there

```java
// Core data structures to use:
// 1. minHeap of available platforms (by platform number)
// 2. minHeap of occupied platforms (by next_available_time)

import java.util.*;

class TrainScheduler {
    private final int numPlatforms;
    // available: min-heap by platform number
    private final PriorityQueue<Integer> available;
    // occupied: min-heap by [nextFreeTime, platformId]
    private final PriorityQueue<int[]> occupied;
    // track schedules: platformId -> list of (startTime, endTime)
    private final Map<Integer, List<int[]>> schedule;

    public TrainScheduler(int numPlatforms) {
        this.numPlatforms = numPlatforms;
        this.available = new PriorityQueue<>();
        this.occupied = new PriorityQueue<>((a, b) ->
            a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);
        this.schedule = new HashMap<>();
        for (int i = 0; i < numPlatforms; i++) {
            available.add(i);
            schedule.put(i, new ArrayList<>());
        }
    }

    // Returns [platformId, actualStartTime]
    public int[] scheduleTrain(int arrivalTime, int duration) {
        // Free up platforms that become available by arrivalTime
        while (!occupied.isEmpty() && occupied.peek()[0] <= arrivalTime) {
            available.add(occupied.poll()[1]);
        }

        int platformId, startTime;
        if (!available.isEmpty()) {
            platformId = available.poll();
            startTime = arrivalTime;
        } else {
            // Wait — pick platform that frees up earliest
            int[] earliest = occupied.poll();
            startTime = earliest[0];
            platformId = earliest[1];
        }

        int endTime = startTime + duration;
        occupied.add(new int[]{endTime, platformId});
        schedule.get(platformId).add(new int[]{startTime, endTime});
        return new int[]{platformId, startTime};
    }

    // Query: get train at (platformId, queryTime)
    public int[] getTrainAtPlatform(int platformId, int queryTime) {
        for (int[] slot : schedule.get(platformId)) {
            if (slot[0] <= queryTime && queryTime < slot[1]) {
                return slot;
            }
        }
        return null; // No train at this time
    }
}
```

### Other Machine Coding Problem Types to Practice

| Problem | Core Pattern |
|---|---|
| Train/Platform scheduling | Meeting Rooms III + OOP |
| Parking lot system | OOP + heap/map |
| Library management system | OOP + design |
| Rate limiter | Sliding window + OOP |
| LRU/LFU Cache | LinkedHashMap / heap + map |
| Elevator system | Priority queue + state machine |
| Food delivery assignment | Greedy + OOP |
| In-memory key-value store with TTL | TreeMap + expiry |

### Machine Coding Execution Checklist
```
[ ] Read problem fully before writing any code
[ ] Ask: "What should happen when X?" for 3-4 edge cases
[ ] Define your classes/interfaces on paper first (2 min)
[ ] Implement core data structure + main method first
[ ] Test with the given example before refining
[ ] Refactor for SRP, clean naming, remove magic numbers
[ ] Discuss time/space complexity
```

### Code Quality Bar at Uber
```java
// BAD — what NOT to do
int x = pq.poll()[0]; // what is x?

// GOOD — what to do
int nextAvailableTime = occupiedPlatforms.poll()[0];
```

- Class names: `TrainScheduler`, `Platform`, `TrainArrival`
- Method names: `scheduleTrain()`, `getTrainAtPlatform()`
- No single-letter variables except loop indices
- Each class has one responsibility (SRP)
- Use interfaces where it makes sense (`Scheduler`, `QueryHandler`)

---

## YOUR EDGE — How to Use Your Background

### At BPS (Engineering Leadership Discussion)
Lead with scale and reliability:
> "At Skydo, I owned a payments platform doing 10K+ international transactions/day. The core challenge was ensuring idempotency and failure recovery across distributed workflows. Here's how I architected it..."

### For Any System Design Discussion
You have direct experience with:
- **Distributed locking** → explain Redlock, use cases vs optimistic locking
- **Workflow orchestration** → compare Temporal, Airflow, custom job schedulers
- **Idempotency at scale** → idempotency keys, deduplication windows, exactly-once semantics
- **Reconciliation systems** → two-phase commit vs Saga vs outbox pattern
- **Multi-tenant SaaS** → tenant isolation, rate limiting, schema-per-tenant vs shared schema

### Behavioral Angles (Uber Values)
Prepare 2-min STAR stories for each:

| Uber Value | Your Story |
|---|---|
| **Go Get It** (Ownership) | Led end-to-end delivery of payments platform from 0 to 10K TPS; also took on CIO role for ISO 27001/SOC2 |
| **Build With Heart** (Customer obsession) | Cut onboarding from hours to minutes — direct customer pain → engineering solution |
| **Surprise and Delight** | GenAI adoption initiative — defined standards that improved dev speed org-wide |
| **Stand for Safety** | SOC 2 Type II as CIO — built DLP controls, org-wide security posture |
| **Be Fearlessly Open** | Managing conflict between delivery timelines and technical debt at Goldman/Skydo |
| **Grow Together** | Mentored 8 engineers directly; drove Goldman team from Senior → VP in 1 year |

---

## 5-Day Sprint Plan (Apr 6 → Apr 11)

### Day 1 (Today, Mon Apr 6) — DSA Foundations
- [ ] Solve: LC 1438 (Longest subarray abs diff ≤ K) — target O(N) with deques
- [ ] Solve: LC 1139 (Largest 1-bordered square)
- [ ] Solve: LC 2402 (Meeting Rooms III) — foundation for machine coding
- [ ] Review: Monotonic stack pattern (next greater element, LC 739)

### Day 2 (Tue Apr 7) — DP + Graphs
- [ ] Solve: LC 300 (LIS), LC 322 (Coin Change), LC 1143 (LCS)
- [ ] Solve: LC 207 (Course Schedule — topological sort)
- [ ] Solve: LC 127 (Word Ladder — BFS)
- [ ] Practice: Explain brute force → optimized approach out loud

### Day 3 (Wed Apr 8) — Machine Coding Practice
- [ ] Code the `TrainScheduler` from scratch (no reference) in 45 min
- [ ] Code an LRU Cache from scratch (no library)
- [ ] Code a Rate Limiter (sliding window)
- [ ] Practice narrating while coding

### Day 4 (Thu Apr 9) — Backend Concepts + BPS Prep
- [ ] Write out your 3-minute BPS intro story (Skydo payments platform)
- [ ] Review: Distributed locking (Redlock), Saga pattern, Outbox pattern
- [ ] Review: CAP theorem trade-offs with real examples from your work
- [ ] Practice 5 STAR behavioral stories using the table above

### Day 5 (Fri Apr 10) — Mock + Review
- [ ] Do a timed mock session: 1 DSA problem in 45 min (no looking up)
- [ ] Do a timed mock machine coding in 50 min
- [ ] Review all edge cases for your prepared problems
- [ ] Sleep early. Interview day is physically demanding.

---

## Quick-Reference: Problems to Solve Before Apr 11

| # | Problem | LC # | Pattern | Priority |
|---|---|---|---|---|
| 1 | Longest subarray abs diff ≤ K | 1438 | Sliding window + deque | MUST |
| 2 | Largest 1-bordered square | 1139 | DP / prefix sum | MUST |
| 3 | Meeting Rooms III | 2402 | Heap | MUST |
| 4 | Longest Increasing Subsequence | 300 | DP | HIGH |
| 5 | Coin Change | 322 | DP | HIGH |
| 6 | Word Ladder | 127 | BFS | HIGH |
| 7 | Course Schedule | 207 | Topological sort | HIGH |
| 8 | Merge K sorted lists | 23 | Heap | HIGH |
| 9 | LRU Cache | 146 | LinkedHashMap | MUST (MC) |
| 10 | Next Greater Element | 739 | Monotonic stack | MEDIUM |
| 11 | Number of Islands | 200 | DFS/BFS | MEDIUM |
| 12 | Shortest Path (Dijkstra) | 743 | Heap + BFS | MEDIUM |

---

## On Interview Day

### BPS — Opening Move
When the interviewer starts:
> "Before we dive in, just to set context: I'm currently Engineering Manager at Skydo, where I own a distributed payments platform processing 10K+ transactions/day. I also led our ISO 27001/SOC 2 certifications as CIO. I've been an IC for 8+ years prior to management — so I'm quite comfortable going deep technically."

### C1/C2 — If You're Stuck
> "I have a brute force approach in mind — let me code that up first to ensure correctness, and then we can optimize together."

Never go silent. Always narrate. The interviewer wants to see how you think, not just the answer.

### After Each Round
Ask one of:
- "What does the team's biggest technical challenge look like right now?"
- "What does success look like in the first 6 months for this role?"
- "How does the backend team at Uber Bengaluru collaborate with global teams?"

---

*Good luck, Ramit. Your distributed systems background is genuinely relevant to Uber's infrastructure. Own that narrative.*
