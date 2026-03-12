package lld.pricingengine.pricing;

import lld.pricingengine.model.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * Closed-form Black-Scholes-Merton model for European vanilla options.
 *
 * Formulae:
 *   d1 = [ln(S/K) + (r - q + σ²/2)·T] / (σ·√T)
 *   d2 = d1 - σ·√T
 *
 *   Call = S·e^(-qT)·N(d1) - K·e^(-rT)·N(d2)
 *   Put  = K·e^(-rT)·N(-d2) - S·e^(-qT)·N(-d1)
 */
public class BlackScholesPricingModel implements PricingModel {

    private static final Set<InstrumentType> SUPPORTED = EnumSet.of(InstrumentType.VANILLA_OPTION);

    @Override
    public boolean supports(InstrumentType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public String getModelName() {
        return "BLACK_SCHOLES";
    }

    @Override
    public PricingResult price(PricingRequest request) {
        long start = System.currentTimeMillis();
        Instrument inst = request.getInstrument();
        MarketData md   = request.getMarketData();

        double S = md.spotPrice(inst.getTicker());
        double K = inst.getStrikePrice();
        double T = inst.getMaturityYears();
        double r = md.getRiskFreeRate();
        double q = md.dividendYield(inst.getTicker());
        double sigma = md.impliedVol(inst.getTicker());

        if (T <= 0 || sigma <= 0) {
            double intrinsic = inst.isCall() ? Math.max(S - K, 0) : Math.max(K - S, 0);
            return buildResult(inst, intrinsic, Greeks.zero(), start);
        }

        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S / K) + (r - q + 0.5 * sigma * sigma) * T) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        double discountFactor    = Math.exp(-r * T);
        double dividendDiscount  = Math.exp(-q * T);

        double pv;
        double delta, gamma, vega, theta, rho;

        double Nd1  = MathUtils.normalCDF(d1);
        double Nd2  = MathUtils.normalCDF(d2);
        double Nnd1 = MathUtils.normalCDF(-d1);
        double Nnd2 = MathUtils.normalCDF(-d2);
        double nd1  = MathUtils.normalPDF(d1);

        if (inst.isCall()) {
            pv    = S * dividendDiscount * Nd1 - K * discountFactor * Nd2;
            delta = dividendDiscount * Nd1;
            theta = (-(S * dividendDiscount * nd1 * sigma) / (2 * sqrtT)
                     - r * K * discountFactor * Nd2
                     + q * S * dividendDiscount * Nd1) / 365.0;
            rho   = K * T * discountFactor * Nd2 / 100.0;
        } else {
            pv    = K * discountFactor * Nnd2 - S * dividendDiscount * Nnd1;
            delta = -dividendDiscount * Nnd1;
            theta = (-(S * dividendDiscount * nd1 * sigma) / (2 * sqrtT)
                     + r * K * discountFactor * Nnd2
                     - q * S * dividendDiscount * Nnd1) / 365.0;
            rho   = -K * T * discountFactor * Nnd2 / 100.0;
        }

        gamma = dividendDiscount * nd1 / (S * sigma * sqrtT);
        vega  = S * dividendDiscount * nd1 * sqrtT / 100.0;  // per 1% vol move

        double scaledPv = pv * inst.getNotional();
        Greeks greeks   = new Greeks(delta, gamma, vega, theta, rho);

        return buildResult(inst, scaledPv, greeks, start);
    }

    private PricingResult buildResult(Instrument inst, double pv, Greeks greeks, long start) {
        return PricingResult.builder(inst.getId(), inst.getTicker(), inst.getType())
            .presentValue(pv)
            .greeks(greeks)
            .modelUsed(getModelName())
            .pricingDurationMs(System.currentTimeMillis() - start)
            .build();
    }
}
