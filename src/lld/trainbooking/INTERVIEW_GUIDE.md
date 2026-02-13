# Train Booking System - Interview Guide

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Clarifying Questions](#clarifying-questions)
3. [Requirements Gathering](#requirements-gathering)
4. [Core Entities & Relationships](#core-entities--relationships)
5. [API Design](#api-design)
6. [Concurrency & Thread Safety](#concurrency--thread-safety)
7. [Common Interview Questions](#common-interview-questions)
8. [Variations & Extensions](#variations--extensions)
9. [Interview Timeline (45-60 min)](#interview-timeline-45-60-min)
10. [Red Flags to Avoid](#red-flags-to-avoid)
11. [Comparison with Real Systems](#comparison-with-real-systems)

---

## Problem Statement

> Design a train ticket booking system like IRCTC where users can search for trains, check availability, book tickets, and cancel bookings.

**Complexity Level**: Medium to Hard
**Topics Covered**: Concurrency, Resource Management, State Management, System Design

---

## Clarifying Questions

Before jumping into design, ask these questions:

### 1. Scope & Scale
- ❓ "How many users do we expect? Concurrent users?"
  - **Answer**: Start with 10,000 concurrent users, design for scalability
  
- ❓ "How many trains in the system?"
  - **Answer**: ~5,000-10,000 trains

- ❓ "What's the average number of seats per train?"
  - **Answer**: 500-2000 seats (varies by train type)

### 2. Functional Requirements
- ❓ "Should the system support partial route bookings?"
  - **Example**: Train goes Delhi → Agra → Jhansi. Can someone book Delhi-Agra while another books Agra-Jhansi?
  - **Answer**: YES (this is key!)

- ❓ "Do we need to support different classes (AC, Sleeper, etc.)?"
  - **Answer**: Yes, multiple seat types

- ❓ "Should we handle waitlist and RAC?"
  - **Answer**: Start with simple confirmed bookings, mention as extension

- ❓ "Do we need dynamic pricing?"
  - **Answer**: Start with fixed pricing, can extend later

### 3. Non-Functional Requirements
- ❓ "What's the expected response time for booking?"
  - **Answer**: < 2 seconds for booking, < 1 second for search

- ❓ "Can there be double bookings?"
  - **Answer**: NO - this is critical!

- ❓ "What happens if payment fails mid-booking?"
  - **Answer**: Should rollback seat allocation

### 4. Out of Scope (for initial design)
- User authentication & authorization (assume it exists)
- Seat preferences (window/aisle)
- Food ordering, seat upgrades
- Real-time train tracking
- PNR SMS/email notifications (mention but don't implement)

---

## Requirements Gathering

### Functional Requirements (Must Have)
1. ✅ Search trains between two stations on a date
2. ✅ View real-time seat availability by class
3. ✅ Book tickets for one or more passengers
4. ✅ Automatic seat allocation
5. ✅ Payment integration (simulated)
6. ✅ Cancel bookings with refund
7. ✅ Check booking status via PNR
8. ✅ Support multiple seat classes

### Non-Functional Requirements
1. ✅ **Consistency**: No double bookings (strong consistency)
2. ✅ **Concurrency**: Handle concurrent bookings safely
3. ✅ **Atomicity**: Booking either fully succeeds or fully fails
4. ✅ **Performance**: Sub-second search, quick booking
5. ✅ **Scalability**: Support high concurrent load
6. ✅ **Availability**: System should be highly available (99.9%+)

### Key Constraints
- Once a seat is booked for a segment, it cannot be booked for overlapping segments
- Payment must succeed before confirming booking
- Cancellation should release seats immediately for rebooking

---

## Core Entities & Relationships

### Entity Relationship Diagram

```
┌─────────────┐
│   Station   │
│─────────────│
│ code (PK)   │
│ name        │
│ city        │
└─────────────┘
      △
      │
      │ has multiple
      │
┌─────────────┐
│    Route    │
│─────────────│
│ schedules   │──┐
└─────────────┘  │
                 │ contains
                 ▼
        ┌─────────────────┐
        │ StationSchedule │
        │─────────────────│
        │ station         │
        │ arrivalTime     │
        │ departureTime   │
        │ stationOrder    │
        │ distanceFromSt  │
        └─────────────────┘

┌──────────────┐
│    Train     │
│──────────────│
│ trainNumber  │◆──── Route
│ trainName    │
│ seatsByType  │
└──────────────┘
       │
       │ has many
       ▼
┌──────────────┐
│     Seat     │
│──────────────│
│ seatNumber   │
│ seatType     │
│ coachNumber  │
└──────────────┘

┌──────────────┐
│   Booking    │
│──────────────│
│ pnr (PK)     │────┐
│ userId       │    │
│ train     ───────┤
│ source       │    │
│ destination  │    │
│ journeyDate  │    │
│ status       │    │
│ totalAmount  │    │
└──────────────┘    │
       │            │
       │ contains   │
       ▼            │
┌────────────────────┐
│ PassengerSeatMap   │
│────────────────────│
│ passenger          │
│ seat               │
└────────────────────┘

┌──────────────┐
│  Passenger   │
│──────────────│
│ name         │
│ age          │
│ gender       │
│ identityProof│
└──────────────┘
```

### Key Relationships
1. Train **HAS-A** Route
2. Route **CONTAINS** multiple StationSchedules
3. Train **HAS-MANY** Seats (grouped by SeatType)
4. Booking **REFERENCES** Train, source/destination Stations
5. Booking **CONTAINS** multiple PassengerSeatMappings

---

## API Design

### 1. Search Trains
```java
/**
 * Search trains between source and destination on given date
 * @return List of trains with availability info
 */
List<TrainSearchResult> searchTrains(
    Station source, 
    Station destination, 
    LocalDate journeyDate
)

// TrainSearchResult contains:
// - Train details
// - Departure and arrival time
// - Availability by seat class (Map<SeatType, Integer>)
```

**Time Complexity**: O(T × S) where T = total trains, S = stations per route
**Optimization**: Index trains by source-destination pairs

### 2. Book Tickets
```java
/**
 * Book tickets for passengers
 * @return Booking object with PNR
 * @throws IllegalStateException if booking fails
 */
Booking bookTickets(
    String userId,
    String trainNumber,
    String sourceStationCode,
    String destinationStationCode,
    LocalDate journeyDate,
    SeatType seatType,
    List<Passenger> passengers,
    PaymentMethod paymentMethod
)
```

**Transaction Steps**:
1. Validate inputs
2. Check seat availability
3. Calculate fare
4. Process payment
5. Allocate seats (with rollback on failure)
6. Create and save booking
7. Return PNR

**Time Complexity**: O(N × M) where N = passengers, M = available seats to check

### 3. Cancel Booking
```java
/**
 * Cancel a booking by PNR
 * @return true if cancelled successfully
 */
boolean cancelBooking(String pnr)
```

**Steps**:
1. Find booking
2. Validate cancellation allowed
3. Release seats
4. Update status
5. Process refund

### 4. Get Booking Status
```java
/**
 * Retrieve booking details by PNR
 */
Booking getBooking(String pnr)

/**
 * Get all bookings for a user
 */
List<Booking> getUserBookings(String userId)
```

---

## Concurrency & Thread Safety

### The Core Problem

**Scenario**: Last seat problem
```
Time    Thread-1              Thread-2
────────────────────────────────────────
t1      Read: 1 seat free
t2                            Read: 1 seat free
t3      Book seat             
t4                            Book seat ← DOUBLE BOOKING!
```

### Solution 1: Synchronized Block (Naive)

```java
synchronized (trainBookingSystem) {
    // Check availability
    // Book seat
}
```

**Pros**: Simple, prevents double booking
**Cons**: Serializes ALL bookings (even for different trains/seats) - terrible performance

### Solution 2: Train-Level Locking

```java
synchronized (train) {
    // Check availability
    // Book seat
}
```

**Pros**: Better than global lock
**Cons**: All bookings for same train serialized (even for different seats)

### Solution 3: Seat-Level Locking (Our Approach) ⭐

```java
Map<Seat, Object> seatLocks = new ConcurrentHashMap<>();

// When booking seat S1:
synchronized (seatLocks.get(S1)) {
    if (isSeatAvailable(S1, sourceOrder, destOrder)) {
        bookSeat(S1, sourceOrder, destOrder);
        return true;
    }
    return false;
}
```

**Pros**:
- Fine-grained locking
- Concurrent bookings for different seats proceed in parallel
- Only conflicts when two users want EXACT same seat for overlapping segments

**Cons**:
- More complex to implement
- Need to manage locks per seat

**Performance Impact**:
- Global lock: 100 bookings/sec (all serialized)
- Train-level: 500 bookings/sec (per-train parallelism)
- Seat-level: 5000+ bookings/sec (per-seat parallelism)

### Solution 4: Optimistic Locking (Alternative)

```java
class SeatAvailability {
    private int version;
    
    boolean bookSeat(Seat seat, int expectedVersion) {
        if (this.version != expectedVersion) {
            return false; // Someone else modified, retry
        }
        // Book seat
        this.version++;
        return true;
    }
}
```

**Pros**: No blocking, better for low-contention scenarios
**Cons**: Requires retry logic, complex error handling

---

## Segment-Based Seat Allocation

### The Key Insight

❌ **Naive Approach**: If a seat is booked from Delhi to Mumbai, it's blocked for entire journey

✅ **Smart Approach**: A seat can be booked for non-overlapping segments

### Example

```
Train Route: Delhi (0) → Agra (1) → Jhansi (2) → Bhopal (3) → Mumbai (4)

Seat S1:
- Booked by User A: Delhi → Jhansi [0, 2]
- Can User B book Jhansi → Mumbai [2, 4]? ✅ YES! (no overlap)
- Can User C book Agra → Bhopal [1, 3]? ❌ NO! (overlaps with [0, 2])
```

### Overlap Detection Algorithm

```java
boolean hasOverlap(int newStart, int newEnd, int existingStart, int existingEnd) {
    // Two segments DON'T overlap if:
    // 1. New segment ends before existing starts: newEnd <= existingStart
    // 2. New segment starts after existing ends: newStart >= existingEnd
    
    return !(newEnd <= existingStart || newStart >= existingEnd);
}
```

### Data Structure

```java
class SeatAvailability {
    // For each seat, maintain list of occupied segments
    Map<Seat, List<RouteSegment>> seatOccupancy;
    
    class RouteSegment {
        int sourceOrder;  // Starting station order
        int destOrder;    // Ending station order
    }
}
```

### Booking Algorithm

```java
boolean bookSeat(Seat seat, int newSourceOrder, int newDestOrder) {
    synchronized (seatLocks.get(seat)) {
        List<RouteSegment> occupied = seatOccupancy.get(seat);
        
        // Check if new segment overlaps with any existing segment
        for (RouteSegment segment : occupied) {
            if (hasOverlap(newSourceOrder, newDestOrder, 
                          segment.sourceOrder, segment.destOrder)) {
                return false; // Overlap found, can't book
            }
        }
        
        // No overlap, safe to book
        occupied.add(new RouteSegment(newSourceOrder, newDestOrder));
        return true;
    }
}
```

**Time Complexity**: O(K) where K = number of existing bookings for this seat
**Space Complexity**: O(K) for storing segments

---

## Common Interview Questions

### Q1: How do you handle race conditions in seat booking?

**Answer**:
We use **seat-level locking** with segment-based availability:

```java
synchronized (seatLocks.get(seat)) {
    // Check if seat available for route segment
    // Book if available
}
```

This ensures:
1. Only one thread can book a specific seat at a time
2. Other threads booking different seats proceed concurrently
3. No double bookings possible

**Follow-up**: "What if 1000 people try to book the same seat?"
- Only one succeeds, others get "not available" response immediately
- Need to retry with different seat or show error

---

### Q2: How would you scale this to millions of users?

**Answer** (discuss progressively):

**Level 1: Database & Caching**
```
┌──────────┐    ┌──────────┐    ┌──────────┐
│  User    │───▶│  Cache   │───▶│    DB    │
└──────────┘    │ (Redis)  │    │(Postgres)│
                └──────────┘    └──────────┘
```
- Cache frequently searched routes
- Cache seat availability (with TTL)
- Database for persistent storage

**Level 2: Microservices**
```
┌────────────┐  ┌────────────┐  ┌────────────┐
│  Search    │  │  Booking   │  │  Payment   │
│  Service   │  │  Service   │  │  Service   │
└────────────┘  └────────────┘  └────────────┘
      │               │                │
      └───────────────┴────────────────┘
                      │
             ┌────────────────┐
             │  API Gateway   │
             └────────────────┘
```

**Level 3: Database Sharding**
- Shard by train number (e.g., Trains 10000-19999 → Shard 1)
- Or shard by region (North, South, East, West)

**Level 4: Read Replicas**
```
Master (Writes) → Bookings, Cancellations
   ↓ Replicate
Slaves (Reads) → Search, PNR Status
```

**Level 5: Message Queue**
```
Booking Event → Kafka → [Notification, Analytics, Email]
```

---

### Q3: How do you prevent double bookings?

**Answer**:

1. **Pessimistic Locking** (our approach):
   - Lock seat before checking availability
   - Atomic check-and-book operation
   - Release lock after booking

2. **Database Constraints**:
   ```sql
   UNIQUE CONSTRAINT (train_id, date, seat_id, source, destination)
   ```
   - Database enforces uniqueness
   - Race condition leads to constraint violation → retry

3. **Distributed Locks** (for microservices):
   - Use Redis distributed locks
   - Acquire lock: `SETNX booking:train123:seat45 1 EX 10`
   - Ensures only one service instance can book

---

### Q4: How do you calculate fare?

**Answer**:

```java
double calculateFare(SeatType seatType, int distance, int passengerCount) {
    double basePrice = seatType.getBasePrice();
    double distanceFactor = distance / 100.0; // Per 100 km
    double totalFare = basePrice * distanceFactor * passengerCount;
    
    // Apply discounts (if any)
    totalFare = applyDiscounts(totalFare, passengers);
    
    return totalFare;
}
```

**Extensions**:
- Dynamic pricing (surge during peak times)
- Discount for children, senior citizens
- Early bird discounts
- Group booking discounts

---

### Q5: What happens if payment fails after seat allocation?

**Answer**:

We implement **rollback mechanism**:

```java
Booking bookTickets(...) {
    List<Seat> bookedSeats = new ArrayList<>();
    
    try {
        // 1. Allocate seats
        for (Passenger p : passengers) {
            Seat seat = allocateSeat();
            bookedSeats.add(seat);
        }
        
        // 2. Process payment
        PaymentResult result = paymentService.process(...);
        
        if (!result.isSuccess()) {
            throw new PaymentException("Payment failed");
        }
        
        // 3. Create booking
        return new Booking(...);
        
    } catch (Exception e) {
        // ROLLBACK: Release all allocated seats
        for (Seat seat : bookedSeats) {
            releaseSeat(seat);
        }
        throw new BookingException("Booking failed", e);
    }
}
```

**Key Points**:
- Transaction-like behavior (all-or-nothing)
- Seats released immediately on failure
- User sees clear error message
- No inconsistent state

---

### Q6: How would you implement waitlist?

**Answer**:

```java
class Booking {
    BookingStatus status; // CONFIRMED, WAITLISTED, RAC
    int waitlistNumber; // Position in queue
}

class SeatAvailability {
    Queue<Booking> waitlist = new LinkedList<>();
    
    Booking bookTickets(...) {
        if (availableSeats > 0) {
            // Confirm booking
            return confirmBooking();
        } else if (waitlist.size() < MAX_WAITLIST) {
            // Add to waitlist
            Booking booking = new Booking(..., WAITLISTED);
            booking.waitlistNumber = waitlist.size() + 1;
            waitlist.add(booking);
            return booking;
        } else {
            throw new Exception("Train full, waitlist also full");
        }
    }
    
    void cancelBooking(String pnr) {
        // Cancel booking
        releaseSeat(...);
        
        // Promote from waitlist
        if (!waitlist.isEmpty()) {
            Booking waitlisted = waitlist.poll();
            confirmBooking(waitlisted); // Allocate released seat
            notifyUser(waitlisted.userId, "Your ticket is confirmed!");
        }
    }
}
```

**Challenges**:
- Need to notify waitlisted users
- Handle partial confirmations (2 seats released, 3 people waiting)
- Priority: RAC first, then waitlist

---

### Q7: How do you handle train cancellations or delays?

**Answer**:

```java
class Train {
    TrainStatus status; // RUNNING, CANCELLED, DELAYED
    int delayMinutes;
}

void cancelTrain(String trainNumber, LocalDate date) {
    // 1. Mark train as cancelled
    Train train = getTrain(trainNumber);
    train.setStatus(CANCELLED);
    
    // 2. Get all bookings for this train on this date
    List<Booking> bookings = getBookingsForTrain(trainNumber, date);
    
    // 3. Cancel all bookings and process refunds
    for (Booking booking : bookings) {
        cancelBooking(booking.getPnr());
        processFullRefund(booking); // 100% refund for train cancellation
        notifyUser(booking.getUserId(), "Train cancelled, refund processed");
    }
}
```

**For delays**:
- Update arrival/departure times in route
- Send notifications to affected passengers
- Provide rebooking options if delay > threshold

---

### Q8: Database schema design?

**Answer**:

```sql
-- Stations table
CREATE TABLE stations (
    station_code VARCHAR(10) PRIMARY KEY,
    station_name VARCHAR(100) NOT NULL,
    city VARCHAR(50),
    state VARCHAR(50)
);

-- Trains table
CREATE TABLE trains (
    train_number VARCHAR(10) PRIMARY KEY,
    train_name VARCHAR(100) NOT NULL
);

-- Routes table
CREATE TABLE routes (
    route_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_number VARCHAR(10) REFERENCES trains(train_number),
    station_code VARCHAR(10) REFERENCES stations(station_code),
    arrival_time TIMESTAMP,
    departure_time TIMESTAMP,
    station_order INT,
    distance_from_start INT,
    UNIQUE(train_number, station_order)
);

-- Seats table
CREATE TABLE seats (
    seat_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    train_number VARCHAR(10) REFERENCES trains(train_number),
    coach_number VARCHAR(10),
    seat_number VARCHAR(10),
    seat_type VARCHAR(10),
    UNIQUE(train_number, coach_number, seat_number)
);

-- Bookings table
CREATE TABLE bookings (
    pnr VARCHAR(20) PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    train_number VARCHAR(10) REFERENCES trains(train_number),
    source_station VARCHAR(10) REFERENCES stations(station_code),
    destination_station VARCHAR(10) REFERENCES stations(station_code),
    journey_date DATE,
    booking_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20), -- CONFIRMED, CANCELLED, WAITLISTED
    total_amount DECIMAL(10, 2),
    INDEX idx_user_bookings (user_id),
    INDEX idx_journey (train_number, journey_date)
);

-- Seat Allocations table (for segment-based booking)
CREATE TABLE seat_allocations (
    allocation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pnr VARCHAR(20) REFERENCES bookings(pnr),
    seat_id BIGINT REFERENCES seats(seat_id),
    journey_date DATE,
    source_order INT,
    destination_order INT,
    UNIQUE(seat_id, journey_date, source_order, destination_order)
);

-- Passengers table
CREATE TABLE passengers (
    passenger_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pnr VARCHAR(20) REFERENCES bookings(pnr),
    name VARCHAR(100),
    age INT,
    gender VARCHAR(10),
    identity_proof VARCHAR(50),
    seat_id BIGINT REFERENCES seats(seat_id)
);
```

**Key Indexes**:
- `idx_user_bookings`: Fast user booking lookup
- `idx_journey`: Fast search by train and date
- Unique constraints prevent double bookings

---

### Q9: How to handle peak load (e.g., Tatkal booking at 10 AM)?

**Answer**:

**Problem**: 1 million users hit server at exactly 10:00 AM

**Solutions**:

1. **Queue-Based System**:
```
User → Load Balancer → Queue (Kafka/RabbitMQ) → Workers → Database
```
- Users get queue position: "You are #1247 in queue"
- Workers process requests sequentially
- Prevents server crash

2. **Rate Limiting**:
```java
@RateLimit(maxRequests = 100, perSeconds = 1)
public Booking bookTickets(...) {
    // Process booking
}
```
- Limit requests per user/IP
- Return "Too many requests, try again"

3. **CAPTCHA**:
- Prevent bots from hammering system
- Reduce load by filtering automated requests

4. **Horizontal Scaling**:
- Spin up 100+ server instances
- Auto-scale based on load

5. **Database Optimization**:
- Connection pooling
- Read replicas for queries
- Master for writes only

---

### Q10: How to test this system?

**Answer**:

**1. Unit Tests**:
```java
@Test
public void testSeatAvailability() {
    SeatAvailability avail = new SeatAvailability(...);
    Seat seat = new Seat(...);
    
    // Book segment [0, 2]
    assertTrue(avail.bookSeat(seat, 0, 2));
    
    // Try to book overlapping [1, 3] - should fail
    assertFalse(avail.bookSeat(seat, 1, 3));
    
    // Book non-overlapping [2, 4] - should succeed
    assertTrue(avail.bookSeat(seat, 2, 4));
}
```

**2. Concurrency Tests**:
```java
@Test
public void testConcurrentBooking() throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // 100 threads try to book same seat
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            try {
                bookTicket(...);
                successCount.incrementAndGet();
            } catch (Exception e) {
                // Booking failed (expected for most)
            }
        });
    }
    
    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.MINUTES);
    
    // Only one booking should succeed
    assertEquals(1, successCount.get());
}
```

**3. Load Tests**:
- Use JMeter or Gatling
- Simulate 10,000 concurrent users
- Measure response time, throughput
- Check for memory leaks, database connection issues

**4. Integration Tests**:
- Test full booking flow end-to-end
- Mock payment gateway
- Verify rollback on failures

---

## Variations & Extensions

### Variation 1: Add seat preferences

```java
enum SeatPreference {
    WINDOW, AISLE, LOWER_BERTH, MIDDLE_BERTH, UPPER_BERTH
}

Booking bookTickets(..., List<SeatPreference> preferences) {
    // Filter seats by preference first
    List<Seat> availableSeats = getAvailableSeats(seatType, sourceOrder, destOrder);
    List<Seat> preferredSeats = filterByPreference(availableSeats, preferences);
    
    // Book preferred seats if available
    if (!preferredSeats.isEmpty()) {
        allocate(preferredSeats);
    } else {
        // Fall back to any available seat
        allocate(availableSeats);
    }
}
```

### Variation 2: Group bookings

**Requirement**: Keep family together (adjacent seats)

```java
List<Seat> findAdjacentSeats(int count, SeatType type) {
    // Look for seats in same coach with consecutive numbers
    for (String coach : coaches) {
        List<Seat> coachSeats = getSeatsInCoach(coach, type);
        
        for (int i = 0; i <= coachSeats.size() - count; i++) {
            List<Seat> candidates = coachSeats.subList(i, i + count);
            if (areConsecutive(candidates) && allAvailable(candidates)) {
                return candidates;
            }
        }
    }
    return null; // No adjacent seats found
}
```

### Variation 3: Dynamic pricing

```java
double calculateDynamicPrice(Train train, LocalDate date, SeatType type) {
    double basePrice = type.getBasePrice();
    
    // Surge pricing based on demand
    int bookedSeats = getBookedCount(train, date, type);
    int totalSeats = getTotalSeats(train, type);
    double occupancyRate = (double) bookedSeats / totalSeats;
    
    if (occupancyRate > 0.9) {
        basePrice *= 1.5; // 50% surge
    } else if (occupancyRate > 0.7) {
        basePrice *= 1.2; // 20% surge
    }
    
    // Early bird discount
    long daysUntilJourney = ChronoUnit.DAYS.between(LocalDate.now(), date);
    if (daysUntilJourney > 30) {
        basePrice *= 0.9; // 10% discount
    }
    
    return basePrice;
}
```

### Variation 4: Multi-city booking

**Requirement**: Book Delhi → Mumbai → Goa (multiple trains)

```java
class MultiCityBooking {
    List<Booking> legs; // Each leg is a separate booking
    
    Booking bookMultiCity(List<Journey> journeys) {
        List<Booking> bookings = new ArrayList<>();
        
        try {
            for (Journey journey : journeys) {
                Booking booking = bookTickets(journey);
                bookings.add(booking);
            }
            
            return new MultiCityBooking(bookings);
            
        } catch (Exception e) {
            // Rollback all bookings
            for (Booking booking : bookings) {
                cancelBooking(booking.getPnr());
            }
            throw e;
        }
    }
}
```

### Variation 5: Loyalty program

```java
interface LoyaltyProgram {
    double getDiscountPercentage(String userId);
    void awardPoints(String userId, double amountSpent);
}

class PremiumMembership implements LoyaltyProgram {
    public double getDiscountPercentage(String userId) {
        Member member = getMember(userId);
        if (member.tier == GOLD) return 0.15; // 15% off
        if (member.tier == SILVER) return 0.10; // 10% off
        return 0.05; // 5% off for basic
    }
    
    public void awardPoints(String userId, double amountSpent) {
        int points = (int) (amountSpent / 100); // 1 point per ₹100
        memberRepository.addPoints(userId, points);
    }
}
```

---

## Interview Timeline (45-60 min)

### Minutes 0-10: Requirements & Clarification
- ✅ Ask clarifying questions
- ✅ Define functional requirements
- ✅ Define non-functional requirements
- ✅ Identify key entities

### Minutes 10-20: High-Level Design
- ✅ Draw entity relationship diagram
- ✅ Identify main classes and relationships
- ✅ Discuss API design
- ✅ Explain workflow (search → book → confirm)

### Minutes 20-35: Deep Dive & Coding
- ✅ Focus on most critical component (usually seat availability & booking)
- ✅ Explain concurrency approach
- ✅ Write pseudocode or actual code for core logic
- ✅ Discuss thread safety measures

### Minutes 35-45: Extensions & Trade-offs
- ✅ Discuss scalability strategies
- ✅ Database design
- ✅ Caching approach
- ✅ Handle edge cases (payment failure, rollback, etc.)

### Minutes 45-60: Q&A & Variations
- ✅ Answer follow-up questions
- ✅ Discuss alternative approaches
- ✅ Talk about real-world systems (IRCTC, Amtrak, etc.)

---

## Red Flags to Avoid

### ❌ Don't Do This:

1. **Starting to code immediately without clarifying requirements**
   - Always ask questions first!

2. **Ignoring concurrency**
   - "I'll just use synchronized everywhere" ← Too naive
   - Must explain proper locking strategy

3. **Not considering partial route bookings**
   - Segment-based allocation is the key insight!

4. **Forgetting about rollback on payment failure**
   - Transaction-like behavior is critical

5. **Not discussing scalability**
   - Even if implementation is simple, mention how to scale

6. **Hardcoding values**
   - Use configuration/enums for seat types, prices, etc.

7. **Not handling edge cases**
   - What if train gets cancelled?
   - What if user cancels at the last minute?
   - What if database goes down during booking?

### ✅ Do This Instead:

1. **Clarify requirements upfront**
2. **Explain your thought process**
3. **Discuss trade-offs** (pessimistic vs optimistic locking, etc.)
4. **Start simple, then extend**
5. **Ask if interviewer wants you to go deeper**
6. **Write clean, readable code**
7. **Test your logic mentally** (walk through example)

---

## Comparison with Real Systems

### IRCTC (Indian Railways)
- **Scale**: 10+ million bookings/day
- **Architecture**: Microservices on cloud
- **Database**: Oracle RAC (clustered)
- **Features**: Waitlist, RAC, Tatkal, dynamic pricing
- **Challenges**: Peak load handling (Tatkal opens at 10 AM sharp)

### Amtrak (USA)
- **Scale**: Smaller than IRCTC
- **Features**: Multi-city bookings, seat preferences
- **Payment**: Multiple payment gateways
- **Integration**: With airline bookings (intermodal travel)

### Trainline (UK)
- **Aggregator**: Books across multiple operators
- **Dynamic pricing**: Complex algorithm
- **Mobile-first**: Heavy focus on app experience
- **Real-time updates**: Train delays, platform changes

### Key Takeaways from Real Systems:
1. **Horizontal scaling** is essential
2. **Caching** heavily used (Redis/Memcached)
3. **Message queues** for async processing
4. **Microservices** for modularity
5. **Monitoring & alerting** critical for uptime
6. **Disaster recovery** and backups mandatory

---

## Summary Checklist

Before ending interview, make sure you covered:

- [ ] Core entities and relationships
- [ ] API design for main operations
- [ ] Concurrency and thread safety (most important!)
- [ ] Segment-based seat allocation logic
- [ ] Payment integration and rollback
- [ ] Booking cancellation
- [ ] Database schema (at least high-level)
- [ ] Scalability discussion
- [ ] At least one extension/variation

---

## Practice Questions

1. **Design BookMyShow** (similar: concurrent seat booking for movies)
2. **Design Uber/Ola** (matching drivers to riders, similar concurrency issues)
3. **Design Hotel Booking System** (rooms instead of seats, date ranges instead of segments)
4. **Design Restaurant Reservation System** (table booking, time slots)
5. **Design Parking Lot System** (spot allocation, similar to seat allocation)

---

## Additional Resources

- Concurrency in Java (java.util.concurrent package)
- Database transactions and ACID properties
- Distributed systems concepts (CAP theorem, eventual consistency)
- System design primers (Grokking the System Design Interview)
- Real IRCTC architecture talks/papers

---

**Good luck with your interview! 🚂💻**
