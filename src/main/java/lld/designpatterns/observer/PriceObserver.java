package lld.designpatterns.observer;

import java.util.Objects;

/**
 * Observer: subscribed to a feed; notified when condition is met (e.g. price > 150).
 */
public interface PriceObserver {

    StockPriceFeed.PriceCondition getCondition();
    void onConditionMet(String symbol, double price);

    /** Convenience: create observer with condition and notification channel. */
    static PriceObserver create(StockPriceFeed.PriceCondition condition, NotificationChannel channel) {
        return new PriceObserver() {
            @Override
            public StockPriceFeed.PriceCondition getCondition() { return condition; }
            @Override
            public void onConditionMet(String symbol, double price) {
                channel.notify(symbol, price);
            }
        };
    }

    @FunctionalInterface
    interface NotificationChannel {
        void notify(String symbol, double price);
    }
}
