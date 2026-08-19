# Tekion Corp — LLD & HLD: Full Ready-to-Deliver Answers

> Companion to `TEKION-LLD-HLD-QUESTIONS-BANK.md`. That doc tells you *what's asked*; this doc gives you the actual **answer to say out loud** for each one — requirements, entities/classes, code skeletons, schema, and the follow-ups they'll throw at you next.
>
> **How to use this in the interview (say this, don't skip it):**
> 1. **Restate + clarify requirements first** (30–60 sec). Tekion interviewers explicitly reward this — several reports call out candidates who jumped straight to entities as a negative signal.
> 2. **List functional + non-functional requirements** out loud, then say "let me design to these."
> 3. **Draw/name entities before writing any code.**
> 4. **State the design pattern you're using and why**, not just apply it silently.
> 5. **Volunteer the concurrency/scale question before they ask it** — this is the #1 differentiator between offer and reject in the source reports.
> 6. Language: reports are mostly **Java** backend roles — code skeletons below are Java. If your loop is different, the entities/logic port directly.

---

# PART 1 — Backend LLD (Full Answers)

## 1. Design an Elevator System *(asked most frequently — know this cold)*

**Clarify first:**
> "How many elevators and floors? Is this a single building? Do we need external hall-call buttons (up/down) in addition to internal floor buttons? Do all elevators serve all floors?"
Assume: N elevators, M floors, external up/down buttons per floor + internal floor buttons per elevator.

**Functional requirements:** request an elevator from any floor (direction), request a destination floor from inside, dispatch the "best" elevator, move elevators toward requests efficiently, open/close doors, handle simultaneous requests.

**Non-functional:** minimize wait time, extensible to more elevators/floors, thread-safe (concurrent requests from multiple floors).

**Entities:**

```
Elevator            - id, currentFloor, direction (UP/DOWN/IDLE), state (MOVING/STOPPED/DOOR_OPEN), requests (sorted set)
Floor                - floorNumber, upButton, downButton
Request              - sourceFloor, destinationFloor (nullable for hall calls), direction
ElevatorController   - list<Elevator>, dispatch(Request) -> Elevator
Scheduler            - dispatch strategy (interface) -> pluggable algorithm
Door                 - state (OPEN/CLOSED)
```

**Design pattern to name explicitly:** **State pattern** for `Elevator` (IDLE, MOVING_UP, MOVING_DOWN, DOORS_OPEN — each state defines legal transitions), and **Strategy pattern** for dispatch (`NearestElevatorStrategy`, `SCANStrategy`) so you can swap dispatch logic without touching `ElevatorController`.

```java
interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, Request request);
}

class NearestElevatorStrategy implements DispatchStrategy {
    public Elevator selectElevator(List<Elevator> elevators, Request request) {
        return elevators.stream()
            .filter(e -> e.canServe(request))          // idle, or moving same direction and not past the floor
            .min(Comparator.comparingInt(e -> Math.abs(e.getCurrentFloor() - request.getSourceFloor())))
            .orElse(elevators.get(0));                  // fallback: least-loaded idle elevator
    }
}

class Elevator {
    int id, currentFloor;
    Direction direction = Direction.IDLE;
    TreeSet<Integer> upRequests = new TreeSet<>();
    TreeSet<Integer> downRequests = new TreeSet<>(Collections.reverseOrder());

    synchronized void addRequest(int floor, Direction dir) {
        if (dir == Direction.UP) upRequests.add(floor); else downRequests.add(floor);
    }

    synchronized void step() {                          // called every "tick" by the controller
        if (direction == Direction.UP && !upRequests.isEmpty()) {
            currentFloor = upRequests.pollFirst();
            openDoors();
        } else if (direction == Direction.DOWN && !downRequests.isEmpty()) {
            currentFloor = downRequests.pollFirst();
            openDoors();
        } else {
            direction = pickNextDirection();
        }
    }
}

class ElevatorController {
    List<Elevator> elevators;
    DispatchStrategy strategy;

    void handleRequest(Request request) {
        Elevator chosen = strategy.selectElevator(elevators, request);
        chosen.addRequest(request.getSourceFloor(), request.getDirection());
    }
}
```

**Scheduling algorithm to name:** the SCAN/elevator algorithm — an elevator keeps moving in one direction, servicing all pending requests along the way, only reversing when there are no more requests ahead — this avoids starvation and minimizes reversals versus naive FIFO.

**Follow-ups and your answers:**
- *"How do you pick the elevator when 2 are idle and equidistant?"* → tiebreak by elevator with fewer total pending requests (load balancing), or lowest ID for determinism.
- *"Someone on floor 3 going up is interrupted by a floor-4 hall call going up — how is that handled?"* → SCAN handles it naturally: floor 4 gets added to `upRequests` and served en route since the elevator is already moving up and floor 4 is ahead.
- *"Concurrency — two floor buttons pressed at the same instant"* → `synchronized` on `addRequest`/`step`, or better: a single-threaded event loop per elevator consuming from a thread-safe queue, avoiding lock contention across elevators entirely.
- *"How would you extend this to a freight elevator with weight limits?"* → subclass `Elevator` → `FreightElevator` overriding `canServe()` to check weight capacity — this is why State/Strategy over one monolithic class matters.

---

## 2. Design a Parking Lot System

**Clarify:** vehicle types (motorcycle/car/truck)? Multiple floors? Pricing model — flat/hourly, same for all vehicle types? Payment at exit only?
Assume: 3 vehicle types, multi-floor, hourly pricing rounded up, pay at exit.

**Entities:**

```
ParkingLot (singleton)  - List<ParkingFloor>
ParkingFloor            - floorNumber, List<ParkingSpot>
ParkingSpot             - id, SpotType, isOccupied, parkedVehicle
Vehicle (abstract)      - licensePlate, VehicleType  →  Motorcycle, Car, Truck
Ticket                  - id, vehicle, spot, entryTime
PricingStrategy (iface) - calculate(entry, exit) -> fee   →  HourlyPricing, FlatRatePricing
```

```java
enum VehicleType { MOTORCYCLE, CAR, TRUCK }
enum SpotType { SMALL, COMPACT, LARGE }

// O(1) lookup instead of if-else chains
static final Map<VehicleType, SpotType> VEHICLE_TO_SPOT = Map.of(
    VehicleType.MOTORCYCLE, SpotType.SMALL,
    VehicleType.CAR, SpotType.COMPACT,
    VehicleType.TRUCK, SpotType.LARGE
);

class ParkingSpot {
    int id; SpotType type; volatile boolean occupied; Vehicle vehicle;

    synchronized boolean assign(Vehicle v) {
        if (occupied) return false;
        occupied = true; vehicle = v; return true;
    }
    synchronized void release() { occupied = false; vehicle = null; }
}

class ParkingLot {
    List<ParkingFloor> floors;
    PricingStrategy pricingStrategy;

    Ticket park(Vehicle vehicle) {
        SpotType required = VEHICLE_TO_SPOT.get(vehicle.getType());
        for (ParkingFloor floor : floors) {
            for (ParkingSpot spot : floor.getAvailableSpots(required)) {
                if (spot.assign(vehicle)) {                     // atomic — first thread to succeed wins
                    return new Ticket(vehicle, spot, Instant.now());
                }
            }
        }
        throw new NoAvailableSpotException();
    }

    Receipt unpark(Ticket ticket) {
        if (ticket.isUsed()) throw new InvalidTicketException();
        double fee = pricingStrategy.calculate(ticket.getEntryTime(), Instant.now());
        ticket.getSpot().release();
        ticket.markUsed();
        return new Receipt(ticket, fee);
    }
}
```

**Design pattern to name:** **Strategy** for pricing (`HourlyPricing`, `FlatRatePricing`, `WeekendPricing` — swap without touching `ParkingLot`), **Singleton** for `ParkingLot` itself.

**Follow-ups:**
- *"Concurrency — two cars arrive at the same spot simultaneously"* → `spot.assign()` is the atomic operation (synchronized method, or a CAS/optimistic-lock if backed by a DB row) — whichever thread wins the `synchronized` block gets the spot; the other retries the next available spot. This is the same idempotency/atomicity instinct as a payments system.
- *"Notify a user who's been parked >1 hour"* (asked in a real report) → a scheduled job / delayed-queue (e.g., a Redis sorted-set keyed by `entryTime + 1hr`, polled by a worker) rather than a per-vehicle timer thread, which wouldn't scale.
- *"How do you scale this to 1000 parking lots across a city?"* → this is the HLD escalation: `ParkingLot` becomes a row in a DB indexed by `lot_id`, `Spot` availability cached in Redis for fast lookup, and a search service to find nearest lot with availability.

---

## 3. Design a Food Delivery System — LLD (schema + indexing/sharding focus)

**Clarify:** Scope to LLD — entities and schema, not full distributed architecture (that's the HLD version in Part 2). Confirm: users, restaurants, orders, delivery partners, payments are all in scope.

**Functional requirements:** browse restaurants/menu, place order, track order status, assign delivery partner, process payment.

**Entities & schema:**

```sql
users(id PK, name, phone, address, created_at)
restaurants(id PK, name, city_id, location(lat,lng), cuisine, is_open, avg_prep_time_min)
menu_items(id PK, restaurant_id FK, name, price, is_available)
orders(id PK, user_id FK, restaurant_id FK, status ENUM, total_amount, created_at, updated_at)
order_items(id PK, order_id FK, menu_item_id FK, quantity, price_at_order_time)
delivery_partners(id PK, name, current_location(lat,lng), status ENUM(AVAILABLE/BUSY/OFFLINE))
deliveries(id PK, order_id FK, partner_id FK, assigned_at, delivered_at, status)
payments(id PK, order_id FK, amount, status, idempotency_key UNIQUE, gateway_ref)
```

**Where to index (say this explicitly — it's a guaranteed follow-up):**
- `orders(user_id, created_at)` — "show my order history" is the most frequent read pattern → composite index.
- `orders(restaurant_id, status)` — restaurant dashboard needs "active orders for me."
- `restaurants(city_id, is_open)` — the browse/discovery query filters by city constantly.
- `menu_items(restaurant_id)` — trivial FK index, every menu load hits this.
- `payments(idempotency_key)` — **unique index**, this is what prevents double-charging on retry (see idempotency section, Part 3).
- Avoid over-indexing `orders.status` alone — it's low cardinality (few distinct values) and a full index scan on a huge table is often *worse* than a partial index scoped by a more selective column combined with it.

**Where to shard, and why:**
- `restaurants`, `menu_items`, `deliveries` → shard by **`city_id`** (or geohash region). Justification: a food-delivery system's access pattern is fundamentally local — a user in Bangalore never queries Delhi restaurants, so co-locating a city's data avoids cross-shard joins for the hottest query path (browse nearby restaurants).
- `users` → shard by `user_id` (hash-based) — access pattern is a single user's own data, no cross-user query needs a single shard.
- `orders` → shard by `user_id` for the "my orders" read path, but this creates a write hot-spot problem for a restaurant's "my active orders" view (now scattered across shards) — the honest trade-off to state: you either (a) accept a scatter-gather query for the restaurant view since it's lower volume than user reads, or (b) maintain a **denormalized read-model** of active orders per restaurant (e.g., in Redis, updated via the same event that writes the order) so the restaurant dashboard doesn't need a cross-shard query at all.
- `payments` → shard by `order_id` — keep it co-located with its order for transactional consistency during the payment-capture step.

**Follow-up you should expect and how to answer it:** *"Why not just shard everything by `restaurant_id`?"* → Because the user-facing read path (order history, profile) doesn't know `restaurant_id` upfront and would need a lookup table to find it, adding a hop to the hottest read query. Sharding key choice should match the dominant query pattern, not just "pick one FK and go."

---

## 4. Design BookMyShow / Movie Ticket Booking (concurrency-focused LLD)

**Clarify:** Single-city or multi-city? Seat-level booking (not just ticket count)? Payment integrated or out of scope? Assume seat-level, payment integrated, single city for LLD scope.

**Entities:**

```
Movie            - id, title, duration
Theater          - id, name, city
Screen           - id, theaterId, List<Seat>
Seat             - id, screenId, seatType (REGULAR/PREMIUM), rowLabel, seatNumber
Show             - id, movieId, screenId, startTime, endTime
Booking          - id, showId, userId, List<SeatId>, status (PENDING/CONFIRMED/CANCELLED/EXPIRED), createdAt
SeatLock         - showId, seatId, userId, lockedAt, expiresAt   ← the crux of this problem
Payment          - bookingId, amount, status, idempotencyKey
```

**The core design problem — preventing double-booking under concurrency — is what they're actually testing, not the CRUD:**

```java
class SeatBookingService {

    // Step 1: Temporarily lock seats (e.g., 5-min TTL) so two users can't both proceed to payment
    // for the same seat. Use a distributed lock (Redis SETNX with TTL) keyed by (showId, seatId).
    boolean lockSeats(String showId, List<String> seatIds, String userId) {
        List<String> acquired = new ArrayList<>();
        try {
            for (String seatId : seatIds) {
                String key = "lock:" + showId + ":" + seatId;
                boolean ok = redis.set(key, userId, "NX", "EX", 300);   // atomic set-if-not-exists, 5 min TTL
                if (!ok) {
                    releaseAll(acquired);                                // rollback partial locks
                    return false;
                }
                acquired.add(seatId);
            }
            return true;
        } catch (Exception e) {
            releaseAll(acquired);
            throw e;
        }
    }

    // Step 2: On payment success, confirm booking atomically — DB transaction with a
    // unique constraint on (show_id, seat_id) in a confirmed_seats table as the final
    // source of truth (defense in depth beyond the Redis lock, which is best-effort).
    @Transactional
    Booking confirmBooking(String bookingId, String paymentIdempotencyKey) {
        // INSERT into confirmed_seats(show_id, seat_id) ... ON CONFLICT DO NOTHING
        // if rows_inserted < seats_requested -> some other txn won the race -> refund + fail
    }
}
```

**Why two layers (Redis lock + DB unique constraint):** the Redis lock gives fast, low-latency UX ("this seat is reserved for you for 5 minutes") without hitting the DB on every seat-hover, but it's best-effort — a Redis failover or a bug could leak a lock. The **DB unique constraint on `(show_id, seat_id)` in the confirmed-booking table is the actual source of truth** that makes double-booking structurally impossible, not just unlikely. State this explicitly — interviewers are listening for "defense in depth," not a single mechanism.

**Follow-ups:**
- *"What happens if the user abandons checkout?"* → TTL on the Redis lock auto-expires; a background sweeper also expires `PENDING` bookings older than N minutes and releases seats.
- *"How do you handle payment success but booking-confirmation write failure?"* → outbox pattern — write the "booking confirmed" event transactionally with the payment-success record, and a separate consumer applies it to `confirmed_seats`, retryable and idempotent via the same `(show_id, seat_id)` unique constraint.
- *"Scale to a Big Billion Days-style flash sale for a blockbuster release?"* → this is the HLD escalation: queue-based admission control (virtual waiting room), pre-warmed cache of seat maps, rate limiting per user.

---

## 5. Design Snake & Ladder (must generalize to other board games)

**Clarify:** Explicitly ask: *"You mentioned extensibility to other games — should I design a general board-game framework, or make Snake & Ladder itself just cleanly extensible?"* This framing question alone signals seniority.

**Entities:**

```
Board          - size, Map<Integer,Integer> snakesAndLadders (start->end)
Player         - id, name, position
Dice           - roll() -> int                       (interface, so you can swap 1-die/2-dice/loaded-dice)
Game           - Board, List<Player>, Dice, currentPlayerIndex, status
GameRule       - interface: onMove(Player, int diceValue) -> new position   ← the extensibility seam
```

```java
interface Dice { int roll(); }
class SingleDice implements Dice {
    public int roll() { return ThreadLocalRandom.current().nextInt(1, 7); }
}

interface GameRule {
    int applyRule(int currentPosition, int diceValue, Board board);
}

class SnakeAndLadderRule implements GameRule {
    public int applyRule(int currentPosition, int diceValue, Board board) {
        int newPos = currentPosition + diceValue;
        if (newPos > board.getSize()) return currentPosition;              // overshoot -> stay
        return board.getSnakesAndLadders().getOrDefault(newPos, newPos);    // snake bite or ladder climb
    }
}

class Game {
    Board board; List<Player> players; Dice dice; GameRule rule;
    int currentPlayerIndex = 0;

    void playTurn() {
        Player player = players.get(currentPlayerIndex);
        int diceValue = dice.roll();
        int newPosition = rule.applyRule(player.getPosition(), diceValue, board);
        player.setPosition(newPosition);
        if (newPosition == board.getSize()) { announceWinner(player); return; }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }
}
```

**Why this generalizes ("extensibility to other games"):** `Dice` and `GameRule` are both interfaces — to build a completely different board game (e.g., a game with "chance" cards, or one using 2 dice with doubles-go-again logic), you implement a new `GameRule`/`Dice` and reuse `Game`, `Player`, `Board` untouched. This is the **Strategy pattern** applied twice — name it explicitly.

**Follow-ups:**
- *"Multiplayer over network — what changes?"* → `Game` becomes server-authoritative state; `playTurn()` is invoked via an API call, not a local loop; broadcast state diff to all connected players over WebSocket.
- *"How would you support a game with a completely different board shape (e.g., not linear)?"* → abstract `Board` behind an interface with `getNextPosition(current, steps)`, so linear (Snake & Ladder) and non-linear (e.g., Ludo-style branching paths) boards both implement it differently.

---

## 6. Design a Chess Game (standard LLD)

**Entities:** `Board (8x8 Cell[][])`, `Piece (abstract: King, Queen, Rook, Bishop, Knight, Pawn)`, `Move`, `Player`, `Game`.

```java
abstract class Piece {
    Color color; Position position;
    abstract List<Position> getValidMoves(Board board);   // polymorphism replaces a giant switch
}

class Knight extends Piece {
    List<Position> getValidMoves(Board board) {
        // L-shaped offsets, filtered by board bounds and same-color occupancy
    }
}

class Board {
    Cell[][] cells = new Cell[8][8];
    boolean isCheck(Color kingColor) { /* is any enemy piece's valid-move set containing king's position */ }
}

class Game {
    Board board; Player white, black; Player currentTurn; List<Move> history;

    boolean move(Position from, Position to) {
        Piece piece = board.getPieceAt(from);
        if (!piece.getValidMoves(board).contains(to)) return false;
        Move move = new Move(piece, from, to);
        board.applyMove(move);
        if (board.isCheckmate(opponent(currentTurn))) endGame();
        history.add(move);
        switchTurn();
        return true;
    }
}
```

**Design pattern to name:** polymorphism over `Piece` subclasses (avoids an if-else chain on piece type for move validation) is the interview-expected answer here; mention **Memento pattern** if asked about undo/move-history/replay.

**Follow-ups:** "How do you detect check/checkmate efficiently?" (scan whether any legal opposing move captures the king — cache attacked-squares per move rather than recomputing from scratch), "How would you add a chess clock?" (decorator/observer on `Game` ticking down `Player.remainingTime`, separate from move-validation logic).

---

## 7. Implement an LFU Cache (extensible)

**Requirement:** `get(key)` and `put(key, value)` in O(1), evicting the **least frequently used** key on overflow (tie-break: least recently used among equal frequency).

```java
class LFUCache {
    int capacity, minFreq;
    Map<Integer, Integer> keyToValue = new HashMap<>();
    Map<Integer, Integer> keyToFreq = new HashMap<>();
    Map<Integer, LinkedHashSet<Integer>> freqToKeys = new HashMap<>();   // LinkedHashSet preserves insertion order for LRU tiebreak

    LFUCache(int capacity) { this.capacity = capacity; }

    int get(int key) {
        if (!keyToValue.containsKey(key)) return -1;
        bumpFrequency(key);
        return keyToValue.get(key);
    }

    void put(int key, int value) {
        if (capacity == 0) return;
        if (keyToValue.containsKey(key)) {
            keyToValue.put(key, value);
            bumpFrequency(key);
            return;
        }
        if (keyToValue.size() >= capacity) {
            int evictKey = freqToKeys.get(minFreq).iterator().next();     // oldest among least-frequent
            freqToKeys.get(minFreq).remove(evictKey);
            keyToValue.remove(evictKey);
            keyToFreq.remove(evictKey);
        }
        keyToValue.put(key, value);
        keyToFreq.put(key, 1);
        freqToKeys.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    private void bumpFrequency(int key) {
        int freq = keyToFreq.get(key);
        freqToKeys.get(freq).remove(key);
        if (freqToKeys.get(freq).isEmpty() && minFreq == freq) minFreq++;
        keyToFreq.put(key, freq + 1);
        freqToKeys.computeIfAbsent(freq + 1, k -> new LinkedHashSet<>()).add(key);
    }
}
```

**Extensibility angle they asked for:** wrap eviction policy behind an `EvictionPolicy` interface (`LFUEviction`, `LRUEviction`) so `Cache` doesn't hardcode LFU — same Strategy-pattern instinct as the other answers. **Complexity:** O(1) get/put, O(capacity) space.

---

## 8. Design an In-Memory Key-Value Store (Redis/Memcached-style)

**Core requirements:** O(1) get/set, TTL expiry, eviction under memory pressure, optional persistence, concurrency-safe.

**Design:**
```
Storage layer:  ConcurrentHashMap<String, ValueWrapper>   (ValueWrapper = {value, expiryTimestamp})
Expiry:         Lazy (check on read) + active sweep (background thread scans a sample every N ms — this
                 is literally how Redis does it, mention that explicitly)
Eviction:       Pluggable policy (LRU/LFU/random) once memory limit is hit — Strategy pattern again
Concurrency:    Fine-grained locking (segment locks / ConcurrentHashMap's own striping) rather than a
                 single global lock, to avoid serializing all reads/writes
Persistence:    Optional — periodic snapshot (RDB-style) or append-only log (AOF-style) for durability trade-off
```

**Follow-ups:** "Single-threaded like real Redis, or multi-threaded?" → discuss the trade-off: single-threaded avoids lock contention and race conditions entirely (simpler correctness), multi-threaded gives more raw throughput on multi-core but needs careful locking — Redis's actual answer is single-threaded command execution + separate I/O threads in newer versions, a good namedrop. "How do you scale beyond one machine?" → this is the HLD escalation: consistent hashing to shard keys across nodes, replication for availability.

---

## 9. Design a Notification Service — LLD

**Entities:**

```
Notification         - id, userId, channel (EMAIL/SMS/PUSH), templateId, payload, status, scheduledAt
NotificationTemplate  - id, channel, subject/body template with placeholders
UserPreference        - userId, channel, isEnabled, quietHoursStart/End
NotificationSender (interface) - send(Notification) -> DeliveryResult    → EmailSender, SmsSender, PushSender
NotificationService   - orchestrates: checks preference -> picks sender -> renders template -> sends -> logs
```

```java
interface NotificationSender {
    DeliveryResult send(Notification n);
}
class PushSender implements NotificationSender { /* calls FCM/APNs */ }
class EmailSender implements NotificationSender { /* SMTP/SES */ }

class NotificationService {
    Map<Channel, NotificationSender> senders;   // Factory-style registry, keyed by channel
    UserPreferenceRepository prefs;

    void notify(String userId, String templateId, Map<String,Object> data) {
        for (Channel channel : Channel.values()) {
            UserPreference pref = prefs.get(userId, channel);
            if (pref == null || !pref.isEnabled() || isQuietHours(pref)) continue;
            Notification n = render(templateId, channel, data);
            senders.get(channel).send(n);              // could be async / queued, see HLD version
        }
    }
}
```

**Design pattern to name:** **Factory/Strategy** for `NotificationSender` per channel, **Template Method** for rendering (`NotificationTemplate` with placeholder substitution) — this is what makes adding a new channel (e.g., WhatsApp) a one-class change.

*(For the distributed/high-volume version of this question — Kafka backbone, delivery guarantees, retry — see Part 2, HLD #8.)*

---

## 10. API Design for a "Notify Me" Feature (Amazon-style sale alert)

**Requirements:** user opts in to be notified when a product goes on sale / is back in stock; system notifies them once (or per-configured-frequency) when the condition triggers.

**Entities & schema:**
```sql
notify_subscriptions(id PK, user_id FK, product_id FK, condition ENUM(PRICE_DROP, BACK_IN_STOCK),
                      threshold_price NULLABLE, created_at, status ENUM(ACTIVE, FULFILLED, CANCELLED))
```

**API contract:**
```
POST /v1/products/{productId}/notify-me
  Body: { "condition": "PRICE_DROP", "thresholdPrice": 499.00 }
  201 Created: { "subscriptionId": "...", "status": "ACTIVE" }

DELETE /v1/notify-me/{subscriptionId}
  204 No Content

GET /v1/users/{userId}/notify-subscriptions
  200 OK: [ { subscriptionId, productId, condition, status, createdAt } ]
```

**How it interacts with the existing system (this is what they're really testing):** don't poll — the product/pricing service **emits an event** (`PriceChangedEvent`, `InventoryReplenishedEvent`) on every price/stock update; a `NotifyMeConsumer` subscribes to that event stream, queries `notify_subscriptions` indexed on `(product_id, status='ACTIVE')`, and enqueues notifications for matching subscriptions, then flips them to `FULFILLED`. This decouples the notify-me feature from the core product service entirely — the product service doesn't need to know this feature exists.

**Index:** `notify_subscriptions(product_id, status)` — the consumer's lookup path — and `notify_subscriptions(user_id)` for the "my subscriptions" read.

---

## 11. Design a Vehicle Parking App (product-flavored variant)

This is the same core LLD as #2, with a product lens layered on. Structure your answer as: **core LLD (reuse #2's entities) + three product-layer additions:**
1. **Search/discovery**: geospatial query — "find lots with availability within 2km" via a geo-index (Redis GEO or PostGIS) rather than scanning all lots.
2. **Reservation ahead of time** (not just walk-in): adds a `Reservation` entity with a hold window, same seat-lock-style concurrency pattern as BookMyShow (#4) — reuse that reasoning explicitly, it shows pattern-recognition across problems.
3. **Payment integration**: idempotent payment capture on exit, same idempotency-key pattern as Part 3's payments discussion.

---

## 12. "LLD for the Human Mouth" (the deliberately odd one)

This question has no "correct" answer — they're testing whether you can model an unfamiliar real-world object from scratch without a memorized template. **Approach out loud:**
> "Let me identify the entities first: `Mouth` composed of `Teeth` (a collection, each with a type — incisor/canine/molar — and a state — healthy/decayed/missing), `Tongue`, `Gums`, `Jaw` (upper/lower). Behaviors: `chew(food)`, `speak(sound)`, `Tooth.decay()` as a state transition. I'd model `Tooth` with a `Condition` enum and a `treat(Procedure)` method so a dentist-facing system could log procedures per tooth."

The point isn't the exact model — it's demonstrating: **entity identification → attributes → behaviors → relationships (composition: Mouth *has-a* collection of Teeth)**, calmly, without needing a familiar template. Say this framing explicitly if you get any unfamiliar object — it turns confusion into a demonstrated process.

---

## 13. Word Guess Game (Wordle) — Machine Coding

**Requirements:** 5-letter target word, N guesses, per-letter feedback (correct position / wrong position / absent), win/lose detection.

```java
enum LetterStatus { CORRECT, PRESENT, ABSENT }

class WordGuessGame {
    String targetWord;
    int maxAttempts;
    int attemptsUsed = 0;

    List<LetterStatus> guess(String guess) {
        if (guess.length() != targetWord.length()) throw new IllegalArgumentException("length mismatch");
        attemptsUsed++;
        List<LetterStatus> result = new ArrayList<>(Collections.nCopies(guess.length(), LetterStatus.ABSENT));
        Map<Character, Integer> remaining = new HashMap<>();
        for (char c : targetWord.toCharArray()) remaining.merge(c, 1, Integer::sum);

        // pass 1: exact matches first (so duplicate letters don't get double-counted as PRESENT)
        for (int i = 0; i < guess.length(); i++) {
            if (guess.charAt(i) == targetWord.charAt(i)) {
                result.set(i, LetterStatus.CORRECT);
                remaining.merge(guess.charAt(i), -1, Integer::sum);
            }
        }
        // pass 2: present-but-wrong-position
        for (int i = 0; i < guess.length(); i++) {
            char c = guess.charAt(i);
            if (result.get(i) == LetterStatus.CORRECT) continue;
            if (remaining.getOrDefault(c, 0) > 0) {
                result.set(i, LetterStatus.PRESENT);
                remaining.merge(c, -1, Integer::sum);
            }
        }
        return result;
    }

    boolean isWon(List<LetterStatus> result) { return result.stream().allMatch(s -> s == LetterStatus.CORRECT); }
    boolean isGameOver() { return attemptsUsed >= maxAttempts; }
}
```

**The classic bug interviewers probe for:** naive single-pass implementations mark duplicate letters as `PRESENT` incorrectly when the target has fewer occurrences than the guess — the two-pass approach above (exact matches consumed first) is the fix; **volunteer this edge case unprompted**, it's the signal they're looking for.

**Follow-ups:** "keyboard state" → maintain a `Map<Character, LetterStatus>` updated after each guess (never downgrade CORRECT to PRESENT/ABSENT if seen again). "Make it extensible for a 6-letter or emoji-based variant" → parametrize word length and character-equality function rather than hardcoding.

---

# PART 2 — Backend HLD (Full Answers)

## 1. Design a Scalable Distributed Web Crawler

**Clarify:** Scale target (pages/day)? Politeness constraints (robots.txt, rate limits per domain)? Freshness requirement (recrawl frequency)? Assume: billions of pages, must respect robots.txt and per-domain rate limits, weekly recrawl for most pages.

**Architecture:**
```
Seed URLs → URL Frontier (priority + politeness queues, partitioned by domain)
              │
              ▼
     Parallel Fetcher Workers (one worker pool, but each domain gets its own rate-limited lane)
              │
      ┌───────┼────────────┐
      ▼       ▼             ▼
  DNS Cache  Robots.txt   Fetch (HTTP client, timeout + retry)
  Cache                        │
                                ▼
                        Dedup Filter (Bloom filter on URL hash — catches most dupes cheaply,
                                       backed by exact-match store for the rare false positive)
                                │
                                ▼
                        Content Parser (extract links, text, metadata)
                          │                    │
                          ▼                    ▼
                  New URLs → back to      Storage (S3/blob) + Indexer (Elasticsearch)
                  Frontier (if not
                  already crawled)
```

**Key design decisions to narrate:**
- **URL Frontier is partitioned by domain**, not a single global queue — this is what makes politeness (rate-limiting per domain, e.g., 1 req/sec to a given site) tractable: each domain's queue has its own token-bucket rate limiter, so one slow/rude domain never starves others.
- **Priority within the frontier**: higher-priority URLs (e.g., high PageRank, frequently-updated pages) get dequeued more often — a weighted queue or multiple priority tiers.
- **Deduplication at two levels**: URL-level (don't fetch the same URL twice) via Bloom filter for cheap approximate checks, and **content-level** (different URLs, same content — mirrors, tracking-parameter variants) via a content hash (SimHash/MinHash for near-duplicate detection, not just exact hash).
- **Fault tolerance**: each fetch is idempotent and retryable; frontier state (what's pending/in-progress/done) is persisted (not in-memory only) so a worker crash doesn't lose progress — a URL "checked out" by a worker gets a lease with a timeout, and if not marked complete before the lease expires, it's returned to the frontier (same lease pattern as SQS visibility timeout).
- **Scaling**: horizontally scale fetcher workers independently of the frontier service; frontier itself can be sharded by domain hash across multiple frontier nodes.

**Follow-ups:**
- *"How do you avoid crawler traps (infinite URL generation, e.g., calendar pages)?"* → cap max depth per domain, detect URL-pattern loops (same path template with incrementing params), and a per-domain crawl budget.
- *"How do you handle robots.txt changing mid-crawl?"* → cache robots.txt per domain with a TTL (e.g., 24h), re-fetch on expiry, never crawl past a `Disallow` even if already queued.
- *"Storage at this scale?"* → raw HTML to blob storage (S3) partitioned by crawl-date/domain-hash, extracted structured data to a document store, search index (Elasticsearch) built asynchronously from the parsed output, not synchronously in the crawl path.

---

## 2. Design Zomato / a Food Delivery Platform — HLD

**Non-functional requirements to state up front:** read-heavy (menu browsing >> order placement), real-time order tracking, high availability > strict consistency for browsing, strong consistency required only at the payment/order-confirmation step.

**Architecture:**
```
Client (App/Web)
    │
    ▼
API Gateway (auth, rate limiting, routing)
    │
    ├──► Restaurant/Menu Service ──► PostgreSQL (sharded by city_id) + Redis cache (menu, hot restaurants)
    │
    ├──► Search/Discovery Service ──► Elasticsearch (geo-query: restaurants near me, filters)
    │
    ├──► Order Service ──► PostgreSQL (sharded by user_id) ──► emits OrderPlaced to Kafka (partitioned by city_id)
    │                                                                  │
    ├──► Payment Service ──► idempotent capture, PCI-compliant vault   │
    │                                                                  ▼
    └──► Delivery Assignment Service ◄──── consumes OrderPlaced ── Kafka
              │ (nearest available partner, greedy or batched assignment)
              ▼
         Real-Time Tracking (WebSocket gateway, partner location pushed via Redis pub/sub
                              or a dedicated location-ingest service writing to a geo-index)
```

**Database choice, stated with justification:** PostgreSQL (relational) for orders/restaurants/users — the data is inherently relational (orders reference restaurants, users, items) and needs transactional guarantees at order-placement time; Elasticsearch for search/discovery because geo + text + filter queries are its strength, not Postgres's; Redis for hot-path caching (menu reads, live partner locations) because that traffic is 10-100x order-placement traffic and shouldn't hit the primary DB.

**Sharding:** `restaurants`/`menu_items` by `city_id` (locality of access — see Part 1 #3 reasoning, reuse it verbatim if asked again in this round), `orders` by `user_id`.

**Event-driven architecture — why Kafka specifically:** order placement, delivery assignment, and notification are naturally decoupled steps that shouldn't block each other synchronously — if notification service is slow, it shouldn't delay delivery assignment. Kafka topic `orders.placed` partitioned by `city_id` lets delivery-assignment consumers scale per-region independently, and gives replayability (re-run assignment logic against historical events for testing/debugging).

**Real-time tracking:** delivery partner app pushes location every N seconds → ingest service writes to Redis (keyed by `partner_id`, short TTL) → WebSocket gateway holds a connection per active order's customer and pushes location diffs, sourced from Redis, not the primary DB (avoids hammering Postgres with a write-heavy, ephemeral data stream).

**Failure handling to volunteer:** if delivery-assignment fails to find a partner within an SLA (e.g., 3 min), auto-widen the search radius, then escalate to ops if still unassigned — never let an order sit in silent limbo; if the payment step fails after order creation, the order enters a `PAYMENT_FAILED` state via the same outbox/event pattern discussed in the BookMyShow answer — never leave inconsistent partial state.

---

## 3. Design a Booking.com-Style Hotel Reservation System

**Core hard problem: preventing overbooking under concurrency, at scale, across a search-then-book flow with a time gap.**

**Architecture:**
```
Search Service (denormalized, cached availability index — Elasticsearch or a materialized view,
                refreshed near-real-time, allowed to be *slightly* stale)
      │
      ▼
Booking Service ──► Inventory Service (source of truth, strongly consistent)
      │                    │
      ▼                    ▼
 Payment Service      inventory table: (room_type_id, date, total_rooms, booked_rooms)
                       with a CHECK constraint: booked_rooms <= total_rooms
```

**Why search and booking are separate consistency domains:** search needs to be **fast and highly available** — millions of read-only availability checks — so it's fine to serve from a cache that's a few seconds stale. Booking (the actual write) must be **strongly consistent** — this is the classic read-your-own-writes vs. eventual-consistency split, and naming it explicitly is the senior signal.

**Preventing overbooking — the actual mechanism:**
```sql
-- Atomic decrement with a guard, in one statement — no read-then-write race window
UPDATE inventory
SET booked_rooms = booked_rooms + 1
WHERE room_type_id = ? AND date = ? AND booked_rooms < total_rooms;
-- if rows_affected == 0 → sold out, fail the booking immediately, no lock held
```
This avoids a separate `SELECT` + application-level check (a classic TOCTOU race under concurrency) by making availability-check-and-decrement a single atomic DB operation. For a date range (multi-night stay), do this per date within one DB transaction, and if any date fails, roll back the whole transaction — a partial booking (confirmed for 2 of 5 nights) is a worse failure mode than a full rejection.

**Follow-ups:**
- *"What about the gap between search results and the user clicking 'book' 10 minutes later?"* → this is inherent optimistic concurrency — the atomic UPDATE above is the actual gate; the search result was always advisory, never a hold. Optionally add a short soft-hold (like the BookMyShow seat lock) if the product wants to guarantee availability for a checkout flow.
- *"Scale reads massively above writes"* → CQRS-style split: all availability reads go through the cached/search path, only the final booking confirmation touches the strongly-consistent inventory table — this keeps the hot read path off the write-critical system entirely.

---

## 4. Design a System for a Continuous Data Stream

**Clarify:** what kind of stream (IoT sensor data, clickstream, logs)? Real-time processing needed, or just durable ingestion? Assume: high-volume events needing both real-time alerting and durable storage for later analytics.

**Architecture:**
```
Producers → Ingestion Gateway (validates, batches) → Kafka (partitioned by a natural key, e.g., device_id)
                                                          │
                                    ┌─────────────────────┼─────────────────────┐
                                    ▼                     ▼                     ▼
                          Stream Processor          Cold Storage           Real-time Alerting
                          (Flink/Kafka Streams,      (S3/data lake,         Consumer (evaluates
                          windowed aggregation)       via a sink connector)  rules per event,
                                    │                                        fires on threshold breach)
                                    ▼
                          Aggregated Metrics Store
                          (TimescaleDB / Druid — optimized for time-range queries)
```

**Key decisions:** Kafka as the durable, replayable backbone (decouples producers from however many consumers need the same data — storage, alerting, aggregation all read independently); partition key chosen for the dominant query pattern (per-device analysis → partition by `device_id`, ensures ordering per device without needing global ordering); backpressure handled by Kafka's own consumer-lag mechanism rather than the ingestion gateway blocking — if a consumer falls behind, it just processes a longer backlog, producers are never blocked.

**Follow-ups:** "exactly-once processing?" → Kafka transactions + idempotent producers, or design consumers to be naturally idempotent (upsert by event ID) so at-least-once delivery is safe — cheaper and more robust than chasing true exactly-once. "How do you handle a burst 10x normal volume?" → Kafka absorbs the burst durably (it's just a longer queue), consumers auto-scale (more partitions + more consumer instances) to catch up, versus a system without a buffer where a burst directly overloads processing.

---

## 5. Design a URL Shortener

**Architecture:**
```
POST /shorten {longUrl} → ID Generator (base62 encode of an auto-incrementing/Snowflake ID,
                                          or a pre-generated pool of unique keys to avoid a
                                          hot counter under high write concurrency)
                          → write to DB: (short_code PK, long_url, created_at, expiry, click_count)
                          → write-through to cache (Redis) since new links are often clicked soon after creation

GET /{shortCode} → check Redis cache first → fallback to DB on miss → 301/302 redirect
```
**Key decisions:** base62 encoding of a numeric ID keeps short codes compact (7 chars covers 62^7 ≈ 3.5 trillion); DB is the source of truth, Redis is a read-through cache with high hit rate since link access is heavily skewed toward recently-created/viral links (classic Zipfian access pattern — name this explicitly); 301 (permanent) vs 302 (temporary) redirect trade-off: 302 lets you keep click-analytics server-side (browser re-hits your server each time), 301 gets cached by the browser (faster for the user, but you lose click tracking) — state this trade-off, it's a common follow-up.

**Scale numbers to volunteer:** at 100M new URLs/day and 10:1 read:write ratio, reads dominate → cache hit rate is the main lever, not DB write throughput.

---

## 6. Design a Rate Limiter

**Algorithm comparison to state up front:**

| Algorithm | Behavior | Trade-off |
|---|---|---|
| Fixed window counter | Reset counter every fixed interval | Simple, but allows 2x burst at window boundary |
| Sliding window log | Store timestamp of every request | Accurate, but memory-heavy at scale |
| Sliding window counter | Weighted average of current + previous window | Good accuracy/memory balance — most commonly recommended |
| Token bucket | Tokens refill at fixed rate, request consumes a token | Allows controlled bursts, smooth average rate — my default choice |
| Leaky bucket | Requests processed at fixed rate via a queue | Smooths bursts completely, adds latency |

**My pick and why:** Token bucket for most API rate-limiting — it allows short bursts (good UX for legitimate clients) while enforcing a long-term average rate, and it's O(1) to check.

**Distributed implementation (this is the actual system-design meat):**
```
Redis, per key = "ratelimit:{userId}:{endpoint}"
Lua script (atomic, avoids race condition between check-and-decrement across app instances):

  local tokens = redis.call('GET', key)
  if tokens == false then tokens = capacity end
  if tokens > 0 then
    redis.call('DECR', key)
    redis.call('EXPIRE', key, refill_interval)
    return 1  -- allowed
  else
    return 0  -- rejected
  end
```
**Why Redis + Lua, not app-local counters:** app servers are horizontally scaled and stateless — a local in-memory counter per instance would let a user get N-times the limit by hitting N different instances. Redis is the shared source of truth, and the Lua script makes the check-and-decrement atomic (avoiding a race where two concurrent requests both read "1 token left" and both proceed).

**Follow-ups:** "Per-user vs per-IP vs global limits?" → layer all three, checked in order (global system-protection limit first, cheapest to check, then per-user business limit). "What happens when Redis is down?" → fail-open (allow requests, log/alert) vs fail-closed (reject all) is a genuine product decision — I'd default to fail-open for availability unless the endpoint is specifically abuse-prone (e.g., login), where fail-closed is safer.

---

## 7. Design a Real-Time Stock Price Update System

**Push vs. pull, stated explicitly:** Pull (client polls) doesn't scale for real-time feel and wastes bandwidth on unchanged prices; Push (WebSocket/SSE) is the right model for "real-time."

**Architecture:**
```
Market Data Feed → Ingestion Service → normalizes ticks → publishes to Kafka (partitioned by symbol)
                                                                │
                                                    Price Aggregator (dedups, computes OHLC candles)
                                                                │
                                                    Redis Pub/Sub (or a fan-out layer)
                                                                │
                                            WebSocket Gateway (holds client connections,
                                            subscribes to symbols the client is watching,
                                            pushes only diffs — not full snapshots — per tick)
```
**Fan-out problem to name explicitly:** a popular symbol (e.g., a hot stock) might have 100K clients watching it simultaneously — a naive "one Redis pub/sub message per subscriber" doesn't scale linearly forever, so at very high fan-out you'd shard the WebSocket gateway itself by symbol (all clients watching AAPL connect to gateway shard N) so a single price update fans out within one process's connection pool rather than crossing the network per subscriber.

**Trade-off to volunteer:** true tick-by-tick push to every client is expensive at scale; a common real-world compromise is **throttled push** (e.g., max 1 update/second per symbol per client) since human perception can't distinguish updates faster than that anyway — cheap way to cut load without hurting UX.

---

## 8. Design a Notification Service — HLD (high volume, distributed)

**Architecture:**
```
Trigger Events (order shipped, price drop, etc.) → Kafka topic "notifications.requested"
                                                          │
                                          Notification Orchestrator (consumes, checks user
                                          preferences + quiet hours, renders template)
                                                          │
                        ┌─────────────────┬───────────────┴───────────────┐
                        ▼                 ▼                               ▼
                Email Queue (SQS)   SMS Queue (SQS)                Push Queue (SQS)
                        │                 │                               │
                 Email Worker       SMS Worker                     Push Worker
                 (SES/SendGrid)     (Twilio)                       (FCM/APNs)
```
**Why per-channel queues, not one shared queue:** each channel has a different failure mode, rate limit, and vendor SLA (SMS is expensive and rate-limited by carrier, push is cheap and high-volume) — isolating them means an SMS vendor outage doesn't back up push notifications.

**Delivery guarantees — state the trade-off explicitly:** at-least-once delivery (retry on failure) is the practical default — exactly-once is expensive to guarantee end-to-end (would need dedup at the vendor's inbox, which you don't control) — so instead make the **notification content idempotent to re-delivery** (a duplicate "your order shipped" push is annoying but harmless) and reserve stricter guarantees only for cases where duplicates cause real harm.

**Follow-ups:** "How do you avoid spamming a user with 20 notifications in a minute?" → a per-user notification-rate limiter (token bucket again) plus digest/batching logic for low-priority notification types. "Scheduling for future delivery (e.g., 'notify at 9am local time')?" → a delayed-queue mechanism (SQS delay queues, or a scheduled-job table polled by a worker) rather than holding messages in application memory.

---

## 9. Design a Chat Application

**Architecture:**
```
Client ──WebSocket──► Connection Gateway (stateful, holds the socket; horizontally scaled,
                        connection registry in Redis: user_id -> gateway_instance_id)
                              │
                              ▼
                        Message Service ──► Message Store (Cassandra/DynamoDB — write-heavy,
                        │                     partitioned by conversation_id, sorted by timestamp)
                              │
                              ▼
                        Fan-out: look up recipient's gateway instance via Redis registry,
                        forward message to that instance (or queue if recipient offline)
                              │
                        If recipient offline → push notification + store as unread,
                        deliver on next connect (client fetches "since last seen")
```
**Why the connection registry matters:** in a horizontally-scaled WebSocket gateway, sender and recipient may be connected to *different* gateway instances — a message can't just be "written to a socket" locally; you need a way to route it to whichever instance holds the recipient's live connection (Redis pub/sub between gateway instances, or a dedicated routing layer), otherwise the design silently only works on a single instance.

**Group chat fan-out:** for small groups, fan-out-on-write (deliver to every online member's gateway immediately) is fine; for very large groups/channels (thousands of members), fan-out-on-read is better (store the message once, each client pulls it when it opens the conversation) to avoid an O(members) write amplification per message — this is the same fan-out trade-off as a social media feed, worth naming the parallel.

**Follow-ups:** "long-polling fallback?" → for clients/networks that can't hold WebSocket connections (corporate proxies), fall back to HTTP long-polling with the same message-queue-per-user abstraction underneath, so the application logic doesn't need to know which transport is in use. "Message ordering guarantees?" → per-conversation ordering via a monotonic sequence number written at the message-store layer, not relying on network-arrival order.

---

## 10. Design an HLD for a Franchise-Based Application (agents sell products)

**Roles & permissions:** `Admin` (full access), `FranchiseOwner` (manage their franchise's agents/inventory), `Agent` (sell products, view own commission) — implement via **RBAC**: a `Role` → `Permission[]` mapping checked at the API gateway/middleware layer, not scattered `if role == X` checks through business logic.

**Core services:** Product/Inventory Service (per-franchise stock), Sales Service (records a sale, computes commission via a `CommissionStrategy` — Strategy pattern again, since commission rules vary by product/agent tier), Geolocation Service (assign leads/territory to the nearest agent — geo-index lookup, same pattern as the parking-lot/food-delivery discovery problem).

**Follow-up:** "How do you prevent an agent from seeing another agent's commission data?" → row-level authorization at the query layer (every query scoped by `agent_id` derived from the authenticated session, never accepted as a client-supplied parameter) — a classic IDOR (insecure direct object reference) vulnerability to explicitly call out that you're guarding against.

---

## 11. RESTful PATCH API Design (JSON Patch)

**The question is really: "do you understand PATCH semantics vs. PUT, and can you design a safe partial-update contract?"**

```
PATCH /v1/orders/{orderId}
Content-Type: application/json-patch+json

[
  { "op": "replace", "path": "/status", "value": "CANCELLED" },
  { "op": "add", "path": "/tags/-", "value": "customer_requested" }
]
```
**Key points to state:**
- **PUT replaces the whole resource; PATCH applies a partial, ordered set of operations** (RFC 6902 JSON Patch: `add`, `remove`, `replace`, `move`, `copy`, `test`) — don't conflate them.
- **Idempotency:** PATCH is *not* inherently idempotent the way PUT is — `{"op": "add", "path": "/tags/-", "value": "x"}` applied twice appends twice. If idempotency matters (retries), either use `replace` semantics for that field, or require an `Idempotency-Key` header the server dedupes on (same pattern as the payments idempotency discussion).
- **Concurrency control:** support `If-Match` with an ETag/version field so a PATCH based on stale data is rejected (412 Precondition Failed) rather than silently overwriting a concurrent update — this is the optimistic-concurrency answer they're fishing for.
- **Validation:** the `"test"` op in JSON Patch lets the client assert a precondition (e.g., "only apply if `status` is currently `PENDING`") atomically as part of the same patch document — a good detail to volunteer, shows RFC-level familiarity, not just "PATCH updates some fields."

---

## 12. API Latency Debugging Without Code Changes

**Framework — this tests operational/observability instinct, not code-reading. Walk the request path top to bottom, cheapest checks first:**

1. **Is it universal or specific?** Check if latency is across all endpoints (infra-wide: DB, network) or one endpoint (query/logic-specific) — segment by endpoint in your APM dashboard first.
2. **Client vs. server time.** Split P99 into network time vs. server processing time (most APMs do this natively) — rules out "it's actually a client-side/CDN issue."
3. **DB layer**: check slow-query logs, look for a missing index (a query that used to be fast can regress silently if a table grew past where a sequential scan becomes expensive), check connection-pool saturation (all connections busy → requests queue waiting for a DB connection, looks like "the app is slow" but it's actually pool exhaustion).
4. **Cache layer**: check cache hit rate — a sudden drop (cache eviction storm, cold cache after a deploy, TTL misconfiguration) pushes traffic to the DB and looks exactly like a DB slowdown.
5. **Downstream dependencies**: check if a third-party API call in the request path has degraded — distributed tracing (a trace_id spanning the whole request) tells you which hop is actually slow, without touching code.
6. **Infra-level**: CPU/memory/GC pauses on the app servers (a long GC pause looks identical to "random P99 spikes" — this is literally the root cause from your own Goldman Sachs VaR-latency story, a good one to reuse here if it comes up), noisy-neighbor on shared infra, autoscaling lag during a traffic spike.
7. **Config-only fixes to propose without code changes**: bump connection pool size, add/adjust a cache TTL, add a missing DB index (a schema change, not application code), scale out replicas, adjust a timeout that's causing retries/cascading load.

**The framing line to close with:** "The key discipline is instrument-then-diagnose, not guess-then-fix — my instinct from a similar production latency issue I debugged was that the top suspect (usually the cache or a specific query) is wrong more often than people expect; the trace/dashboard should tell you, not intuition."

---

# PART 3 — Backend Fundamentals (Guaranteed Follow-Ups)

## Kafka: "Explain how Kafka works" + "producer publishes but consumer isn't receiving — debug it"

**Core components, stated concisely:** Producer → Topic (split into Partitions, each an ordered, append-only log) → Broker (stores partitions, replicated across a cluster) → Consumer (in a Consumer Group; each partition is consumed by exactly one consumer within a group, enabling parallelism) → Offset (per-partition position each consumer group has read up to, committed back to Kafka).

**Debugging "producer publishes, consumer doesn't receive" — walk the checklist out loud:**
1. **Confirm the message actually landed** — check broker-side topic metrics/consumer-offset lag tools (`kafka-consumer-groups.sh --describe`) to see if the topic's log end offset advanced at all. If not, it's a producer-side problem (check producer `acks` config, check for silent send failures if not handling the callback/future).
2. **Check consumer group state** — is the consumer actually part of the group, or did it fall out due to a `session.timeout.ms` expiry (processing took too long between polls) and trigger a rebalance, leaving no consumer assigned to that partition temporarily?
3. **Check offset position** — if the consumer group's committed offset is already past the message (e.g., a config change reset it to `latest` after a restart, skipping backlog), it'll never see messages it considers "old."
4. **Check partition assignment** — in a multi-consumer group, is this specific partition assigned to a *different* consumer instance than the one you're checking logs on?
5. **Check ACLs/authorization** — a permission issue on the consumer can silently fail to fetch depending on client configuration/error handling.
6. **Check consumer-side exceptions** — is the consumer's poll loop throwing and dying silently (no supervisor/restart), effectively meaning "the consumer app is down" while looking alive at the process level?

## Kafka delivery semantics: at-least-once vs. at-most-once vs. exactly-once

- **At-most-once**: commit offset *before* processing — if the process crashes mid-processing, that message is lost, never reprocessed. Rarely what you want.
- **At-least-once**: process *then* commit offset — if the process crashes after processing but before committing, the message is reprocessed on restart. **This is the default you should reach for**, combined with idempotent consumers (see below) to make duplicates harmless.
- **Exactly-once**: Kafka transactions (`enable.idempotence=true` + transactional producer/consumer) guarantee no duplicates *within Kafka*, but true end-to-end exactly-once (including side effects like a DB write or an external API call) requires the consumer's write to be part of the same transaction or otherwise idempotent — exactly-once is a Kafka-internal guarantee, not a magic wand for your whole pipeline. State this nuance explicitly — it's the differentiator between a surface-level and a deep answer.

## Idempotency in Payment Retries (near-guaranteed if payments/Kafka is on your resume)

> This is the exact scenario already fully worked out in your `docs/EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md` duplicate-payout incident — reuse it directly, it's a real, quantified story.

**The answer, structured:**
1. **Bind the idempotency key to business intent, not transport.** A retry from a client (or an upstream service retrying due to a timeout) generates a *new* HTTP request ID, but represents the *same* business payment intent. The idempotency key must be something the client derives deterministically from the business operation (e.g., `payment_intent_id`, or a client-generated UUID passed explicitly and reused on retry) — never the server-generated request ID.
2. **Persist the idempotency record atomically with the side effect** — in the same DB transaction that debits the balance, insert into an `idempotency_keys(key UNIQUE, result, created_at)` table. If a retry arrives with the same key, look it up first: if found, return the *stored result* without re-executing the debit — don't just check-then-act (race condition), let the DB's unique constraint be the actual guard.
3. **Handle the in-flight race**: if a retry arrives while the *first* request is still processing (not yet committed), the second request should either block briefly (short lock on the key) or return a `409/425` "processing, retry shortly" rather than proceeding — a classic double-submit race window.
4. **TTL the idempotency record** appropriately (e.g., 24h) — long enough to cover realistic retry windows, short enough not to grow unboundedly.

## Java Concurrency: threads, race conditions, deadlocks (expect production-flavored questions)

- **Race condition example to have ready:** two threads incrementing a shared counter without synchronization — `count++` is not atomic (read-modify-write), so under concurrency you lose increments. Fix: `AtomicInteger`, or `synchronized`, or a `ConcurrentHashMap`'s atomic compute methods depending on context.
- **Deadlock — the classic 4 conditions** (mutual exclusion, hold-and-wait, no preemption, circular wait) and **the practical fix they want to hear**: **consistent lock ordering** — if every thread that needs locks A and B always acquires them in the order A-then-B, circular wait becomes impossible. Alternatives: `tryLock` with a timeout (back off and retry instead of blocking forever), or redesign to avoid needing multiple locks at once (e.g., a single lock covering the whole critical section, or lock-free structures).
- **Production debugging strategy to mention**: thread dumps (`jstack`) to see which threads are `BLOCKED` and on what monitor, to identify the actual lock-ordering conflict in a live deadlock.
- **Concurrent collections** to namedrop when relevant: `ConcurrentHashMap` (segment/bucket-level locking, not a single global lock), `CopyOnWriteArrayList` (read-heavy, rare-write lists), `BlockingQueue` implementations for producer-consumer patterns.

## ACID Properties & SQL vs. NoSQL

- **Atomicity**: a transaction is all-or-nothing. **Consistency**: a transaction moves the DB from one valid state to another (constraints/invariants hold). **Isolation**: concurrent transactions don't see each other's intermediate state (isolation levels: Read Uncommitted → Serializable trade consistency for throughput). **Durability**: once committed, survives a crash (WAL/fsync).
- **SQL vs. NoSQL, framed as a decision, not a preference:** choose SQL when data is relational, you need multi-row transactional guarantees, and the schema is relatively stable (payments, orders, inventory — anywhere correctness matters more than raw write throughput). Choose NoSQL when you need horizontal write scale beyond what a single relational primary can give you, the access pattern is simple key-based lookups or wide-column time-series (chat messages, event logs, session data), or the schema is genuinely variable/evolving fast. **The answer that shows seniority**: "most real systems use both, for different tables/services, not one dogmatic choice for the whole system" — this is exactly the pattern in the Food Delivery / Zomato HLD answer above (Postgres + Elasticsearch + Redis together).

---

# PART 4 — Frontend LLD/HLD (Full Answers)

## Machine Coding: Stopwatch, Dynamic Grid, Polyfills

**Stopwatch (React):**
```jsx
function Stopwatch() {
  const [elapsedMs, setElapsedMs] = useState(0);
  const [running, setRunning] = useState(false);
  const intervalRef = useRef(null);
  const startTimeRef = useRef(0);

  useEffect(() => {
    if (running) {
      startTimeRef.current = Date.now() - elapsedMs;
      intervalRef.current = setInterval(() => {
        setElapsedMs(Date.now() - startTimeRef.current);
      }, 100);
    }
    return () => clearInterval(intervalRef.current);   // cleanup — the detail interviewers check for
  }, [running]);

  return (
    <div>
      <span>{formatTime(elapsedMs)}</span>
      <button onClick={() => setRunning(r => !r)}>{running ? 'Pause' : 'Start'}</button>
      <button onClick={() => { setRunning(false); setElapsedMs(0); }}>Reset</button>
    </div>
  );
}
```
**Why `Date.now()`-based, not a naive `+= 100` counter:** `setInterval` isn't guaranteed to fire exactly every 100ms under event-loop pressure — computing elapsed time from a fixed start timestamp avoids drift accumulating over a long-running stopwatch. Volunteer this.

**Dynamic Grid Component:** render `rows × cols` efficiently — key point to volunteer: **avoid inline function/object creation inside the render loop** (causes unnecessary re-renders of every cell), memoize cell components with `React.memo`, and for very large grids mention **virtualization** (`react-window`/`react-virtualized`) so you only render visible rows, not all of them — this is the scalability answer they're listening for on "make it efficient."

**Polyfills — the two most commonly asked, know both cold:**
```js
Function.prototype.myBind = function(context, ...boundArgs) {
  const fn = this;
  return function(...callArgs) {
    return fn.apply(context, [...boundArgs, ...callArgs]);
  };
};

function debounce(fn, delay) {
  let timer;
  return function(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), delay);
  };
}
```

## Frontend "HLD": Optimize tekion.com Performance (asked almost verbatim across reports)

**Structure the answer around the metrics, not a grab-bag of tricks:**

| Metric | What it measures | Fixes to mention |
|---|---|---|
| **LCP** (Largest Contentful Paint) | Time to render the biggest visible element | Preload critical hero image/font, SSR the above-the-fold content, use a CDN, optimize/compress images (WebP/AVIF) |
| **CLS** (Cumulative Layout Shift) | Visual stability | Reserve explicit width/height for images/ads before they load, avoid injecting content above existing content |
| **TBT** (Total Blocking Time) | Main-thread blocking from JS | Code-splitting (route-based + component-based lazy loading), defer non-critical JS, break up long tasks |
| **API calls** | Network-bound delays | Batch/parallelize independent calls, cache responses (SWR/React Query with stale-while-revalidate), avoid waterfalls (don't fetch B only after A resolves if they're independent) |

**Architecture-level answer, not just a checklist:** "I'd start by profiling with Lighthouse/WebPageTest to find the actual bottleneck rather than guessing, then prioritize: (1) SSR or static generation for the initial page so LCP doesn't wait on client-side JS execution, (2) code-split by route so the initial bundle is minimal, (3) a CDN + aggressive caching headers for static assets, (4) React Query/SWR for API response caching so navigating back to a page doesn't re-fetch, (5) image optimization pipeline (responsive `srcset`, lazy-load below-the-fold images)." This ordered, metric-driven structure is what separates this answer from a list of buzzwords.

## Flipkart Search Bar (pagination, throttling, autocomplete)

**Requirements:** as-you-type suggestions, debounced to avoid a request per keystroke, paginated results, cancel stale in-flight requests.

```jsx
function SearchBar() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const abortControllerRef = useRef(null);

  const debouncedSearch = useMemo(() => debounce(async (q) => {
    abortControllerRef.current?.abort();               // cancel the previous in-flight request
    const controller = new AbortController();
    abortControllerRef.current = controller;
    try {
      const res = await fetch(`/api/search?q=${q}`, { signal: controller.signal });
      setResults(await res.json());
    } catch (e) {
      if (e.name !== 'AbortError') console.error(e);
    }
  }, 300), []);

  useEffect(() => { if (query) debouncedSearch(query); }, [query]);

  return <input value={query} onChange={e => setQuery(e.target.value)} />;
}
```
**Points to volunteer explicitly:**
- **Debounce (not throttle)** for as-you-type search — you want to wait for the user to pause typing, not fire at a fixed cadence while they're still typing.
- **Abort stale requests** — without this, a fast typer can get an out-of-order response (an earlier, slower request resolving *after* a later one) rendering wrong/stale results — a real bug class, name it explicitly.
- **Pagination**: infinite scroll (IntersectionObserver-triggered fetch of next page) vs. classic page numbers — trade-off is UX preference vs. ability to deep-link to a specific page.
- **Backend-side**: this pairs with the URL-shortener-style prefix/trie or an Elasticsearch "search-as-you-type" analyzer for the autocomplete backend — mention it briefly to connect frontend and backend halves of the answer.

## LinkedIn Notification Section (verbal/architecture)

**Key points:** unread-count badge synced via a lightweight polling or WebSocket push (same pattern as chat app's connection registry, smaller scale); notification list paginated and grouped (e.g., "5 people liked your post" aggregation rather than 5 separate rows — mention a grouping/aggregation window, e.g., bucket same-type notifications within a 1-hour window into one entry); mark-as-read is an optimistic UI update (update locally immediately, sync to server async, reconcile on failure).

---

# PART 5 — Product-Flavored Design Questions (PM/senior rounds, brief)

**"Design a vehicle parking app" (product framing):** structure the answer as user journey → feature list → prioritization, not architecture: discovery (find + filter nearby lots), real-time availability, advance reservation, payment, ratings/reviews, push notifications for reminders — then say "I'd prioritize discovery + payment as MVP, defer advance reservation and social features to v2" — a product-thinking answer shows prioritization judgment, not just a feature dump.

**"Design an ad sales campaign":** target audience definition → channel selection (justify each) → creative/messaging → budget allocation → success metrics (CTR, CAC, conversion) → iteration loop (A/B testing). Keep it structured as a funnel, not a list of tactics.

**"Design an app for people living in societies":** community bulletin board, event planning/RSVP, resource/amenity booking (clubhouse, parking), visitor management, complaint/maintenance ticketing — again, close with an explicit MVP-vs-later prioritization statement; that's the part that actually differentiates candidates in these rounds.

---

## Final Delivery Checklist (read this right before you go in)

- [ ] For every design question: **requirements → entities → design pattern named explicitly → code/schema → concurrency/scale follow-up you raise yourself.**
- [ ] Never silently pick SQL/NoSQL, sharding key, or a data structure — always say the trade-off, even for the "obvious" choice.
- [ ] If you don't know a follow-up cold, reason out loud from first principles rather than going silent — several source reports explicitly praised "structured thinking" over "the right answer."
- [ ] Reuse your own real production stories (Kafka, idempotency, concurrency, production incidents) wherever a question touches them — you have real, quantified experience for exactly this material in your other prep docs.
- [ ] Keep each answer to ~5–8 minutes of talking before pausing for interviewer direction — don't over-design past what they've asked.

---

*Paired with `TEKION-LLD-HLD-QUESTIONS-BANK.md` (source questions + citations) and reuses story material from `EM-AMBIGUITY-QUALITY-INTERVIEW-PREP.md` (idempotency/incident stories) and `docs/ai/AI-ML-EM-STAFF-INTERVIEW-PREP.md` (production monitoring/incident framing) already in this repo.*
