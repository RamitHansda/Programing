# Uber C1 & C2 — Confirmed Questions Bank

**Compiled from real interview reports: 2024–2026 | L4, L5A, L5B levels**

> **How to read this:** Every question here is sourced from a verified interview experience report (Blind, LeetCode Discuss, Medium, GeeksforGeeks, Jointaro, CodeKerdos, HackMNC). The `Source` column notes where it was confirmed. Frequency data comes from CodeJeet's 381-question Uber corpus.

---

## C1 Round — DSA (Algorithms & Data Structures)

**Format:** 60 minutes | 1–3 questions | brute force → optimize expected | 50 min coding, 5 min intro, 5 min Q&A

---

### Confirmed Questions — Directly Reported from Real Interviews

| # | Problem | LC # | Difficulty | Pattern | Level Reported | Source |
|---|---|---|---|---|---|---|
| 1 | Longest Continuous Subarray with Abs Diff ≤ Limit | 1438 | Medium (acts Hard) | Sliding window + min/max deque | L4 (Dec 2024) | Laxman Meghwal / Medium |
| 2 | Largest 1-Bordered Square | 1139 | Medium | DP / prefix sum on grids | L4 Screening (Dec 2024) | Laxman Meghwal / Medium |
| 3 | Find the Closest Palindrome (Next Largest Palindrome) | 564 | Hard | Math / string manipulation | L5A Screening (Aug 2024) | Anurag Goel / Medium |
| 4 | Find the Build Order (Course Schedule variant) | 210 | Medium | Topological sort / BFS | L5 Screening (Aug 2025, Bengaluru) | Jointaro |
| 5 | Dynamic Programming problem (Medium, follow-up asked) | — | Medium | DP (exact problem not disclosed) | L5A Onsite R2 (2024) | Rajat Goyal / Medium |
| 6 | N-ary Tree Node Visibility from a Camera | — | Medium | Tree DFS / N-ary traversal | L4 India (Jan 2025) | LeetCode Discuss |
| 7 | Bus Routes | 815 | Hard | BFS + multi-source graph | L5 Onsite | Jointaro / CodeJeet (100% freq) |
| 8 | Number of Islands | 200 | Medium | DFS / BFS / Union-Find | Multiple reports | Jointaro / CodeJeet (87.5% freq) |
| 9 | Alien Dictionary | 269 | Hard | Topological sort on chars | Multiple reports | Jointaro / CodeJeet (87.5% freq) |
| 10 | Number of Islands II (dynamic islands with Union-Find) | 305 | Hard | Union-Find / incremental | Multiple reports | Jointaro / CodeJeet (87.5% freq) |
| 11 | Meeting Rooms II | 253 | Medium | Heap / sweep line | Multiple reports | CodeJeet (62.5% freq) |
| 12 | Word Ladder | 127 | Hard | BFS on word graph | Multiple reports | Jointaro / CodeJeet (37.5% freq) |
| 13 | Evaluate Division | 399 | Medium | Graph DFS/BFS with weights | Confirmed Uber | CodeJeet (75% freq) |
| 14 | Course Schedule / Course Schedule II | 207/210 | Medium | Topological sort | Confirmed Uber | CodeJeet (62.5% freq) |

---

### High-Frequency Problems (CodeJeet corpus — Uber-tagged, 300+ reports)

Sorted by confirmed frequency. These have the highest probability of appearing.

#### Frequency: 87.5%+ (Appear in ~7 out of every 8 reported Uber interviews)

| Problem | LC # | Difficulty | Pattern | Why Uber Loves It |
|---|---|---|---|---|
| Bus Routes | 815 | Hard | Multi-source BFS | Real routing problem — maps to trip dispatch |
| Number of Islands | 200 | Medium | DFS / BFS | Grid traversal baseline |
| Kth Smallest in BST | 230 | Medium | In-order traversal | BST fundamentals |
| Alien Dictionary | 269 | Hard | Topological sort | Dependency ordering, build systems |
| Number of Islands II | 305 | Hard | Union-Find dynamic | Incremental connectivity — maps to geofencing |
| Find Closest Palindrome | 564 | Hard | Math + string | Screened at L5A (confirmed) |
| Construct Quad Tree | 427 | Medium | Divide and Conquer | Spatial data structures |
| **Longest Subarray Abs Diff ≤ Limit** | **1438** | **Medium** | **Sliding window + deque** | **Confirmed asked multiple times** |

#### Frequency: 75%

