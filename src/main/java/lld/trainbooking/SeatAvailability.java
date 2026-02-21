package lld.trainbooking;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages seat availability for a train on a specific date
 * Thread-safe implementation using segment-based locking
 */
public class SeatAvailability {
    private final Train train;
    private final LocalDate journeyDate;
    
    // Map: Seat -> List of (source_station_order, dest_station_order) representing occupied segments
    private final Map<Seat, List<RouteSegment>> seatOccupancy;
    
    // Locks for each seat to ensure thread-safe booking
    private final Map<Seat, Object> seatLocks;

    public SeatAvailability(Train train, LocalDate journeyDate) {
        this.train = train;
        this.journeyDate = journeyDate;
        this.seatOccupancy = new ConcurrentHashMap<>();
        this.seatLocks = new ConcurrentHashMap<>();
        
        // Initialize all seats as available
        for (SeatType seatType : train.getAvailableSeatTypes()) {
            for (Seat seat : train.getSeatsByType(seatType)) {
                seatOccupancy.put(seat, new ArrayList<>());
                seatLocks.put(seat, new Object());
            }
        }
    }

    /**
     * Check if a seat is available for a given route segment
     */
    public boolean isSeatAvailable(Seat seat, int sourceOrder, int destOrder) {
        synchronized (seatLocks.get(seat)) {
            List<RouteSegment> occupiedSegments = seatOccupancy.get(seat);
            
            for (RouteSegment segment : occupiedSegments) {
                // Check if segments overlap
                if (!(destOrder <= segment.getSourceOrder() || sourceOrder >= segment.getDestOrder())) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Book a seat for a given route segment
     */
    public boolean bookSeat(Seat seat, int sourceOrder, int destOrder) {
        synchronized (seatLocks.get(seat)) {
            if (isSeatAvailable(seat, sourceOrder, destOrder)) {
                seatOccupancy.get(seat).add(new RouteSegment(sourceOrder, destOrder));
                return true;
            }
            return false;
        }
    }

    /**
     * Release a seat (used during cancellation)
     */
    public void releaseSeat(Seat seat, int sourceOrder, int destOrder) {
        synchronized (seatLocks.get(seat)) {
            List<RouteSegment> segments = seatOccupancy.get(seat);
            segments.removeIf(segment -> 
                segment.getSourceOrder() == sourceOrder && segment.getDestOrder() == destOrder
            );
        }
    }

    /**
     * Get available seats for a route segment
     */
    public List<Seat> getAvailableSeats(SeatType seatType, int sourceOrder, int destOrder, int count) {
        List<Seat> availableSeats = new ArrayList<>();
        List<Seat> seatsOfType = train.getSeatsByType(seatType);
        
        for (Seat seat : seatsOfType) {
            if (isSeatAvailable(seat, sourceOrder, destOrder)) {
                availableSeats.add(seat);
                if (availableSeats.size() >= count) {
                    break;
                }
            }
        }
        
        return availableSeats;
    }

    /**
     * Get count of available seats for a route segment
     */
    public int getAvailableSeatsCount(SeatType seatType, int sourceOrder, int destOrder) {
        int count = 0;
        List<Seat> seatsOfType = train.getSeatsByType(seatType);
        
        for (Seat seat : seatsOfType) {
            if (isSeatAvailable(seat, sourceOrder, destOrder)) {
                count++;
            }
        }
        
        return count;
    }

    public Train getTrain() {
        return train;
    }

    public LocalDate getJourneyDate() {
        return journeyDate;
    }

    /**
     * Inner class representing a route segment (from one station to another)
     */
    private static class RouteSegment {
        private final int sourceOrder;
        private final int destOrder;

        public RouteSegment(int sourceOrder, int destOrder) {
            this.sourceOrder = sourceOrder;
            this.destOrder = destOrder;
        }

        public int getSourceOrder() {
            return sourceOrder;
        }

        public int getDestOrder() {
            return destOrder;
        }
    }
}
