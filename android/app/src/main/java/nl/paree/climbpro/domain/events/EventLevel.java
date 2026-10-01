package nl.paree.climbpro.domain.events;

import nl.paree.climbpro.data.events.CyclingEvent;
import nl.paree.climbpro.data.ride.StoredRide;

import java.util.List;

/**
 * Does an event suit the rider (issue #241)? Compares the event's route options with the
 * longest ride and the most climbing in one ride over the last {@link #WINDOW_DAYS} days
 * (Strava rides, virtual rides excluded). Pure.
 */
public final class EventLevel {

    public static final int WINDOW_DAYS = 90;
    /** Up to this multiple of what you rode recently still "fits". */
    static final double FIT_FACTOR = 1.2;
    /** Up to this multiple is a "challenge"; beyond it is a big step up. */
    static final double CHALLENGE_FACTOR = 1.5;

    public enum Fit { FITS, CHALLENGE, TOO_HARD, UNKNOWN }

    /** The rider's recent capacity: longest ride (km) and most elevation in one ride (m). */
    public static final class Capacity {
        public final double longestKm;
        public final double mostElevationM;

        public Capacity(double longestKm, double mostElevationM) {
            this.longestKm = longestKm;
            this.mostElevationM = mostElevationM;
        }

        public boolean known() {
            return longestKm > 0;
        }
    }

    /** Result: the fit plus the route option (km) it was judged on, 0 when unknown. */
    public static final class Result {
        public final Fit fit;
        public final int optionKm;

        Result(Fit fit, int optionKm) {
            this.fit = fit;
            this.optionKm = optionKm;
        }
    }

    private EventLevel() {}

    public static Capacity capacity(List<StoredRide> rides, long nowEpochSec) {
        double km = 0;
        double elev = 0;
        long from = nowEpochSec - WINDOW_DAYS * 86400L;
        if (rides != null) {
            for (StoredRide r : rides) {
                if (r == null || r.startEpochSec < from || r.startEpochSec > nowEpochSec) continue;
                if ("VirtualRide".equals(r.type) || "VirtualRide".equals(r.sportType)) continue;
                km = Math.max(km, r.distanceM / 1000.0);
                elev = Math.max(elev, r.elevationGainM);
            }
        }
        return new Capacity(km, elev);
    }

    /**
     * Judges the longest route option that still fits; when none fits, the shortest option.
     * Elevation is only compared for the longest option (that is what the text describes)
     * and only when both sides know it.
     */
    public static Result judge(CyclingEvent e, Capacity c) {
        if (e == null || c == null || !c.known() || e.distancesKm == null
                || e.distancesKm.isEmpty()) {
            return new Result(Fit.UNKNOWN, 0);
        }
        int longest = e.distancesKm.get(e.distancesKm.size() - 1);
        for (int i = e.distancesKm.size() - 1; i >= 0; i--) {
            int km = e.distancesKm.get(i);
            Fit f = fitFor(km, km == longest ? e.elevationM : null, c);
            if (f == Fit.FITS) return new Result(Fit.FITS, km);
        }
        int shortest = e.distancesKm.get(0);
        return new Result(fitFor(shortest, shortest == longest ? e.elevationM : null, c), shortest);
    }

    private static Fit fitFor(int km, Integer elevationM, Capacity c) {
        double ratio = km / c.longestKm;
        if (elevationM != null && c.mostElevationM > 0) {
            ratio = Math.max(ratio, elevationM / c.mostElevationM);
        }
        if (ratio <= FIT_FACTOR) return Fit.FITS;
        if (ratio <= CHALLENGE_FACTOR) return Fit.CHALLENGE;
        return Fit.TOO_HARD;
    }
}
