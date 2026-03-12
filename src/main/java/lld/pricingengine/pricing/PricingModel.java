package lld.pricingengine.pricing;

import lld.pricingengine.model.InstrumentType;
import lld.pricingengine.model.PricingRequest;
import lld.pricingengine.model.PricingResult;

/**
 * Strategy interface for all pricing models. Each implementation encapsulates
 * a specific mathematical model (Black-Scholes, Monte Carlo, DCF, etc.).
 */
public interface PricingModel {
    PricingResult price(PricingRequest request);
    boolean supports(InstrumentType type);
    String getModelName();
}
