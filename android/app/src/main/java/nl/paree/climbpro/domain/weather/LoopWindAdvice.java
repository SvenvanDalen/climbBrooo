package nl.paree.climbpro.domain.weather;

/**
 * Result of {@link LoopWindAdvisor} (issue #174): which way round a loop to ride so the wind
 * helps on the way home. Pure data; the UI turns the verdict into text.
 */
public final class LoopWindAdvice {

    public enum Verdict {
        /** Too few usable points to judge the route. */
        NO_ROUTE,
        /** Start and finish are too far apart for a loop; direction is fixed. */
        NOT_A_LOOP,
        /** No usable wind forecast. */
        NO_WIND,
        /** Wind too light to matter. */
        CALM,
        /** Both directions end with about the same wind. */
        EITHER,
        /** Ride the route as drawn. */
        FORWARD,
        /** Ride the route the other way round. */
        REVERSE
    }

    public final Verdict verdict;
    /**
     * Headwind component (km/h) on the home stretch riding the route as drawn, weighted toward
     * the finish; negative means tailwind. NaN when not scored.
     */
    public final double forwardHomeHeadwindKmh;
    /** Same for the reversed direction. */
    public final double reverseHomeHeadwindKmh;
    /** Direction the wind comes from, degrees (0 = north); NaN when unknown. */
    public final double windFromDeg;
    public final double windKmh;
    /** Straight-line distance between start and finish in metres; NaN when not measured. */
    public final double loopGapM;

    public LoopWindAdvice(Verdict verdict, double forwardHomeHeadwindKmh,
                          double reverseHomeHeadwindKmh, double windFromDeg, double windKmh,
                          double loopGapM) {
        this.verdict = verdict;
        this.forwardHomeHeadwindKmh = forwardHomeHeadwindKmh;
        this.reverseHomeHeadwindKmh = reverseHomeHeadwindKmh;
        this.windFromDeg = windFromDeg;
        this.windKmh = windKmh;
        this.loopGapM = loopGapM;
    }

    /** Home-stretch headwind for the recommended direction (or as drawn when no preference). */
    public double bestHomeHeadwindKmh() {
        return verdict == Verdict.REVERSE ? reverseHomeHeadwindKmh : forwardHomeHeadwindKmh;
    }

    /** Home-stretch headwind for the direction that was not recommended. */
    public double otherHomeHeadwindKmh() {
        return verdict == Verdict.REVERSE ? forwardHomeHeadwindKmh : reverseHomeHeadwindKmh;
    }
}
