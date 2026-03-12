package lld.pricingengine.model;

/**
 * Output of a single instrument pricing run: present value, Greeks, and
 * simulated P&L distribution for VaR/CVaR calculations.
 */
public class PricingResult {
    private final String instrumentId;
    private final String ticker;
    private final InstrumentType instrumentType;
    private final double presentValue;
    private final Greeks greeks;
    private final double[] simulatedPnLs;   // Monte Carlo paths (empty for non-MC models)
    private final String modelUsed;
    private final long pricingDurationMs;
    private final boolean success;
    private final String errorMessage;

    private PricingResult(Builder builder) {
        this.instrumentId     = builder.instrumentId;
        this.ticker           = builder.ticker;
        this.instrumentType   = builder.instrumentType;
        this.presentValue     = builder.presentValue;
        this.greeks           = builder.greeks;
        this.simulatedPnLs    = builder.simulatedPnLs;
        this.modelUsed        = builder.modelUsed;
        this.pricingDurationMs = builder.pricingDurationMs;
        this.success          = builder.success;
        this.errorMessage     = builder.errorMessage;
    }

    public String getInstrumentId()       { return instrumentId; }
    public String getTicker()             { return ticker; }
    public InstrumentType getInstrumentType() { return instrumentType; }
    public double getPresentValue()       { return presentValue; }
    public Greeks getGreeks()             { return greeks; }
    public double[] getSimulatedPnLs()    { return simulatedPnLs; }
    public String getModelUsed()          { return modelUsed; }
    public long getPricingDurationMs()    { return pricingDurationMs; }
    public boolean isSuccess()            { return success; }
    public String getErrorMessage()       { return errorMessage; }

    public static Builder builder(String instrumentId, String ticker, InstrumentType type) {
        return new Builder(instrumentId, ticker, type);
    }

    public static class Builder {
        private final String instrumentId;
        private final String ticker;
        private final InstrumentType instrumentType;
        private double presentValue      = 0;
        private Greeks greeks            = Greeks.zero();
        private double[] simulatedPnLs   = new double[0];
        private String modelUsed         = "UNKNOWN";
        private long pricingDurationMs   = 0;
        private boolean success          = true;
        private String errorMessage      = null;

        private Builder(String instrumentId, String ticker, InstrumentType type) {
            this.instrumentId   = instrumentId;
            this.ticker         = ticker;
            this.instrumentType = type;
        }

        public Builder presentValue(double v)         { this.presentValue = v; return this; }
        public Builder greeks(Greeks g)               { this.greeks = g; return this; }
        public Builder simulatedPnLs(double[] pnls)   { this.simulatedPnLs = pnls; return this; }
        public Builder modelUsed(String m)            { this.modelUsed = m; return this; }
        public Builder pricingDurationMs(long ms)     { this.pricingDurationMs = ms; return this; }
        public Builder failed(String error)           { this.success = false; this.errorMessage = error; return this; }

        public PricingResult build() { return new PricingResult(this); }
    }

    @Override
    public String toString() {
        return String.format("PricingResult{id=%s, pv=%.2f, model=%s, success=%s}",
            instrumentId, presentValue, modelUsed, success);
    }
}
