package lld.pricingengine.model;

import java.time.Instant;
import java.util.UUID;

public class PricingRequest {
    private final String requestId;
    private final Instrument instrument;
    private final MarketData marketData;
    private final int numSimulations;
    private final Instant valuationTime;

    public PricingRequest(Instrument instrument, MarketData marketData, int numSimulations) {
        this.requestId      = UUID.randomUUID().toString();
        this.instrument     = instrument;
        this.marketData     = marketData;
        this.numSimulations = numSimulations;
        this.valuationTime  = Instant.now();
    }

    public String getRequestId()        { return requestId; }
    public Instrument getInstrument()   { return instrument; }
    public MarketData getMarketData()   { return marketData; }
    public int getNumSimulations()      { return numSimulations; }
    public Instant getValuationTime()   { return valuationTime; }
}
