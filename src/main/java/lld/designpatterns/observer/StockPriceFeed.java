package lld.designpatterns.observer;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Subject: notifies observers when price updates. Observers subscribe with conditions.
 */
public final class StockPriceFeed {

    private final String symbol;
    private final CopyOnWriteArrayList<PriceObserver> observers = new CopyOnWriteArrayList<>();
    private volatile double lastPrice;

    public StockPriceFeed(String symbol) {
        this.symbol = Objects.requireNonNull(symbol);
    }

    public void subscribe(PriceObserver observer) {
        observers.add(Objects.requireNonNull(observer));
    }

    public void unsubscribe(PriceObserver observer) {
        observers.remove(observer);
    }

    public void setPrice(double price) {
        this.lastPrice = price;
        for (PriceObserver o : observers) {
            if (o.getCondition().test(price)) {
                o.onConditionMet(symbol, price);
            }
        }
    }

    public double getLastPrice() { return lastPrice; }
    public String getSymbol() { return symbol; }

    @FunctionalInterface
    public interface PriceCondition {
        boolean test(double price);
    }
}
