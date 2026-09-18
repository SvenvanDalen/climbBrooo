package nl.paree.climbpro.domain.climb;

/**
 * Pure presentation logic: a single "how hard does this climb feel" score, used for
 * sorting/badging climbs in the UI. Not part of the wire protocol — phone-only.
 *
 * <p><b>Base difficulty.</b> {@code elevationGainM * avgGradient}. Elevation gain alone
 * already grows with length, so multiplying by the average gradient again rewards
 * steepness quadratically (a 10%-average climb outscores a 5%-average climb of the same
 * elevation gain by 2x, not by a flat amount) — this matches the everyday intuition that
 * steep climbs feel disproportionately harder than long-but-gentle ones of equal height gain.
 *
 * <p><b>Fatigue adjustment.</b> The same climb feels harder the further into a ride it
 * sits. This is modelled as a multiplier on the base score that grows with the cumulative
 * distance already ridden before the climb starts, saturating so it never blows up on very
 * long routes: {@code 1 + FATIGUE_MAX_BONUS * (1 - exp(-distanceIntoRouteKm / FATIGUE_SCALE_KM))}.
 * At zero distance into the route (or when there is no route context at all — a standalone
 * imported climb) the multiplier is exactly 1.0, i.e. neutral: the score degrades gracefully
 * to the base difficulty rather than requiring route context to produce a sane number.
 *
 * <p>Constants live in {@link DifficultyScoreConstants} so they're easy to retune without
 * touching the formula.
 */
public final class DifficultyScoreCalculator {

    private DifficultyScoreCalculator() {}

    /**
     * @param elevationGainM      the climb's own elevation gain, in metres (>= 0).
     * @param avgGradient         the climb's own average gradient as a fraction (0.072 = 7.2%).
     * @param distanceIntoRouteKm cumulative distance ridden before this climb starts, in km.
     *                            Pass 0 (or any value <= 0) for a standalone climb with no
     *                            route context — the fatigue term then degrades to neutral (1.0).
     * @return a non-negative difficulty score; higher means harder. Not normalised to any
     *         fixed range — only meaningful relative to other scores from this same calculator.
     */
    public static double score(int elevationGainM, double avgGradient, double distanceIntoRouteKm) {
        double base = Math.max(0, elevationGainM) * Math.max(0, avgGradient);
        double fatigueMultiplier = fatigueMultiplier(distanceIntoRouteKm);
        return base * fatigueMultiplier;
    }

    /** Convenience overload taking a {@link Climb} directly, using its own startDistance as fatigue proxy. */
    public static double score(Climb climb, double distanceIntoRouteKm) {
        return score(climb.elevationGain, climb.avgGradient, distanceIntoRouteKm);
    }

    /**
     * The fatigue multiplier alone, exposed for callers that want to display or test it
     * independently of the base score. Always in {@code [1.0, 1 + FATIGUE_MAX_BONUS]}.
     */
    public static double fatigueMultiplier(double distanceIntoRouteKm) {
        if (distanceIntoRouteKm <= 0 || Double.isNaN(distanceIntoRouteKm)) return 1.0;
        double saturation = 1.0 - Math.exp(-distanceIntoRouteKm / DifficultyScoreConstants.FATIGUE_SCALE_KM);
        return 1.0 + DifficultyScoreConstants.FATIGUE_MAX_BONUS * saturation;
    }
}
