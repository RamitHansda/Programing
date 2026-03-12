package lld.pricingengine.model;

/**
 * A financial instrument to be priced. Supports equities, options, bonds, and swaps.
 */
public class Instrument {
    private final String id;
    private final String ticker;
    private final InstrumentType type;
    private final double notional;

    // Option-specific
    private final double strikePrice;
    private final double maturityYears;
    private final boolean isCall;

    // Bond-specific
    private final double couponRate;
    private final int couponFrequencyPerYear;
    private final double faceValue;

    private Instrument(Builder builder) {
        this.id                    = builder.id;
        this.ticker                = builder.ticker;
        this.type                  = builder.type;
        this.notional              = builder.notional;
        this.strikePrice           = builder.strikePrice;
        this.maturityYears         = builder.maturityYears;
        this.isCall                = builder.isCall;
        this.couponRate            = builder.couponRate;
        this.couponFrequencyPerYear = builder.couponFrequencyPerYear;
        this.faceValue             = builder.faceValue;
    }

    public String getId()                   { return id; }
    public String getTicker()               { return ticker; }
    public InstrumentType getType()         { return type; }
    public double getNotional()             { return notional; }
    public double getStrikePrice()          { return strikePrice; }
    public double getMaturityYears()        { return maturityYears; }
    public boolean isCall()                 { return isCall; }
    public double getCouponRate()           { return couponRate; }
    public int getCouponFrequencyPerYear()  { return couponFrequencyPerYear; }
    public double getFaceValue()            { return faceValue; }

    public static Builder builder(String id, String ticker, InstrumentType type) {
        return new Builder(id, ticker, type);
    }

    public static class Builder {
        private final String id;
        private final String ticker;
        private final InstrumentType type;
        private double notional      = 1_000_000;
        private double strikePrice   = 0;
        private double maturityYears = 1.0;
        private boolean isCall       = true;
        private double couponRate    = 0.05;
        private int couponFrequencyPerYear = 2;
        private double faceValue     = 1_000_000;

        private Builder(String id, String ticker, InstrumentType type) {
            this.id     = id;
            this.ticker = ticker;
            this.type   = type;
        }

        public Builder notional(double v)              { this.notional = v; return this; }
        public Builder strikePrice(double v)           { this.strikePrice = v; return this; }
        public Builder maturityYears(double v)         { this.maturityYears = v; return this; }
        public Builder isCall(boolean v)               { this.isCall = v; return this; }
        public Builder couponRate(double v)            { this.couponRate = v; return this; }
        public Builder couponFrequency(int v)          { this.couponFrequencyPerYear = v; return this; }
        public Builder faceValue(double v)             { this.faceValue = v; return this; }

        public Instrument build() { return new Instrument(this); }
    }

    @Override
    public String toString() {
        return String.format("Instrument{id=%s, ticker=%s, type=%s}", id, ticker, type);
    }
}
