package lld.designpatterns.strategy;

import java.util.List;

public final class ShortestDistanceStrategy implements RouteStrategy {

    @Override
    public RouteResult getRoute(Point origin, Point destination) {
        double dist = haversineKm(origin.lat(), origin.lon(), destination.lat(), destination.lon());
        double minutes = dist / 0.5; // assume 30 km/h avg
        return new RouteResult(List.of(origin, destination), dist, minutes, "ShortestDistance");
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        // simplified
        return Math.sqrt((lat2 - lat1) * (lat2 - lat1) + (lon2 - lon1) * (lon2 - lon1)) * 111;
    }
}
