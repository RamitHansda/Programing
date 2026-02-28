package lld.designpatterns.strategy;

import java.util.Objects;

/**
 * Context: uses the selected strategy to compute route. Strategy is interchangeable at runtime.
 */
public final class RouteCalculator {

    private RouteStrategy strategy;

    public RouteCalculator(RouteStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy);
    }

    public void setStrategy(RouteStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy);
    }

    public RouteStrategy.RouteResult getRoute(RouteStrategy.Point origin, RouteStrategy.Point destination) {
        return strategy.getRoute(origin, destination);
    }
}
