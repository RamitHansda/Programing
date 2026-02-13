package lld.trainbooking;

import java.util.Objects;

/**
 * Represents a railway station in the system
 */
public class Station {
    private final String stationCode;
    private final String stationName;
    private final String city;
    private final String state;

    public Station(String stationCode, String stationName, String city, String state) {
        this.stationCode = stationCode;
        this.stationName = stationName;
        this.city = city;
        this.state = state;
    }

    public String getStationCode() {
        return stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Station station = (Station) o;
        return Objects.equals(stationCode, station.stationCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationCode);
    }

    @Override
    public String toString() {
        return stationName + " (" + stationCode + ")";
    }
}