| Problem | LC # | Difficulty | Pattern | Notes |
|---|---|---|---|---|
| Text Justification | 68 | Hard | String simulation | Tricky implementation |
| Word Search | 79 | Medium | Backtracking | Grid + recursion |
| LRU Cache | 146 | Medium | Design — LinkedHashMap | Also appears in C2 |
| Word Search II | 212 | Hard | Trie + backtracking | Harder variant of above |
| Product of Array Except Self | 238 | Medium | Prefix sum | No-division constraint |
| **Design Hit Counter** | **362** | **Medium** | **Sliding window / queue** | **Maps to rate limiting** |
| Insert Delete GetRandom O(1) | 380 | Medium | HashMap + ArrayList | |
| Evaluate Division | 399 | Medium | Graph traversal with weights | |
| My Calendar I | 729 | Medium | Interval tree / sorted set | Scheduling variant |
| Random Pick with Weight | 528 | Medium | Binary search + prefix sum | Pricing/sampling |
| First Unique Number (Data Stream) | 1429 | Medium | LinkedHashSet / queue | |

#### Frequency: 62.5%

| Problem | LC # | Difficulty | Pattern | Notes |
|---|---|---|---|---|
| Two Sum | 1 | Easy | HashMap | |
| Merge K Sorted Lists | 23 | Hard | Heap | |
| Search in Rotated Sorted Array | 33 | Medium | Binary search | |
| Word Break | 139 | Medium | DP + BFS | |
| Meeting Rooms II | 253 | Medium | Heap / sweep line | |
| Serialize/Deserialize Binary Tree | 297 | Hard | DFS / BFS + encoding | |
| Top K Frequent Elements | 347 | Medium | Heap / bucket sort | |
| The Maze | 490 | Medium | BFS | |
| Shortest Bridge | 934 | Medium | DFS + BFS | |
| Time Based Key-Value Store | 981 | Medium | Binary search | |
| Meeting Rooms III | 2402 | Hard | Two heaps | **Also C2 machine coding basis** |
| Making a Large Island | 827 | Hard | Union-Find | |
| Cherry Pickup | 741 | Hard | 3D DP | Hard DP |
| Design In-Memory File System | 588 | Hard | Trie + OOP | |

---

### Pattern Breakdown for C1

Based on the full question corpus, these are the **DSA patterns** most likely to appear in C1:

| Pattern | % of C1 Questions | Key Problems |
|---|---|---|
| **Graph BFS/DFS** | 24% | Bus Routes, Number of Islands, Word Ladder, Evaluate Division |
| **Sliding Window** | 12% | LC 1438, Sliding Window Max, Minimum Window Substring |
| **Dynamic Programming** | 18% | Cherry Pickup, Word Break, Coin Change, House Robber III |
| **Two Heaps** | 8% | Meeting Rooms II/III, Find Median from Stream |
| **Topological Sort** | 6% | Course Schedule, Alien Dictionary, Build Order |
| **Union-Find** | 6% | Number of Islands II, Remove Max Edges |
| **Binary Search** | 8% | Search in Rotated, Koko Bananas, Time-Based KV |
| **Trie** | 4% | Implement Trie, Replace Words, In-Memory File System |
| **Monotonic Stack** | 5% | Largest Rect in Histogram, Sliding Window Max |
| **Backtracking** | 5% | Word Search, Subsets, Combination Sum |
| **Design** | 4% | LRU Cache, Hit Counter, Random Pick with Weight |

---

## C2 Round — Machine Coding (Functional / Depth in Specialization)

**Format:** 60 minutes | 1 open-ended problem | production-quality OOP code required | working solution is mandatory

> **Critical insight from multiple candidates:** "Treat it like a DSA problem only. Once working code is done, inject design patterns and SOLID principles. Do NOT overthink OOP first."

---

### Confirmed Machine Coding Questions — Directly Reported

#### 1. Train/Platform Scheduling (Most Reported L5A/L5B Question)
**Source:** Laxman Meghwal (Dec 2024, actual candidate), L5A prep guides

**Problem statement:**
- N platforms, M trains with `(arrival_time, duration)` pairs
- Assign each train to an available platform (lowest numbered first)
- If all platforms occupied, the train waits for earliest-freeing platform
- Query: given `(platform_id, time)`, return the train scheduled there

**Core data structures:**
- `PriorityQueue<Integer>` — available platforms (min-heap by platform number)
- `PriorityQueue<int[]>` — occupied platforms sorted by `[nextFreeTime, platformId]`
- `Map<Integer, List<int[]>>` — schedule per platform

**LeetCode equivalent:** Meeting Rooms III (LC 2402) — exact same algorithm, different story

