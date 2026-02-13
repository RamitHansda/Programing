package lld.trainbooking;

import java.util.Objects;

/**
 * Represents a seat in a train coach
 */
public class Seat {
    private final String seatNumber;
    private final SeatType seatType;
    private final String coachNumber;

    public Seat(String seatNumber, SeatType seatType, String coachNumber) {
        this.seatNumber = seatNumber;
        this.seatType = seatType;
        this.coachNumber = coachNumber;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    public String getCoachNumber() {
        return coachNumber;
    }

    public String getFullSeatId() {
        return coachNumber + "-" + seatNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Seat seat = (Seat) o;
        return Objects.equals(seatNumber, seat.seatNumber) &&
                Objects.equals(coachNumber, seat.coachNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seatNumber, coachNumber);
    }

    @Override
    public String toString() {
        return coachNumber + "-" + seatNumber + " (" + seatType.getCode() + ")";
    }
}
