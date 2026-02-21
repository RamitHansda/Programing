package lld.trainbooking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo class to showcase the Train Booking System functionality
 */
public class TrainBookingDemo {
    
    public static void main(String[] args) {
        System.out.println("=== Train Booking System Demo ===\n");
        
        TrainBookingSystem system = TrainBookingSystem.getInstance();
        
        // Setup: Create stations
        setupStations(system);
        
        // Setup: Create trains with routes
        setupTrains(system);
        
        System.out.println("\n--- Scenario 1: Search for trains ---");
        searchTrainsDemo(system);
        
        System.out.println("\n--- Scenario 2: Book tickets ---");
        String pnr1 = bookTicketsDemo(system);
        
        System.out.println("\n--- Scenario 3: Check booking status ---");
        checkBookingDemo(system, pnr1);
        
        System.out.println("\n--- Scenario 4: Multiple concurrent bookings ---");
        concurrentBookingDemo(system);
        
        System.out.println("\n--- Scenario 5: Cancel booking ---");
        cancelBookingDemo(system, pnr1);
        
        System.out.println("\n--- Scenario 6: Booking after cancellation ---");
        bookAfterCancellationDemo(system);
    }

    private static void setupStations(TrainBookingSystem system) {
        system.addStation(new Station("NDLS", "New Delhi", "Delhi", "Delhi"));
        system.addStation(new Station("AGC", "Agra Cantt", "Agra", "Uttar Pradesh"));
        system.addStation(new Station("JHS", "Jhansi Junction", "Jhansi", "Uttar Pradesh"));
        system.addStation(new Station("BPL", "Bhopal Junction", "Bhopal", "Madhya Pradesh"));
        system.addStation(new Station("NGP", "Nagpur", "Nagpur", "Maharashtra"));
        system.addStation(new Station("BSP", "Bilaspur", "Bilaspur", "Chhattisgarh"));
        system.addStation(new Station("HWH", "Howrah Junction", "Kolkata", "West Bengal"));
        
        System.out.println("✓ Stations added to system");
    }

    private static void setupTrains(TrainBookingSystem system) {
        // Train 1: Rajdhani Express (Delhi to Howrah)
        Route rajdhaniRoute = new Route();
        rajdhaniRoute.addStation(system.getStation("NDLS"), 
            LocalDateTime.of(2024, 1, 15, 16, 30),
            LocalDateTime.of(2024, 1, 15, 16, 30), 0);
        rajdhaniRoute.addStation(system.getStation("AGC"),
            LocalDateTime.of(2024, 1, 15, 19, 15),
            LocalDateTime.of(2024, 1, 15, 19, 20), 200);
        rajdhaniRoute.addStation(system.getStation("JHS"),
            LocalDateTime.of(2024, 1, 15, 22, 45),
            LocalDateTime.of(2024, 1, 15, 22, 50), 400);
        rajdhaniRoute.addStation(system.getStation("BPL"),
            LocalDateTime.of(2024, 1, 16, 2, 30),
            LocalDateTime.of(2024, 1, 16, 2, 35), 700);
        rajdhaniRoute.addStation(system.getStation("NGP"),
            LocalDateTime.of(2024, 1, 16, 8, 15),
            LocalDateTime.of(2024, 1, 16, 8, 25), 1100);
        rajdhaniRoute.addStation(system.getStation("BSP"),
            LocalDateTime.of(2024, 1, 16, 14, 20),
            LocalDateTime.of(2024, 1, 16, 14, 30), 1500);
        rajdhaniRoute.addStation(system.getStation("HWH"),
            LocalDateTime.of(2024, 1, 16, 22, 15),
            LocalDateTime.of(2024, 1, 16, 22, 15), 2000);
        
        Train rajdhani = new Train("12301", "Rajdhani Express", rajdhaniRoute);
        
        // Add seats
        List<Seat> ac2Seats = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            ac2Seats.add(new Seat(String.valueOf(i), SeatType.AC_2_TIER, "A1"));
        }
        rajdhani.addSeats(SeatType.AC_2_TIER, ac2Seats);
        
