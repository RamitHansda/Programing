package lld.pricingengine.pricing;

import lld.pricingengine.model.InstrumentType;

import java.util.List;

/**
 * Resolves the correct PricingModel for a given InstrumentType using a priority-ordered chain.
 * Black-Scholes is tried first for vanilla options (faster); Monte Carlo acts as a universal fallback.
 */
public class PricingModelFactory {

    private final List<PricingModel> models;

    public PricingModelFactory() {
        this.models = List.of(
            new EquityPricingModel(),
            new BlackScholesPricingModel(),
            new BondPricingModel(),
            new MonteCarloPricingModel()  // universal fallback
        );
    }

    /**
     * Returns the highest-priority model that supports the given instrument type.
     */
    public PricingModel resolve(InstrumentType type) {
        return models.stream()
            .filter(m -> m.supports(type))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No pricing model for type: " + type));
    }
}