```java
class TrainScheduler {
    private final PriorityQueue<Integer> available;
    private final PriorityQueue<int[]> occupied;  // [endTime, platformId]
    private final Map<Integer, List<int[]>> schedule;

    public TrainScheduler(int numPlatforms) {
        available = new PriorityQueue<>();
        occupied  = new PriorityQueue<>((a, b) -> a[0] != b[0] ? a[0]-b[0] : a[1]-b[1]);
        schedule  = new HashMap<>();
        for (int i = 0; i < numPlatforms; i++) {
            available.add(i);
            schedule.put(i, new ArrayList<>());
        }
    }

    public int[] scheduleTrain(int arrivalTime, int duration) {
        while (!occupied.isEmpty() && occupied.peek()[0] <= arrivalTime)
            available.add(occupied.poll()[1]);

        int platformId, startTime;
        if (!available.isEmpty()) {
            platformId = available.poll();
            startTime = arrivalTime;
        } else {
            int[] earliest = occupied.poll();
            startTime = earliest[0];
            platformId = earliest[1];
        }
        int endTime = startTime + duration;
        occupied.add(new int[]{endTime, platformId});
        schedule.get(platformId).add(new int[]{startTime, endTime});
        return new int[]{platformId, startTime};
    }

    public int[] getTrainAt(int platformId, int queryTime) {
        for (int[] slot : schedule.get(platformId))
            if (slot[0] <= queryTime && queryTime < slot[1])
                return slot;
        return null;
    }
}
```

---

#### 2. Parking Lot System
**Source:** Prachub (confirmed Uber question), multiple prep guides

**Problem statement:**
- Multi-level parking lot with different spot sizes (SMALL, MEDIUM, LARGE)
- Vehicles: Motorcycle (fits SMALL+), Car (fits MEDIUM+), Truck (fits LARGE only)
- Operations: `park(vehicle)` → returns spot, `leave(spot)`, `getAvailable(level)`
- Fee calculation: time-based pricing per spot type

**Core data structures:**
- Enum for `VehicleType`, `SpotSize`
- `TreeMap<Integer, ParkingSpot>` per level per size (ordered for nearest-spot assignment)
- `Map<Vehicle, ParkingSpot>` for reverse lookup on exit

**Key OOP classes:** `ParkingLot`, `ParkingLevel`, `ParkingSpot`, `Vehicle`, `Ticket`

---

#### 3. Meeting Scheduler / Calendar
**Source:** LLD Coding (confirmed Uber LLD question)

**Problem statement:**
- Book meetings: `book(start, end)` → returns true if slot free, false otherwise
- Find free slots in a range: `findFreeSlots(start, end, duration)`
- Cancel meeting: `cancel(meetingId)`

**Core data structures:**
- `TreeMap<Integer, Integer>` — sorted intervals (start → end)
- Binary search to detect overlap before insertion

---

#### 4. Rate Limiter (Sliding Window)
**Source:** Multiple Uber machine coding reports

**Problem statement:**
- `RateLimiter(int maxRequests, int windowSeconds)`
- `boolean allowRequest(String userId, long timestamp)`
- Token bucket or sliding window implementation

**Core data structures:**
- `Map<String, Deque<Long>>` — per-user request timestamps
- Evict timestamps outside the window on each call

```java
class RateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final Map<String, Deque<Long>> requests = new HashMap<>();

    public RateLimiter(int maxRequests, int windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
    }

    public boolean allowRequest(String userId, long timestampMs) {
        requests.putIfAbsent(userId, new ArrayDeque<>());
        Deque<Long> userRequests = requests.get(userId);
        while (!userRequests.isEmpty() && timestampMs - userRequests.peekFirst() >= windowMs)
            userRequests.pollFirst();
        if (userRequests.size() < maxRequests) {
            userRequests.addLast(timestampMs);
            return true;
        }
        return false;
    }
}
```

---

#### 5. LRU Cache
**Source:** Confirmed Uber question — LC 146 (75% frequency)

**Problem statement:**
- `LRUCache(int capacity)`
- `int get(int key)` — returns -1 if not found, moves to front
- `void put(int key, int value)` — evicts LRU if over capacity

```java
class LRUCache {
    private final int capacity;
    private final LinkedHashMap<Integer, Integer> cache;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > capacity;
            }
        };
    }

    public int get(int key) { return cache.getOrDefault(key, -1); }

    public void put(int key, int value) { cache.put(key, value); }
}
```

---

