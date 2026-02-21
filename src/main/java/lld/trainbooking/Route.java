package lld.trainbooking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the route of a train with all stations and timings
 */
public class Route {
    private final List<StationSchedule> stationSchedules;

    public Route() {
        this.stationSchedules = new ArrayList<>();
    }

    public void addStation(Station station, LocalDateTime arrivalTime, 
                          LocalDateTime departureTime, int distanceFromStart) {
        stationSchedules.add(new StationSchedule(
            station, arrivalTime, departureTime, distanceFromStart, stationSchedules.size()
        ));
    }

    public List<StationSchedule> getStationSchedules() {
        return Collections.unmodifiableList(stationSchedules);
    }

    public StationSchedule getScheduleForStation(Station station) {
        return stationSchedules.stream()
            .filter(schedule -> schedule.getStation().equals(station))
            .findFirst()
            .orElse(null);
    }

    public boolean isValidRoute(Station source, Station destination) {
        int sourceIndex = -1;
        int destIndex = -1;

        for (int i = 0; i < stationSchedules.size(); i++) {
            if (stationSchedules.get(i).getStation().equals(source)) {
                sourceIndex = i;
            }
            if (stationSchedules.get(i).getStation().equals(destination)) {
                destIndex = i;
            }
        }

        return sourceIndex != -1 && destIndex != -1 && sourceIndex < destIndex;
    }

    public int getDistance(Station source, Station destination) {
        StationSchedule sourceSchedule = getScheduleForStation(source);
        StationSchedule destSchedule = getScheduleForStation(destination);

        if (sourceSchedule == null || destSchedule == null) {
            return -1;
        }

        return destSchedule.getDistanceFromStart() - sourceSchedule.getDistanceFromStart();
    }

    /**
     * Inner class representing schedule for a single station
     */
    public static class StationSchedule {
        private final Station station;
        private final LocalDateTime arrivalTime;
        private final LocalDateTime departureTime;
        private final int distanceFromStart;
        private final int stationOrder;

        public StationSchedule(Station station, LocalDateTime arrivalTime, 
                             LocalDateTime departureTime, int distanceFromStart, int stationOrder) {
            this.station = station;
            this.arrivalTime = arrivalTime;
            this.departureTime = departureTime;
            this.distanceFromStart = distanceFromStart;
            this.stationOrder = stationOrder;
        }

        public Station getStation() {
            return station;
        }

        public LocalDateTime getArrivalTime() {
            return arrivalTime;
        }

        public LocalDateTime getDepartureTime() {
            return departureTime;
        }

        public int getDistanceFromStart() {
            return distanceFromStart;
        }

        public int getStationOrder() {
            return stationOrder;
        }
    }
}
