package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import java.util.Locale;

/**
 * Decides whether an archived ride was wet enough to warrant a bike/chain cleaning reminder
 * (issue #234). Road rides: ≥ {@link #ROAD_MIN_MM} from one hour before the start to the end.
 * Off-road rides (gravel/MTB, from the Strava type — the only cheap "modder" signal we have):
 * ≥ {@link #OFFROAD_MIN_MM} from 24 h before the start, since wet trails stay muddy. Pure.
 */
public final class WetRideDetector {

    public static final double ROAD_MIN_MM = 1.0;
    public static final double OFFROAD_MIN_MM = 0.5;
    public static final long ROAD_LEAD_SEC = 3600L;
    public static final long OFFROAD_LEAD_SEC = 24L * 3600L;
    /** Rides that ended longer ago than this are never checked (no stale or backfill reminders). */
    public static final long MAX_AGE_SEC = 3L * 24L * 3600L;
    private static final int FALLBACK_DURATION_SEC = 3600;
    private static final Locale NL = new Locale("nl");

    private WetRideDetector() {}

    /** Result of {@link #judge}; {@code mm} is the precipitation summed over the ride window. */
    public static final class Verdict {
        public final boolean wet;
        public final boolean offroad;
        public final double mm;

        public Verdict(boolean wet, boolean offroad, double mm) {
            this.wet = wet;
            this.offroad = offroad;
            this.mm = mm;
        }
    }

    public static boolean isOffroad(String type) {
        return "GravelRide".equals(type) || "MountainBikeRide".equals(type)
                || "EMountainBikeRide".equals(type);
    }

    /** Ride end: start + elapsed time, else moving time, else one hour. */
    public static long endSec(StoredRide r) {
        int dur = r.elapsedTimeSec > 0 ? r.elapsedTimeSec
                : r.movingTimeSec > 0 ? r.movingTimeSec : FALLBACK_DURATION_SEC;
        return r.startEpochSec + dur;
    }

    /** Outdoor ride with a GPS start and a known date that ended within {@link #MAX_AGE_SEC}. */
    public static boolean isCandidate(StoredRide r, long nowSec) {
        if (r == null || r.startEpochSec <= 0 || r.startLat == null || r.startLon == null) {
            return false;
        }
        if ("VirtualRide".equals(r.type)) return false;
        long end = endSec(r);
        return end <= nowSec && nowSec - end <= MAX_AGE_SEC;
    }

    /** Null when the weather has no value for any hour of the ride window (retry later). */
    public static Verdict judge(StoredRide r, HourlyPrecipitation p) {
        boolean offroad = isOffroad(r.type);
        long lead = offroad ? OFFROAD_LEAD_SEC : ROAD_LEAD_SEC;
        double mm = p.sumBetween(r.startEpochSec - lead, endSec(r));
        if (Double.isNaN(mm)) return null;
        return new Verdict(mm >= (offroad ? OFFROAD_MIN_MM : ROAD_MIN_MM), offroad, mm);
    }

    public static String title(String rideName) {
        return rideName == null || rideName.trim().isEmpty()
                ? "Natte rit: fiets schoonmaken"
                : "Natte rit: " + rideName.trim();
    }

    public static String message(boolean offroad, double mm) {
        String amount = String.format(NL, "%.1f", mm);
        String lead = offroad
                ? "Er viel " + amount + " mm regen rond je rit: kans op modder."
                : "Het regende tijdens je rit (" + amount + " mm).";
        return lead + " Spoel het vuil van je fiets, maak de ketting schoon en droog "
                + "en smeer hem daarna opnieuw.";
    }
}
