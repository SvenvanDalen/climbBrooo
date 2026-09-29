package nl.paree.climbpro.domain.power;

/**
 * Issue #47: effect of head- or tailwind on a climb's time. Pure, phone-only.
 *
 * For every segment the wind is projected onto the direction of travel (segment bearing from
 * the route geometry), scaled from the forecast's 10 m height down to rider height, and fed
 * into the aero term of {@link PowerSpeedSolver}. Both the windless and the wind-corrected time
 * are computed at the same fixed pedal power (the power the existing estimate already chose),
 * so the delta isolates the wind: a rider holding the same watts is slower into the wind and
 * faster with it. Drag grows with the square of the air speed, so a headwind costs more than
 * the same tailwind saves, and switchbacks don't cancel out completely.
 */
public final class WindImpactEstimator {

    /**
     * Wind at ~1.5 m above ground relative to the 10 m forecast value. Log wind profile over
     * open, rough terrain gives roughly 0.6-0.75; 0.7 is a middle-of-the-road choice.
     */
    public static final double WIND_HEIGHT_FACTOR = 0.7;
    /** Below this many seconds the wind is reported as having hardly any influence. */
    public static final int NEGLIGIBLE_SECONDS = 5;

    public enum Verdict { HEADWIND, TAILWIND, NEGLIGIBLE }

    public static final class Result {
        /** Model time without wind at the given power (sum of rounded segment seconds). */
        public final int windlessSeconds;
        /** Model time with the wind at the same power. */
        public final int windSeconds;
        /** windSeconds - windlessSeconds: positive = slower (headwind), negative = faster. */
        public final int deltaSeconds;
        /** Distance-weighted headwind component at 10 m (km/h); negative = tailwind. */
        public final double meanHeadwindKmh;
        /** Forecast wind speed at 10 m (km/h). */
        public final double windKmh;
        /** Direction the wind comes from (degrees, meteorological convention). */
        public final double windFromDeg;

        Result(int windlessSeconds, int windSeconds, double meanHeadwindKmh,
               double windKmh, double windFromDeg) {
            this.windlessSeconds = windlessSeconds;
            this.windSeconds = windSeconds;
            this.deltaSeconds = windSeconds - windlessSeconds;
            this.meanHeadwindKmh = meanHeadwindKmh;
            this.windKmh = windKmh;
            this.windFromDeg = windFromDeg;
        }

        public Verdict verdict() { return WindImpactEstimator.verdict(deltaSeconds); }
    }

    private WindImpactEstimator() {}

    /**
     * Component of the wind blowing against a rider heading {@code travelBearingDeg}.
     * {@code windFromDeg} is where the wind comes from, so wind from straight ahead gives the
     * full speed. Unknown inputs (NaN) give 0: no correction rather than a guess.
     */
    public static double headwindComponentKmh(double windKmh, double windFromDeg,
                                              double travelBearingDeg) {
        if (Double.isNaN(windKmh) || Double.isNaN(windFromDeg) || Double.isNaN(travelBearingDeg)) {
            return 0;
        }
        return windKmh * Math.cos(Math.toRadians(windFromDeg - travelBearingDeg));
    }

    /**
     * Returns null when there is nothing meaningful to say: no wind data, no usable bearing on
     * any segment, empty or mismatched arrays, or a non-positive power. Segments with an
     * unknown (NaN) bearing are ridden windless.
     */
    public static Result estimate(int[] dist, double[] grad, int[] surface, double[] bearingsDeg,
                                  double totalMassKg, double powerWatts,
                                  double windKmh, double windFromDeg) {
        int n = dist.length;
        if (n == 0 || grad.length != n || surface.length != n || bearingsDeg.length != n) {
            return null;
        }
        if (Double.isNaN(windKmh) || Double.isNaN(windFromDeg) || !(powerWatts > 0)) {
            return null;
        }
        int windless = 0;
        int windy = 0;
        double weightedHead = 0;
        double knownDistance = 0;
        for (int i = 0; i < n; i++) {
            double crr = SurfaceRollingResistance.crr(surface[i]);
            double headKmh = headwindComponentKmh(windKmh, windFromDeg, bearingsDeg[i]);
            double headMps = headKmh / 3.6 * WIND_HEIGHT_FACTOR;
            double vStill = PowerSpeedSolver.speedMetersPerSecond(
                    powerWatts, totalMassKg, grad[i], crr);
            double vWind = PowerSpeedSolver.speedMetersPerSecond(
                    powerWatts, totalMassKg, grad[i], crr, headMps);
            windless += (int) Math.round(dist[i] / vStill);
            windy += (int) Math.round(dist[i] / vWind);
            if (!Double.isNaN(bearingsDeg[i])) {
                weightedHead += headKmh * dist[i];
                knownDistance += dist[i];
            }
        }
        if (knownDistance <= 0) {
            return null;
        }
        return new Result(windless, windy, weightedHead / knownDistance, windKmh, windFromDeg);
    }

    /** 8-point compass sector for a direction: 0 = N, 1 = NE, ... 7 = NW. */
    public static int compassSector(double deg) {
        double norm = ((deg % 360) + 360) % 360;
        return (int) Math.floor((norm + 22.5) / 45.0) % 8;
    }

    public static Verdict verdict(int deltaSeconds) {
        if (deltaSeconds >= NEGLIGIBLE_SECONDS) return Verdict.HEADWIND;
        if (deltaSeconds <= -NEGLIGIBLE_SECONDS) return Verdict.TAILWIND;
        return Verdict.NEGLIGIBLE;
    }
}
