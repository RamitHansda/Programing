# Train Booking System - Detailed Design Document

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Class Diagram](#class-diagram)
3. [Component Details](#component-details)
4. [Concurrency Model](#concurrency-model)
5. [Data Flow](#data-flow)
6. [Design Tradeoffs](#design-tradeoffs)

## System Requirements

### Functional Requirements
1. Users can search for trains between two stations on a given date
2. System displays real-time seat availability for different classes
3. Users can book tickets for one or more passengers
4. System allocates seats automatically
5. Payment integration for booking confirmation
6. Users can cancel bookings and get refunds
7. Users can check booking status using PNR
8. Support for different seat types (Sleeper, AC tiers, etc.)

### Non-Functional Requirements
1. **Thread Safety**: Handle concurrent bookings without race conditions
2. **Consistency**: No double bookings, atomic operations
3. **Performance**: Sub-second response for search, quick booking
4. **Scalability**: Support thousands of concurrent users
5. **Reliability**: 99.9% uptime, no data loss
6. **Maintainability**: Clean, extensible code

## Class Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    TrainBookingSystem (Singleton)               │
├─────────────────────────────────────────────────────────────────┤
│ - trains: Map<String, Train>                                    │
│ - stations: Map<String, Station>                                │
│ - bookings: Map<String, Booking>                                │
│ - seatAvailabilityMap: Map<String, SeatAvailability>            │
│ - paymentService: PaymentService                                │
├─────────────────────────────────────────────────────────────────┤
│ + searchTrains(source, dest, date): List<TrainSearchResult>    │
│ + bookTickets(...): Booking                                     │
│ + cancelBooking(pnr): boolean                                   │
│ + getBooking(pnr): Booking                                      │
└─────────────────────────────────────────────────────────────────┘
                    │
                    │ uses
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                        SeatAvailability                          │
├─────────────────────────────────────────────────────────────────┤
│ - train: Train                                                   │
│ - journeyDate: LocalDate                                         │
│ - seatOccupancy: Map<Seat, List<RouteSegment>>                  │
│ - seatLocks: Map<Seat, Object>                                  │
├─────────────────────────────────────────────────────────────────┤
│ + isSeatAvailable(seat, sourceOrder, destOrder): boolean        │
│ + bookSeat(seat, sourceOrder, destOrder): boolean               │
│ + releaseSeat(seat, sourceOrder, destOrder): void               │
│ + getAvailableSeats(seatType, orders, count): List<Seat>        │
└─────────────────────────────────────────────────────────────────┘

┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│   Station    │      │    Train     │      │     Seat     │
├──────────────┤      ├──────────────┤      ├──────────────┤
│ - code       │      │ - number     │      │ - seatNumber │
│ - name       │      │ - name       │      │ - seatType   │
│ - city       │      │ - route      │      │ - coachNumber│
└──────────────┘      │ - seatsByType│      └──────────────┘
                      └──────────────┘

┌──────────────────────────────────────┐
│            Route                     │
├──────────────────────────────────────┤
│ - stationSchedules: List             │
├──────────────────────────────────────┤
│ + addStation(...)                    │
│ + isValidRoute(source, dest): bool   │
│ + getDistance(source, dest): int     │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│            Booking                   │
├──────────────────────────────────────┤
│ - pnr: String                        │
│ - userId: String                     │
│ - train: Train                       │
│ - source, destination: Station       │
│ - journeyDate: LocalDate             │
│ - passengerSeatMappings: List        │
│ - status: BookingStatus              │
│ - totalAmount: double                │
└──────────────────────────────────────┘
```

## Component Details

### 1. Station
**Purpose**: Represents a railway station

**Attributes**:
- `stationCode`: Unique identifier (e.g., "NDLS")
- `stationName`: Full name (e.g., "New Delhi")
- `city`, `state`: Location information

**Design Choices**:
- Immutable class for thread safety
- Uses station code for equality
- Lightweight object

### 2. Train
**Purpose**: Represents a train with its route and seat inventory

**Attributes**:
- `trainNumber`: Unique identifier (e.g., "12301")
- `trainName`: Display name
- `route`: Complete route with station schedules
- `seatsByType`: Map of seat type to list of seats

**Key Methods**:
- `getSeatsByType()`: Get all seats of a particular class
- `getTotalSeats()`: Count of seats per class
- `getAvailableSeatTypes()`: What classes this train offers

**Design Choices**:
- Composition with Route
- Pre-configured seat inventory (could be dynamic)
- No mutable state after creation

### 3. Route & StationSchedule
**Purpose**: Manages train route with timings and distances

**Key Attributes**:
- `stationSchedules`: Ordered list of station stops
- Each schedule has: station, arrival/departure time, distance, order

**Key Methods**:
- `isValidRoute()`: Validates source comes before destination
- `getDistance()`: Calculates distance between two stations
- `getScheduleForStation()`: Get timing for a specific station

**Design Choices**:
- Station order is crucial for segment-based booking
- Distance-based fare calculation
- Immutable after creation

### 4. SeatAvailability
**Purpose**: Thread-safe management of seat availability for a train-date combination

**Core Data Structure**:
```java
Map<Seat, List<RouteSegment>> seatOccupancy
```

Each seat maps to list of occupied segments (source_order to dest_order)

**Example**:
```
Train: Delhi → Agra → Jhansi → Bhopal
Stations: 0      1      2        3

Seat S1 Occupancy:
- Segment [0, 2]: Delhi → Jhansi (booked)
- Segment [2, 3]: Available
```

**Thread Safety**:
```java
Map<Seat, Object> seatLocks  // One lock per seat
```

When booking:
1. Acquire lock for specific seat
2. Check segment overlap
3. Book if available
4. Release lock

**Key Methods**:
- `isSeatAvailable()`: Check if seat free for given segment
- `bookSeat()`: Atomically check and book
- `releaseSeat()`: Remove segment (for cancellation)
- `getAvailableSeats()`: Find N available seats

### 5. Booking
**Purpose**: Complete booking information

**Key Attributes**:
- `pnr`: Unique booking identifier
- `userId`: Who made the booking
- `train`, `source`, `destination`, `journeyDate`: Journey details
- `passengerSeatMappings`: List of passenger-to-seat assignments
- `status`: CONFIRMED, CANCELLED, etc.
- `totalAmount`: Total fare paid

**Design Choices**:
- PNR generated using UUID
- Immutable after creation (except status)
- Contains all info for ticket display

### 6. TrainBookingSystem (Main Service)
**Purpose**: Singleton orchestrator for all operations

**Data Stores**:
```java
Map<String, Train> trains              // trainNumber → Train
Map<String, Station> stations          // stationCode → Station
Map<String, Booking> bookings          // PNR → Booking
Map<String, SeatAvailability> seatAvailabilityMap  // trainNum_date → Availability
```

**Key Operations**:

#### Search Trains
```
Input: source, destination, date
Process:
  1. Iterate all trains
  2. Check if route valid (source before destination)
  3. Get/create SeatAvailability for train-date
  4. Query availability for each seat type
  5. Return list of TrainSearchResult
Output: List of trains with availability
```

#### Book Tickets
```
Input: user, train, source, dest, date, seatType, passengers, paymentMethod
Process:
  1. Validate inputs (train exists, route valid)
  2. Get SeatAvailability for train-date
  3. Check if enough seats available
  4. Calculate fare based on distance and seat type
  5. Process payment
  6. If payment success:
     a. Book each seat atomically
     b. Create Booking object
     c. Map passengers to seats
     d. Save booking
  7. If any step fails: rollback seat bookings
Output: Booking with PNR
```

#### Cancel Booking
```
Input: PNR
Process:
  1. Find booking by PNR
  2. Validate booking can be cancelled
  3. Get SeatAvailability for train-date
  4. Release all booked seats
  5. Update booking status to CANCELLED
  6. Process refund (percentage of original amount)
Output: Success/failure
```

## Concurrency Model

### Problem: Race Conditions in Booking
**Scenario**: Two users book last available seat simultaneously

```
Time  User A                    User B
t1    Check: 1 seat available   
t2                               Check: 1 seat available
t3    Book seat                  
t4                               Book seat  ❌ DOUBLE BOOKING!
```

### Solution 1: Train-Level Locking (Simple but Slow)
```java
synchronized (train) {
    // Check and book
}
```
**Pros**: Simple, no double booking
**Cons**: All bookings for a train serialized, poor throughput

### Solution 2: Seat-Level Locking (Our Approach)
```java
synchronized (seatLocks.get(seat)) {
    if (isSeatAvailable(seat, sourceOrder, destOrder)) {
        bookSeat(seat, sourceOrder, destOrder);
        return true;
    }
    return false;
}
```

**Pros**:
- Fine-grained locking
- Multiple concurrent bookings if different seats
- Better throughput

**Cons**:
- More complex
- Need to manage multiple locks

### Segment-Based Availability Check
```java
boolean isSeatAvailable(Seat seat, int sourceOrder, int destOrder) {
    List<RouteSegment> occupiedSegments = seatOccupancy.get(seat);
    
    for (RouteSegment segment : occupiedSegments) {
        // Overlapping condition:
        // NOT (new booking ends before segment starts OR 
        //      new booking starts after segment ends)
        if (!(destOrder <= segment.sourceOrder || 
              sourceOrder >= segment.destOrder)) {
            return false;  // Overlap found
        }
    }
    return true;  // No overlap
}
```

**Key Insight**: A seat can be booked for multiple non-overlapping segments!

## Data Flow

### Booking Flow Diagram
```
User Request
    ↓
┌─────────────────────┐
│ Validate Inputs     │
│ (train, stations,   │
│  date, passengers)  │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Get/Create          │
│ SeatAvailability    │
│ for train-date      │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Check Availability  │
│ (enough seats?)     │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Calculate Fare      │
│ (distance × base    │
│  price × passengers)│
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Process Payment     │
│ (PaymentService)    │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Atomic Seat Booking │
│ (lock → check →     │
│  book → unlock)     │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Create Booking      │
│ (generate PNR,      │
│  map passengers)    │
└─────────────────────┘
    ↓
┌─────────────────────┐
│ Return Booking      │
│ (with PNR)          │
└─────────────────────┘
```

### Rollback Mechanism
If payment fails or seat booking fails midway:
```java
// Rollback loop
for (int j = 0; j < i; j++) {
    availability.releaseSeat(
        availableSeats.get(j), 
        sourceOrder, 
        destOrder
    );
}
throw new IllegalStateException("Booking failed");
```

## Design Tradeoffs

### 1. In-Memory vs Database
**Current**: In-memory maps
**Production**: Database (PostgreSQL, MySQL)

| Aspect | In-Memory | Database |
|--------|-----------|----------|
| Speed | Fast | Slower |
| Durability | Lost on crash | Persistent |
| Scalability | Single instance | Distributed |
| Suitable for | Demo, learning | Production |

### 2. Pessimistic vs Optimistic Locking
**Current**: Pessimistic (locks during booking)
**Alternative**: Optimistic (version numbers, retry on conflict)

| Approach | Pros | Cons |
|----------|------|------|
| Pessimistic | No conflicts, consistent | Slower, blocks |
| Optimistic | Faster, no blocking | Retry overhead, complex |

### 3. Seat Allocation Strategy
**Current**: First available seat
**Alternatives**:
- User preference (window, aisle, berth)
- Group seating (keep passengers together)
- Dynamic pricing (premium seats cost more)

### 4. Fare Calculation
**Current**: Linear (base price × distance factor)
**Alternatives**:
- Dynamic pricing (surge pricing)
- Discount schemes (early booking, senior citizen)
- Seasonal variations

### 5. Booking Confirmation
**Current**: Immediate confirmation if seats available
**Alternatives**:
- Waitlist (queue when fully booked)
- RAC (Reservation Against Cancellation)
- Tatkal (premium last-minute booking)

### 6. Cancellation Policy
**Current**: 80% refund, anytime
**Production**:
- Time-based refund (more refund if cancelled early)
- Cancellation charges
- No refund for no-show

## Scalability Enhancements

### 1. Caching Layer
```
Redis Cache:
  - Train search results (frequently searched routes)
  - Seat availability (TTL: 30 seconds)
  - User session data
```

### 2. Database Sharding
```
Shard by train number:
  - Shard 1: Trains 10000-19999
  - Shard 2: Trains 20000-29999
  
Or shard by region:
  - Shard North: Trains in northern routes
  - Shard South: Trains in southern routes
```

### 3. Microservices Architecture
```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Search     │    │   Booking    │    │   Payment    │
│   Service    │    │   Service    │    │   Service    │
└──────────────┘    └──────────────┘    └──────────────┘
       │                   │                    │
       └───────────────────┴────────────────────┘
                           │
                  ┌────────────────┐
                  │  API Gateway   │
                  └────────────────┘
```

### 4. Event-Driven Architecture
```
Booking Event → Kafka → [Notification Service, Analytics, Email Service]
```

### 5. Read Replicas
```
Master DB (Writes: Bookings, Cancellations)
    ↓ Replication
Slave DB 1 (Reads: Search, PNR status)
Slave DB 2 (Reads: Search, PNR status)
```

## Testing Considerations

### Unit Tests
- Test seat availability logic
- Test segment overlap detection
- Test fare calculation
- Test route validation

### Integration Tests
- Test full booking flow
- Test concurrent bookings
- Test rollback on payment failure

### Load Tests
- 1000 concurrent users searching
- 100 concurrent bookings for same train
- Database query performance

### Edge Cases
- Booking when only 1 seat left
- Cancelling already cancelled booking
- Invalid route combinations
- Payment timeout scenarios

## Conclusion

This design balances:
- **Simplicity**: Easy to understand and maintain
- **Correctness**: No race conditions or data inconsistency
- **Performance**: Seat-level locking for better throughput
- **Extensibility**: Can add features without major refactoring

The segment-based seat allocation is the key innovation, allowing maximum seat utilization while maintaining thread safety.
