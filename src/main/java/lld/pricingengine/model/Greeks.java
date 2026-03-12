package lld.pricingengine.model;

/**
 * First and second order sensitivities of a derivative's price to its inputs.
 */
public class Greeks {
    private final double delta;  // dV/dS
    private final double gamma;  // d²V/dS²
    private final double vega;   // dV/dσ (per 1% vol move)
    private final double theta;  // dV/dt (daily decay)
    private final double rho;    // dV/dr

    public Greeks(double delta, double gamma, double vega, double theta, double rho) {
        this.delta = delta;
        this.gamma = gamma;
        this.vega = vega;
        this.theta = theta;
        this.rho = rho;
    }

    public static Greeks zero() {
        return new Greeks(0, 0, 0, 0, 0);
    }

    public Greeks add(Greeks other) {
        return new Greeks(
            this.delta + other.delta,
            this.gamma + other.gamma,
            this.vega  + other.vega,
            this.theta + other.theta,
            this.rho   + other.rho
        );
    }

    public double getDelta() { return delta; }
    public double getGamma() { return gamma; }
    public double getVega()  { return vega;  }
    public double getTheta() { return theta; }
    public double getRho()   { return rho;   }

    @Override
    public String toString() {
        return String.format("Greeks{delta=%.4f, gamma=%.6f, vega=%.4f, theta=%.4f, rho=%.4f}",
            delta, gamma, vega, theta, rho);
    }
}