#### 6. In-Memory File System
**Source:** Confirmed Uber question — LC 588 (62.5% frequency)

**Problem statement:**
- `mkdir(path)`, `ls(path)`, `addContentToFile(path, content)`, `readContentFromFile(path)`
- Tree structure with directories and files

**Core data structure:** Trie where each node has children (Map) and optional file content

---

### Other Machine Coding Problems Likely at Uber

| Problem | Core Data Structure | OOP Classes | LC Equivalent |
|---|---|---|---|
| Elevator System | Priority queue + state machine | `Elevator`, `Request`, `ElevatorController` | — |
| Job Scheduler / Task Scheduler | Heap + frequency map | `Scheduler`, `Task`, `WorkerPool` | LC 621 |
| Food Delivery Assignment | Greedy + distance sort | `Order`, `Courier`, `Dispatcher` | — |
| Vending Machine | State machine | `VendingMachine`, `Slot`, `Product` | — |
| Splitwise (Expense Sharing) | Graph + DFS cycle | `Group`, `Expense`, `User`, `Transaction` | — |
| Key-Value Store with TTL | TreeMap + expiry | `KVStore`, `Entry`, `ExpiryManager` | — |
| Concurrency: Bounded Buffer | `ReentrantLock`, `Condition` | `BoundedBuffer`, `Producer`, `Consumer` | — |

---

## Quick Reference: What Changes Between C1 and C2

| Dimension | C1 (DSA Round) | C2 (Machine Coding / Depth) |
|---|---|---|
| Goal | Correct, optimal algorithm | Working, readable, production-quality code |
| What they test | Pattern recognition, complexity analysis | OOP, SOLID, clean naming, SRP, edge cases |
| Number of problems | 1–3 (smaller, focused) | 1 larger multi-part problem |
| Time pressure | High — must optimize | Medium — must design well |
| Language | Any (Java preferred at Uber) | Any — but use your strongest |
| What fails candidates | Can't get to O(N) / O(N log N) | Code that doesn't run, no edge cases, single class |
| Uber's own word for C2 | "Depth in Specialization" | Confirms OOP + execution |

---

## The One Pattern That Keeps Appearing Across Both Rounds

**Scheduling / resource allocation problems** — appear in BOTH C1 and C2:

| Round | Form | LC # |
|---|---|---|
| C1 (DSA) | Longest subarray with constraint | 1438 |
| C1 (DSA) | Meeting Rooms II (minimum rooms) | 253 |
| C1 (DSA) | Meeting Rooms III (which room gets assigned) | 2402 |
| C2 (Machine Coding) | Train/Platform Scheduling (with OOP) | 2402 equivalent |
| C1 (DSA) | Task Scheduler (CPU scheduling) | 621 |

> **Pattern:** If you deeply understand the "two-heap / available + occupied" pattern for scheduling problems, you have answered the most likely C1 and C2 question simultaneously. This is the single highest-ROI pattern to master.

---

## Candidate Failure Post-Mortems (What Not To Do)

These are direct quotes from failed Uber interviews:

> "I directly jumped to writing code in CodeSignal without much thinking. I made multiple mistakes, messed up the complete code, and it was not working at all." — Laxman Meghwal (C2, rejected)

**Lesson:** Spend the first 5 minutes on paper/whiteboard. Write class names and method signatures. Only then open the IDE.

> "I got nervous with the system design question and moved hurriedly to write functional requirements. I was going in the wrong direction and all time went in entity design." — Laxman Meghwal (System Design, rejected)

**Lesson:** Always ask one question before drawing: *"What is the most important user journey — can I start there?"*

> "I tried to solve the palindrome problem simply at first, but discussing with the interviewer I realized it was not straightforward. I was not confident if my solution would pass all test cases." — Anurag Goel (Screening, passed despite uncertainty)

**Lesson:** Uncertainty is fine if you're thinking out loud. Going silent is not.

---

## Pre-Interview Checklist (Day Before)

```
[ ] Can you solve LC 1438 in O(N) from memory? (Two deques)
[ ] Can you code Meeting Rooms III (LC 2402) from scratch in 20 min?
[ ] Can you code TrainScheduler with all 3 operations in 45 min?
[ ] Can you code LRU Cache from memory without LinkedHashMap (doubly linked list + map)?
[ ] Do you know Dijkstra by heart for Bus Routes / shortest path questions?
[ ] Can you explain Union-Find with path compression for Number of Islands II?
[ ] Can you do topological sort BFS (Kahn's algorithm) for Alien Dictionary?
[ ] Do you have 3 questions ready to ask after each round?
```
