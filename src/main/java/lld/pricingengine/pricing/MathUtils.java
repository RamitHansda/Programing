package lld.pricingengine.pricing;

/**
 * Numerical utilities shared across pricing models.
 */
final class MathUtils {

    private MathUtils() {}

    /** Cumulative standard normal distribution (Abramowitz & Stegun approximation). */
    static double normalCDF(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        double cdf = 1.0 - normalPDF(x) * poly;
        return x >= 0 ? cdf : 1.0 - cdf;
    }

    /** Standard normal probability density function. */
    static double normalPDF(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    /** Box-Muller transform: samples N(0,1) from two uniform random variables. */
    static double boxMuller(double u1, double u2) {
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
