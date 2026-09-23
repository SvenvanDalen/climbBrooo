package nl.paree.climbpro.domain.advice;

/**
 * Result of {@link TirePressureAdvisor}: a suggested tire pressure range (PSI, the common
 * unit on pumps/gauges) plus a short Dutch rationale. Pure data — no Android dependency.
 */
public final class TirePressureAdvice {

    private static final double PSI_PER_BAR = 14.5037738;

    public final int minPsi;
    public final int maxPsi;
    public final String rationale;

    public TirePressureAdvice(int minPsi, int maxPsi, String rationale) {
        this.minPsi = minPsi;
        this.maxPsi = maxPsi;
        this.rationale = rationale;
    }

    public double minBar() { return minPsi / PSI_PER_BAR; }
    public double maxBar() { return maxPsi / PSI_PER_BAR; }
}
