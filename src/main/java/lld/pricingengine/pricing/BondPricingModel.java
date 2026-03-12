package lld.pricingengine.pricing;

import lld.pricingengine.model.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * Discounted cash flow (DCF) model for fixed-coupon bonds.
 *
 * PV = Σ [C/m · e^(-r·tᵢ)] + F · e^(-r·T)
 *
 * where C = annual coupon, m = coupon frequency, F = face value, T = maturity.
 *
 * Duration (rho) is expressed as Modified Duration × PV.
 */
public class BondPricingModel implements PricingModel {

    private static final Set<InstrumentType> SUPPORTED = EnumSet.of(InstrumentType.BOND);

    @Override
    public boolean supports(InstrumentType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public String getModelName() {
        return "BOND_DCF";
    }

    @Override
    public PricingResult price(PricingRequest request) {
        long start = System.currentTimeMillis();
        Instrument inst = request.getInstrument();
        MarketData md   = request.getMarketData();

        double r    = md.getRiskFreeRate();
        double T    = inst.getMaturityYears();
        double F    = inst.getFaceValue();
        double c    = inst.getCouponRate();
        int    m    = inst.getCouponFrequencyPerYear();
        double couponPayment = c * F / m;

        double pv = 0.0;
        double dv01 = 0.0;  // Dollar value of 1bp

        int totalPeriods = (int) Math.ceil(T * m);
        for (int i = 1; i <= totalPeriods; i++) {
            double t  = (double) i / m;
            double df = Math.exp(-r * t);
            double cf = (i == totalPeriods) ? couponPayment + F : couponPayment;
            pv   += cf * df;
            dv01 += cf * t * df;  // accumulate duration
        }

        dv01 = dv01 * 0.0001;  // DV01: price change per 1bp rate move

        // Duration as approx rho (sensitivity to 1% rate change)
        double rho   = -dv01 * 100;
        // Convexity (gamma analog for bonds)
        double gamma = computeConvexity(r, T, F, c, m, pv);
        // Daily theta: time decay of bond price
        double theta = computeTheta(r, T, F, c, m, pv);

        Greeks greeks = new Greeks(0.0, gamma, 0.0, theta, rho);

        return PricingResult.builder(inst.getId(), inst.getTicker(), inst.getType())
            .presentValue(pv)
            .greeks(greeks)
            .modelUsed(getModelName())
            .pricingDurationMs(System.currentTimeMillis() - start)
            .build();
    }

    private double computeConvexity(double r, double T, double F, double c, int m, double pv) {
        int totalPeriods = (int) Math.ceil(T * m);
        double couponPayment = c * F / m;
        double convexity = 0.0;
        for (int i = 1; i <= totalPeriods; i++) {
            double t  = (double) i / m;
            double df = Math.exp(-r * t);
            double cf = (i == totalPeriods) ? couponPayment + F : couponPayment;
            convexity += cf * t * t * df;
        }
        return convexity / pv;
    }

    private double computeTheta(double r, double T, double F, double c, int m, double pv) {
        if (T <= 1.0 / 365) return 0;
        double tNew = T - 1.0 / 365;
        int totalPeriods = (int) Math.ceil(tNew * m);
        double couponPayment = c * F / m;
        double pvNew = 0.0;
        for (int i = 1; i <= totalPeriods; i++) {
            double t  = (double) i / m;
            double df = Math.exp(-r * t);
            double cf = (i == totalPeriods) ? couponPayment + F : couponPayment;
            pvNew += cf * df;
        }
        return pvNew - pv;
    }
}