        List<Seat> ac3Seats = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            ac3Seats.add(new Seat(String.valueOf(i), SeatType.AC_3_TIER, "B1"));
        }
        rajdhani.addSeats(SeatType.AC_3_TIER, ac3Seats);
        
        system.addTrain(rajdhani);
        
        // Train 2: Duronto Express
        Route durontoRoute = new Route();
        durontoRoute.addStation(system.getStation("NDLS"),
            LocalDateTime.of(2024, 1, 15, 20, 0),
            LocalDateTime.of(2024, 1, 15, 20, 0), 0);
        durontoRoute.addStation(system.getStation("JHS"),
            LocalDateTime.of(2024, 1, 16, 2, 30),
            LocalDateTime.of(2024, 1, 16, 2, 35), 400);
        durontoRoute.addStation(system.getStation("BPL"),
            LocalDateTime.of(2024, 1, 16, 6, 15),
            LocalDateTime.of(2024, 1, 16, 6, 20), 700);
        durontoRoute.addStation(system.getStation("HWH"),
            LocalDateTime.of(2024, 1, 16, 18, 30),
            LocalDateTime.of(2024, 1, 16, 18, 30), 2000);
        
        Train duronto = new Train("12259", "Duronto Express", durontoRoute);
        
        List<Seat> durontoAc3 = new ArrayList<>();
        for (int i = 1; i <= 80; i++) {
            durontoAc3.add(new Seat(String.valueOf(i), SeatType.AC_3_TIER, "B1"));
        }
        duronto.addSeats(SeatType.AC_3_TIER, durontoAc3);
        
        system.addTrain(duronto);
        
        System.out.println("✓ Trains added to system");
    }

    private static void searchTrainsDemo(TrainBookingSystem system) {
        Station source = system.getStation("NDLS");
        Station dest = system.getStation("BPL");
        LocalDate date = LocalDate.of(2024, 1, 15);
        
        System.out.println("Searching trains from " + source + " to " + dest + " on " + date);
        
        List<TrainBookingSystem.TrainSearchResult> results = system.searchTrains(source, dest, date);
        
        System.out.println("\nFound " + results.size() + " train(s):\n");
        for (TrainBookingSystem.TrainSearchResult result : results) {
            System.out.println(result);
            System.out.println();
        }
    }

    private static String bookTicketsDemo(TrainBookingSystem system) {
        List<Passenger> passengers = new ArrayList<>();
        passengers.add(new Passenger("John Doe", 30, Passenger.Gender.MALE, "AADHAR123"));
        passengers.add(new Passenger("Jane Doe", 28, Passenger.Gender.FEMALE, "AADHAR456"));
        
        try {
            Booking booking = system.bookTickets(
                "user123",
                "12301",
                "NDLS",
                "BPL",
                LocalDate.of(2024, 1, 15),
                SeatType.AC_2_TIER,
                passengers,
                PaymentService.PaymentMethod.UPI
            );
            
            System.out.println("\nBooking Details:");
            System.out.println(booking);
            System.out.println("Total Amount: ₹" + booking.getTotalAmount());
            System.out.println("\nPassenger-Seat Mapping:");
            for (Booking.PassengerSeatMapping mapping : booking.getPassengerSeatMappings()) {
                System.out.println("  " + mapping.getPassenger() + " -> " + mapping.getSeat());
            }
            
            return booking.getPnr();
        } catch (Exception e) {
            System.out.println("Booking failed: " + e.getMessage());
            return null;
        }
    }

    private static void checkBookingDemo(TrainBookingSystem system, String pnr) {
        if (pnr == null) return;
        
        Booking booking = system.getBooking(pnr);
        if (booking != null) {
            System.out.println("Booking found:");
            System.out.println(booking);
        } else {
            System.out.println("Booking not found for PNR: " + pnr);
        }
    }

    private static void concurrentBookingDemo(TrainBookingSystem system) {
        System.out.println("Simulating 3 concurrent booking requests for same train...\n");
        
        List<Thread> threads = new ArrayList<>();
        
        for (int i = 0; i < 3; i++) {
            final int userId = i;
            Thread thread = new Thread(() -> {
                List<Passenger> passengers = new ArrayList<>();
                passengers.add(new Passenger("Passenger" + userId, 25 + userId, 
                    Passenger.Gender.MALE, "ID" + userId));
                
                try {
                    Booking booking = system.bookTickets(
                        "user" + userId,
                        "12301",
                        "NDLS",
                        "JHS",
                        LocalDate.of(2024, 1, 15),
                        SeatType.AC_3_TIER,
                        passengers,
                        PaymentService.PaymentMethod.CREDIT_CARD
                    );
                    System.out.println("Thread " + userId + " - Booked: " + booking.getPnr());
                } catch (Exception e) {
                    System.out.println("Thread " + userId + " - Failed: " + e.getMessage());
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static void cancelBookingDemo(TrainBookingSystem system, String pnr) {
        if (pnr == null) return;
        
        System.out.println("Cancelling booking: " + pnr);
        boolean cancelled = system.cancelBooking(pnr);
        
        if (cancelled) {
            Booking booking = system.getBooking(pnr);
            System.out.println("Updated status: " + booking.getStatus());
        }
    }

    private static void bookAfterCancellationDemo(TrainBookingSystem system) {
        System.out.println("Attempting to book the cancelled seats...\n");
        
        List<Passenger> passengers = new ArrayList<>();
        passengers.add(new Passenger("Alice Smith", 32, Passenger.Gender.FEMALE, "PASS789"));
        
        try {
            Booking booking = system.bookTickets(
                "user456",
                "12301",
                "NDLS",
                "BPL",
                LocalDate.of(2024, 1, 15),
                SeatType.AC_2_TIER,
                passengers,
                PaymentService.PaymentMethod.DEBIT_CARD
            );
            
            System.out.println("Booking successful after cancellation: " + booking.getPnr());
        } catch (Exception e) {
            System.out.println("Booking failed: " + e.getMessage());
        }
    }
}
