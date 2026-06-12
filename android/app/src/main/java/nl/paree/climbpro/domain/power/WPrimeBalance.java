package nl.paree.climbpro.domain.power;

/**
 * Tracks the anaerobic work-capacity balance W' along a ride.
 *
 * Above CP the balance depletes linearly: dW' = -(P - CP)*dt (clamped at 0).
 * At or below CP it reconstitutes exponentially toward W'max with a fixed time
 * constant tau: W' = W'max - (W'max - W')*exp(-dt/tau). This is the common Skiba
 * simplification with a single recovery time constant.
 */
public final class WPrimeBalance {

    private final double wPrimeMax;
    private final double cp;
    private double current;

    public WPrimeBalance(double wPrimeMax, double cp) {
        this.wPrimeMax = wPrimeMax;
        this.cp = cp;
        this.current = wPrimeMax;
    }

    /** Current W'-balance in joules (0 … wPrimeMax). */
    public double current() {
        return current;
    }

    /** Apply a stretch ridden at constant {@code power} for {@code durationSeconds}. */
    public void applyInterval(double power, double durationSeconds) {
        if (durationSeconds <= 0) {
            return;
        }
        if (power > cp) {
            current -= (power - cp) * durationSeconds;
            if (current < 0) {
                current = 0;
            }
        } else {
            double deficit = wPrimeMax - current;
            current = wPrimeMax - deficit * Math.exp(-durationSeconds / PowerConstants.W_PRIME_TAU_SECONDS);
            if (current > wPrimeMax) {
                current = wPrimeMax;
            }
        }
    }
}
