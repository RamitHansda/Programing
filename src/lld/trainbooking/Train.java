package lld.trainbooking;

import java.time.LocalDate;
import java.util.*;

/**
 * Represents a train with its route and seat inventory
 */
public class Train {
    private final String trainNumber;
    private final String trainName;
    private final Route route;
    private final Map<SeatType, List<Seat>> seatsByType;

    public Train(String trainNumber, String trainName, Route route) {
        this.trainNumber = trainNumber;
        this.trainName = trainName;
        this.route = route;
        this.seatsByType = new HashMap<>();
    }

    public void addSeats(SeatType seatType, List<Seat> seats) {
        seatsByType.computeIfAbsent(seatType, k -> new ArrayList<>()).addAll(seats);
    }

    public String getTrainNumber() {
        return trainNumber;
    }

    public String getTrainName() {
        return trainName;
    }

    public Route getRoute() {
        return route;
    }

    public List<Seat> getSeatsByType(SeatType seatType) {
        return seatsByType.getOrDefault(seatType, Collections.emptyList());
    }

    public int getTotalSeats(SeatType seatType) {
        return getSeatsByType(seatType).size();
    }

    public Set<SeatType> getAvailableSeatTypes() {
        return seatsByType.keySet();
    }

    @Override
    public String toString() {
        return trainNumber + " - " + trainName;
    }
}
