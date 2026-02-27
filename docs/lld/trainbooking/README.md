# Train Booking System - Low Level Design

A comprehensive implementation of a train ticket booking system similar to IRCTC, designed with scalability, thread-safety, and real-world constraints in mind.

## 🎯 Overview

This system implements a production-ready train booking platform that handles:
- Train and route management
- Real-time seat availability tracking
- Concurrent booking requests
- Payment processing
- Booking cancellations and refunds
- Multi-class travel support (Sleeper, AC tiers, etc.)

## 🏗️ Architecture

### Core Components

1. **Domain Models**
   - `Station`: Railway stations with unique codes
   - `Train`: Trains with routes and seat inventory
   - `Route`: Route information with station schedules
   - `Seat`: Individual seats with type and coach information
   - `Passenger`: Passenger details
   - `Booking`: Complete booking information with PNR

2. **Service Layer**
   - `TrainBookingSystem`: Main singleton service managing all operations
   - `SeatAvailability`: Thread-safe seat availability management
   - `PaymentService`: Payment processing integration

3. **Utilities**
   - `SeatType`: Enum for different seat classes
   - `BookingStatus`: Enum for booking states

## 🚀 Features

### 1. Train Search
```java
List<TrainSearchResult> results = system.searchTrains(source, destination, date);
```
- Search trains between stations
- View real-time availability across all classes
- Filter by journey date

### 2. Ticket Booking
```java
Booking booking = system.bookTickets(
    userId, trainNumber, sourceCode, destCode, 
    date, seatType, passengers, paymentMethod
);
```
- Book multiple passengers in one transaction
- Automatic seat allocation
- Integrated payment processing
- Atomic operations with rollback on failure

### 3. Booking Management
- PNR-based booking lookup
- User booking history
- Booking cancellation with refunds
- Status tracking (CONFIRMED, CANCELLED, etc.)

### 4. Thread Safety
- Concurrent booking support
- Per-seat locking mechanism
- No race conditions or double bookings
- Optimized for high throughput

## 📊 Design Patterns Used

1. **Singleton Pattern**
   - `TrainBookingSystem` ensures single instance

2. **Builder Pattern (Implicit)**
   - Complex object construction for `Booking` and `Route`

3. **Strategy Pattern**
   - `PaymentService` with multiple payment methods

4. **Immutable Objects**
   - `Station`, `Seat` for thread safety

5. **Segment-Based Locking**
   - Fine-grained locks per seat for scalability

## 🔒 Thread Safety Approach

### Segment-Based Seat Management
Instead of locking entire trains, we:
1. Track seat occupancy as route segments
2. Lock individual seats during booking
3. Check for segment overlaps to determine availability

**Example:**
- Seat S1 booked from Delhi to Jhansi (stations 0-2)
- Seat S1 available from Jhansi to Bhopal (stations 2-3)
- No overlap = both bookings possible!

This allows for higher throughput in partial route bookings.

## 💡 Key Design Decisions

### 1. Route Segment Model
- Seats aren't "blocked" for entire journey
- Available for non-overlapping segments
- Maximizes seat utilization

### 2. Composite Key for Seat Availability
```
Key: trainNumber_date
```
- Separate availability per train per date
- Easy to manage and query

### 3. Atomic Booking with Rollback
- Payment → Seat Allocation → Booking Creation
- Rollback on any failure
- Ensures data consistency

### 4. Per-Seat Locking
```java
synchronized (seatLocks.get(seat)) {
    // Check and book
}
```
- Prevents double booking
- Better performance than train-level locks

## 📝 Usage Example

```java
// Initialize system
TrainBookingSystem system = TrainBookingSystem.getInstance();

// Add stations
system.addStation(new Station("NDLS", "New Delhi", "Delhi", "Delhi"));
system.addStation(new Station("BPL", "Bhopal", "Bhopal", "MP"));

// Create and add train
Route route = new Route();
route.addStation(...);
Train train = new Train("12301", "Rajdhani Express", route);
system.addTrain(train);

// Search trains
List<TrainSearchResult> results = system.searchTrains(
    system.getStation("NDLS"),
    system.getStation("BPL"),
    LocalDate.now()
);

// Book tickets
List<Passenger> passengers = Arrays.asList(
    new Passenger("John Doe", 30, Gender.MALE, "ID123")
);

Booking booking = system.bookTickets(
    "user123", "12301", "NDLS", "BPL",
    LocalDate.now(), SeatType.AC_2_TIER,
    passengers, PaymentMethod.UPI
);

System.out.println("PNR: " + booking.getPnr());
```

## 🧪 Running the Demo

```bash
javac lld/trainbooking/*.java
java lld.trainbooking.TrainBookingDemo
```

The demo showcases:
- Train search functionality
- Single and multi-passenger bookings
- Concurrent booking scenarios
- Cancellation and rebooking

## 🔧 Extensibility

The system can be extended to support:
1. **Waitlist Management**: Queue for overbooked trains
2. **Dynamic Pricing**: Surge pricing based on demand
3. **Seat Preferences**: Window/aisle, upper/lower berth
4. **Tatkal Booking**: Premium booking with different rules
5. **Multi-leg Journeys**: Connecting trains
6. **Real Payment Gateway**: Integration with actual payment APIs

## 📈 Scalability Considerations

1. **Database Integration**: Replace in-memory maps with DB
2. **Caching Layer**: Redis for seat availability
3. **Message Queue**: Async processing for notifications
4. **Microservices**: Split into booking, payment, search services
5. **Load Balancing**: Distribute across multiple instances

## 🎓 Interview Focus Areas

- **Concurrency**: How to handle race conditions
- **Data Consistency**: ACID properties in bookings
- **Scalability**: Handling millions of concurrent users
- **System Design**: Tradeoffs and design choices
- **Real-world Constraints**: Partial bookings, cancellations

## 📚 Related Patterns

- Database Connection Pooling (resource management)
- Rate Limiting (preventing abuse)
- Circuit Breaker (payment gateway failures)
- CQRS (separate read/write models for performance)

## 🤝 Contributing

This is a learning project. Feel free to:
- Add new features
- Improve thread safety
- Optimize performance
- Add comprehensive tests

## 📄 License

Educational purposes only.
