package lld.trainbooking;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main class managing the train booking system
 * Singleton pattern with thread-safe operations
 */
public class TrainBookingSystem {
    private static TrainBookingSystem instance;
    
    private final Map<String, Train> trains;  // trainNumber -> Train
    private final Map<String, Station> stations;  // stationCode -> Station
    private final Map<String, Booking> bookings;  // PNR -> Booking
    
    // Key: trainNumber + "_" + date (e.g., "12345_2024-01-15")
    private final Map<String, SeatAvailability> seatAvailabilityMap;
    
    private final PaymentService paymentService;
    private final Object bookingLock = new Object();

    private TrainBookingSystem() {
        this.trains = new ConcurrentHashMap<>();
        this.stations = new ConcurrentHashMap<>();
        this.bookings = new ConcurrentHashMap<>();
        this.seatAvailabilityMap = new ConcurrentHashMap<>();
        this.paymentService = new PaymentService();
    }

    public static synchronized TrainBookingSystem getInstance() {
        if (instance == null) {
            instance = new TrainBookingSystem();
        }
        return instance;
    }

    // ========== Train and Station Management ==========
    
    public void addTrain(Train train) {
        trains.put(train.getTrainNumber(), train);
    }

    public void addStation(Station station) {
        stations.put(station.getStationCode(), station);
    }

    public Train getTrain(String trainNumber) {
        return trains.get(trainNumber);
    }

    public Station getStation(String stationCode) {
        return stations.get(stationCode);
    }

    // ========== Search Functionality ==========
    
    public List<TrainSearchResult> searchTrains(Station source, Station destination, LocalDate journeyDate) {
        List<TrainSearchResult> results = new ArrayList<>();
        
        for (Train train : trains.values()) {
            if (train.getRoute().isValidRoute(source, destination)) {
                // Get or create seat availability for this train-date combination
                SeatAvailability availability = getOrCreateSeatAvailability(train, journeyDate);
                
                Route.StationSchedule sourceSchedule = train.getRoute().getScheduleForStation(source);
                Route.StationSchedule destSchedule = train.getRoute().getScheduleForStation(destination);
                
                Map<SeatType, Integer> availabilityByClass = new HashMap<>();
                for (SeatType seatType : train.getAvailableSeatTypes()) {
                    int availableSeats = availability.getAvailableSeatsCount(
                        seatType, 
                        sourceSchedule.getStationOrder(), 
                        destSchedule.getStationOrder()
                    );
                    availabilityByClass.put(seatType, availableSeats);
                }
                
                results.add(new TrainSearchResult(
                    train,
                    sourceSchedule.getDepartureTime(),
                    destSchedule.getArrivalTime(),
                    availabilityByClass
                ));
            }
        }
        
        // Sort by departure time
        results.sort(Comparator.comparing(TrainSearchResult::getDepartureTime));
        
        return results;
    }

    // ========== Booking Functionality ==========
    
    public Booking bookTickets(String userId, String trainNumber, String sourceCode, 
                               String destCode, LocalDate journeyDate, SeatType seatType,
                               List<Passenger> passengers, PaymentService.PaymentMethod paymentMethod) {
        
        synchronized (bookingLock) {
            // Validate inputs
            Train train = trains.get(trainNumber);
            Station source = stations.get(sourceCode);
            Station destination = stations.get(destCode);
            
            if (train == null || source == null || destination == null) {
                throw new IllegalArgumentException("Invalid train or station");
            }
            
            if (!train.getRoute().isValidRoute(source, destination)) {
                throw new IllegalArgumentException("Invalid route for this train");
            }
            
            if (passengers.isEmpty()) {
                throw new IllegalArgumentException("At least one passenger required");
            }
            
            // Get seat availability
            SeatAvailability availability = getOrCreateSeatAvailability(train, journeyDate);
            
            Route.StationSchedule sourceSchedule = train.getRoute().getScheduleForStation(source);
            Route.StationSchedule destSchedule = train.getRoute().getScheduleForStation(destination);
            
            int sourceOrder = sourceSchedule.getStationOrder();
            int destOrder = destSchedule.getStationOrder();
            
            // Check if enough seats available
            List<Seat> availableSeats = availability.getAvailableSeats(
                seatType, sourceOrder, destOrder, passengers.size()
            );
            
            if (availableSeats.size() < passengers.size()) {
                throw new IllegalStateException("Not enough seats available");
            }
            
            // Calculate fare
            int distance = train.getRoute().getDistance(source, destination);
            double totalAmount = calculateFare(seatType, distance, passengers.size());
            
            // Process payment
            PaymentService.PaymentResult paymentResult = paymentService.processPayment(
                userId, totalAmount, paymentMethod
            );
            
            if (!paymentResult.isSuccess()) {
                throw new IllegalStateException("Payment failed: " + paymentResult.getMessage());
            }
            
            // Create booking
            Booking booking = new Booking(userId, train, source, destination, journeyDate, seatType, totalAmount);
            
            // Assign seats to passengers and book them
            for (int i = 0; i < passengers.size(); i++) {
                Seat seat = availableSeats.get(i);
                boolean booked = availability.bookSeat(seat, sourceOrder, destOrder);
                
                if (!booked) {
                    // Rollback previous bookings
                    for (int j = 0; j < i; j++) {
                        availability.releaseSeat(availableSeats.get(j), sourceOrder, destOrder);
                    }
                    throw new IllegalStateException("Failed to book seat: " + seat);
                }
                
                booking.addPassengerSeatMapping(passengers.get(i), seat);
            }
            
            // Save booking
            bookings.put(booking.getPnr(), booking);
            
            System.out.println("✓ Booking successful! PNR: " + booking.getPnr());
            
            return booking;
        }
    }

