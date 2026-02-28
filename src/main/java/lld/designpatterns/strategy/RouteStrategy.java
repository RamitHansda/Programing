package lld.designpatterns.strategy;

import java.util.List;

/**
 * Strategy: interchangeable algorithm for computing a route (shortest, fastest, avoid tolls, etc.).
 */
public interface RouteStrategy {

    RouteResult getRoute(Point origin, Point destination);

    record Point(double lat, double lon) {}
    record RouteResult(List<Point> path, double distanceKm, double estimatedMinutes, String strategyName) {}
}
