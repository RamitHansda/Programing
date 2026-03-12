package lld.pricingengine.pricing;

import lld.pricingengine.model.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * Simple mark-to-market model for equities: PV = spot × quantity × notional.
 * Delta = 1 (equity moves 1:1 with spot), no other sensitivities.
 */
public class EquityPricingModel implements PricingModel {

    private static final Set<InstrumentType> SUPPORTED = EnumSet.of(InstrumentType.EQUITY);

    @Override
    public boolean supports(InstrumentType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public String getModelName() {
        return "EQUITY_MTM";
    }

    @Override
    public PricingResult price(PricingRequest request) {
        long start = System.currentTimeMillis();
        Instrument inst = request.getInstrument();
        MarketData md   = request.getMarketData();

        double spot = md.spotPrice(inst.getTicker());
        double pv   = spot * inst.getNotional();
        Greeks greeks = new Greeks(1.0, 0.0, 0.0, 0.0, 0.0);

        return PricingResult.builder(inst.getId(), inst.getTicker(), inst.getType())
            .presentValue(pv)
            .greeks(greeks)
            .modelUsed(getModelName())
            .pricingDurationMs(System.currentTimeMillis() - start)
            .build();
    }
}
