package lld.pricingengine.pricing;

import lld.pricingengine.model.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * Monte Carlo simulation for exotic options and any instrument without a closed-form solution.
 *
 * Simulates Geometric Brownian Motion paths:
 *   S(T) = S(0) · exp[(r - q - σ²/2)·T + σ·√T·Z],  Z ~ N(0,1)
 *
 * Greeks are estimated via finite difference (bump-and-reprice).
 */
public class MonteCarloPricingModel implements PricingModel {

    private static final Set<InstrumentType> SUPPORTED = EnumSet.of(
        InstrumentType.EXOTIC_OPTION,
        InstrumentType.INTEREST_RATE_SWAP,
        InstrumentType.CREDIT_DEFAULT_SWAP,
        InstrumentType.VANILLA_OPTION  // fallback when Black-Scholes is not used
    );

    private static final double DELTA_BUMP = 0.01;  // 1% spot bump
    private static final double VEGA_BUMP  = 0.001; // 0.1% vol bump

    @Override
    public boolean supports(InstrumentType type) {
        return SUPPORTED.contains(type);
    }

    @Override
    public String getModelName() {
        return "MONTE_CARLO";
    }

    @Override
    public PricingResult price(PricingRequest request) {
        long start = System.currentTimeMillis();
        Instrument inst = request.getInstrument();
        MarketData md   = request.getMarketData();
        int N           = request.getNumSimulations();

        double S     = md.spotPrice(inst.getTicker());
        double K     = inst.getStrikePrice();
        double T     = inst.getMaturityYears();
        double r     = md.getRiskFreeRate();
        double q     = md.dividendYield(inst.getTicker());
        double sigma = md.impliedVol(inst.getTicker());

        Random rng = new Random(42L);  // seeded for reproducibility in tests

        double[] payoffs = simulatePaths(S, K, T, r, q, sigma, N, inst.isCall(), rng);
        double discount  = Math.exp(-r * T);
        double pv        = discount * mean(payoffs) * inst.getNotional();

        // Bump-and-reprice for delta and gamma
        double[] payoffsUp   = simulatePaths(S * (1 + DELTA_BUMP), K, T, r, q, sigma, N, inst.isCall(), new Random(42L));
        double[] payoffsDown = simulatePaths(S * (1 - DELTA_BUMP), K, T, r, q, sigma, N, inst.isCall(), new Random(42L));
        double pvUp   = discount * mean(payoffsUp)   * inst.getNotional();
        double pvDown = discount * mean(payoffsDown) * inst.getNotional();

        double dS    = S * DELTA_BUMP;
        double delta = (pvUp - pvDown) / (2 * dS);
        double gamma = (pvUp - 2 * pv + pvDown) / (dS * dS);

        // Vega: bump volatility
        double[] payoffsVolUp = simulatePaths(S, K, T, r, q, sigma + VEGA_BUMP, N, inst.isCall(), new Random(42L));
        double pvVolUp = discount * mean(payoffsVolUp) * inst.getNotional();
        double vega    = (pvVolUp - pv) / (VEGA_BUMP * 100);  // per 1% vol

        // Theta: one-day time decay
        double[] payoffsTheta = T > 1.0/365 ?
            simulatePaths(S, K, T - 1.0/365, r, q, sigma, N, inst.isCall(), new Random(42L)) : payoffs;
        double pvTheta = discount * mean(payoffsTheta) * inst.getNotional();
        double theta   = pvTheta - pv;

        // Simulated P&L distribution (for VaR): reuse GBM paths
        double[] simulatedPnLs = buildPnLDistribution(S, K, T, r, q, sigma, N, inst, discount);

        Greeks greeks = new Greeks(delta, gamma, vega, theta, 0.0);

        return PricingResult.builder(inst.getId(), inst.getTicker(), inst.getType())
            .presentValue(pv)
            .greeks(greeks)
            .simulatedPnLs(simulatedPnLs)
            .modelUsed(getModelName())
            .pricingDurationMs(System.currentTimeMillis() - start)
            .build();
    }

    /**
     * Simulates terminal spot prices under GBM and returns option payoffs.
     */
    private double[] simulatePaths(double S, double K, double T, double r, double q,
                                   double sigma, int N, boolean isCall, Random rng) {
        double drift    = (r - q - 0.5 * sigma * sigma) * T;
        double diffusion = sigma * Math.sqrt(T);
        double[] payoffs = new double[N];

        for (int i = 0; i < N; i += 2) {
            double u1 = rng.nextDouble();
            double u2 = rng.nextDouble();
            double z1 = MathUtils.boxMuller(u1, u2);
            double z2 = -z1;  // antithetic variates for variance reduction

            double sT1 = S * Math.exp(drift + diffusion * z1);
            double sT2 = S * Math.exp(drift + diffusion * z2);

            payoffs[i]   = isCall ? Math.max(sT1 - K, 0) : Math.max(K - sT1, 0);
            if (i + 1 < N) {
                payoffs[i + 1] = isCall ? Math.max(sT2 - K, 0) : Math.max(K - sT2, 0);
            }
        }
        return payoffs;
    }

    /**
     * Builds a P&L distribution by comparing today's PV to PV under each simulated scenario.
     * Used downstream for VaR and CVaR calculations.
     */
    private double[] buildPnLDistribution(double S, double K, double T, double r, double q,
                                          double sigma, int N, Instrument inst, double discount) {
        double drift     = (r - q - 0.5 * sigma * sigma) * (1.0 / 252);
        double diffusion = sigma * Math.sqrt(1.0 / 252);
        Random rng       = new Random(99L);
        double basePv    = discount * Math.max(S - K, 0) * inst.getNotional();

        double[] pnls = new double[N];
        for (int i = 0; i < N; i++) {
            double z  = rng.nextGaussian();
            double sT = S * Math.exp(drift + diffusion * z);
            double scenPv = discount * (inst.isCall() ? Math.max(sT - K, 0) : Math.max(K - sT, 0))
                            * inst.getNotional();
            pnls[i] = scenPv - basePv;
        }
        Arrays.sort(pnls);
        return pnls;
    }

    private double mean(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }
}