    // ========== Cancellation ==========
    
    public boolean cancelBooking(String pnr) {
        synchronized (bookingLock) {
            Booking booking = bookings.get(pnr);
            
            if (booking == null) {
                System.out.println("Booking not found: " + pnr);
                return false;
            }
            
            if (booking.getStatus() == BookingStatus.CANCELLED) {
                System.out.println("Booking already cancelled");
                return false;
            }
            
            // Get seat availability
            SeatAvailability availability = getOrCreateSeatAvailability(
                booking.getTrain(), 
                booking.getJourneyDate()
            );
            
            Route.StationSchedule sourceSchedule = booking.getTrain().getRoute()
                .getScheduleForStation(booking.getSource());
            Route.StationSchedule destSchedule = booking.getTrain().getRoute()
                .getScheduleForStation(booking.getDestination());
            
            int sourceOrder = sourceSchedule.getStationOrder();
            int destOrder = destSchedule.getStationOrder();
            
            // Release all seats
            for (Booking.PassengerSeatMapping mapping : booking.getPassengerSeatMappings()) {
                availability.releaseSeat(mapping.getSeat(), sourceOrder, destOrder);
            }
            
            // Update booking status
            booking.setStatus(BookingStatus.CANCELLED);
            
            // Process refund (simplified)
            double refundAmount = booking.getTotalAmount() * 0.8; // 80% refund
            System.out.println("✓ Booking cancelled. Refund: ₹" + refundAmount);
            
            return true;
        }
    }

    // ========== Query Methods ==========
    
    public Booking getBooking(String pnr) {
        return bookings.get(pnr);
    }

    public List<Booking> getUserBookings(String userId) {
        return bookings.values().stream()
            .filter(booking -> booking.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    // ========== Helper Methods ==========
    
    private SeatAvailability getOrCreateSeatAvailability(Train train, LocalDate date) {
        String key = train.getTrainNumber() + "_" + date.toString();
        return seatAvailabilityMap.computeIfAbsent(key, 
            k -> new SeatAvailability(train, date));
    }

    private double calculateFare(SeatType seatType, int distance, int passengerCount) {
        double basePrice = seatType.getBasePrice();
        double distanceFactor = distance / 100.0; // Price per 100 km
        return basePrice * distanceFactor * passengerCount;
    }

    // ========== Search Result Class ==========
    
    public static class TrainSearchResult {
        private final Train train;
        private final java.time.LocalDateTime departureTime;
        private final java.time.LocalDateTime arrivalTime;
        private final Map<SeatType, Integer> availabilityByClass;

        public TrainSearchResult(Train train, java.time.LocalDateTime departureTime,
                                java.time.LocalDateTime arrivalTime, 
                                Map<SeatType, Integer> availabilityByClass) {
            this.train = train;
            this.departureTime = departureTime;
            this.arrivalTime = arrivalTime;
            this.availabilityByClass = availabilityByClass;
        }

        public Train getTrain() {
            return train;
        }

        public java.time.LocalDateTime getDepartureTime() {
            return departureTime;
        }

        public java.time.LocalDateTime getArrivalTime() {
            return arrivalTime;
        }

        public Map<SeatType, Integer> getAvailabilityByClass() {
            return availabilityByClass;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(train.getTrainNumber()).append(" - ").append(train.getTrainName())
              .append("\n  Departure: ").append(departureTime)
              .append("\n  Arrival: ").append(arrivalTime)
              .append("\n  Availability:");
            
            for (Map.Entry<SeatType, Integer> entry : availabilityByClass.entrySet()) {
                sb.append("\n    ").append(entry.getKey().getCode())
                  .append(": ").append(entry.getValue()).append(" seats");
            }
            
            return sb.toString();
        }
    }
}
