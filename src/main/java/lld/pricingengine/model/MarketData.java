package lld.pricingengine.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of market data required for pricing: spot prices, implied volatilities,
 * risk-free rates, and dividend yields.
 */
public class MarketData {
    private final Map<String, Double> spotPrices;
    private final Map<String, Double> impliedVols;
    private final double riskFreeRate;
    private final Map<String, Double> dividendYields;
    private final long snapshotEpochMs;

    private MarketData(Builder builder) {
        this.spotPrices      = Collections.unmodifiableMap(new HashMap<>(builder.spotPrices));
        this.impliedVols     = Collections.unmodifiableMap(new HashMap<>(builder.impliedVols));
        this.riskFreeRate    = builder.riskFreeRate;
        this.dividendYields  = Collections.unmodifiableMap(new HashMap<>(builder.dividendYields));
        this.snapshotEpochMs = System.currentTimeMillis();
    }

    public double spotPrice(String ticker) {
        return spotPrices.getOrDefault(ticker, 100.0);
    }

    public double impliedVol(String ticker) {
        return impliedVols.getOrDefault(ticker, 0.20);
    }

    public double dividendYield(String ticker) {
        return dividendYields.getOrDefault(ticker, 0.0);
    }

    public double getRiskFreeRate()    { return riskFreeRate; }
    public long getSnapshotEpochMs()   { return snapshotEpochMs; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final Map<String, Double> spotPrices     = new HashMap<>();
        private final Map<String, Double> impliedVols    = new HashMap<>();
        private final Map<String, Double> dividendYields = new HashMap<>();
        private double riskFreeRate = 0.05;

        public Builder spot(String ticker, double price) {
            spotPrices.put(ticker, price); return this;
        }
        public Builder vol(String ticker, double sigma) {
            impliedVols.put(ticker, sigma); return this;
        }
        public Builder dividend(String ticker, double q) {
            dividendYields.put(ticker, q); return this;
        }
        public Builder riskFreeRate(double r) {
            this.riskFreeRate = r; return this;
        }
        public MarketData build() { return new MarketData(this); }
    }
}
