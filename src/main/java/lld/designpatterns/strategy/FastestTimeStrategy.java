package lld.designpatterns.strategy;

import java.util.List;

public final class FastestTimeStrategy implements RouteStrategy {

    @Override
    public RouteResult getRoute(Point origin, Point destination) {
        double dist = 10.0; // assume highway
        double minutes = dist / 1.0; // 60 km/h
        return new RouteResult(List.of(origin, destination), dist, minutes, "FastestTime");
    }
}
