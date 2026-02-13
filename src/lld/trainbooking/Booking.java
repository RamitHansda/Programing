package lld.trainbooking;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a booking made by a user
 */
public class Booking {
    private final String pnr;
    private final String userId;
    private final Train train;
    private final Station source;
    private final Station destination;
    private final LocalDate journeyDate;
    private final List<PassengerSeatMapping> passengerSeatMappings;
    private BookingStatus status;
    private final LocalDateTime bookingTime;
    private final double totalAmount;
    private final SeatType seatType;

    public Booking(String userId, Train train, Station source, Station destination, 
                   LocalDate journeyDate, SeatType seatType, double totalAmount) {
        this.pnr = generatePNR();
        this.userId = userId;
        this.train = train;
        this.source = source;
        this.destination = destination;
        this.journeyDate = journeyDate;
        this.seatType = seatType;
        this.passengerSeatMappings = new ArrayList<>();
        this.status = BookingStatus.CONFIRMED;
        this.bookingTime = LocalDateTime.now();
        this.totalAmount = totalAmount;
    }

    private String generatePNR() {
        return "PNR" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
    }

    public void addPassengerSeatMapping(Passenger passenger, Seat seat) {
        passengerSeatMappings.add(new PassengerSeatMapping(passenger, seat));
    }

    public String getPnr() {
        return pnr;
    }

    public String getUserId() {
        return userId;
    }

    public Train getTrain() {
        return train;
    }

    public Station getSource() {
        return source;
    }

    public Station getDestination() {
        return destination;
    }

    public LocalDate getJourneyDate() {
        return journeyDate;
    }

    public List<PassengerSeatMapping> getPassengerSeatMappings() {
        return Collections.unmodifiableList(passengerSeatMappings);
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDateTime getBookingTime() {
        return bookingTime;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    /**
     * Inner class to map passenger to their seat
     */
    public static class PassengerSeatMapping {
        private final Passenger passenger;
        private final Seat seat;

        public PassengerSeatMapping(Passenger passenger, Seat seat) {
            this.passenger = passenger;
            this.seat = seat;
        }

        public Passenger getPassenger() {
            return passenger;
        }

        public Seat getSeat() {
            return seat;
        }
    }

    @Override
    public String toString() {
        return "PNR: " + pnr + ", Train: " + train.getTrainNumber() + 
               ", " + source.getStationCode() + " -> " + destination.getStationCode() +
               ", Date: " + journeyDate + ", Status: " + status;
    }
}
