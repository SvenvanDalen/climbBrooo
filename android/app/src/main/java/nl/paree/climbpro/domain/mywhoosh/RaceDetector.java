package nl.paree.climbpro.domain.mywhoosh;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.rider.WeightHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Races and events among the MyWhoosh rides (issue #407), so they get their own results list
 * instead of mixing with training in the progress views. A ride is a race when its name says
 * so, or when it was ridden at race intensity (IF ≥ {@link #RACE_INTENSITY_FACTOR}) with the
 * ride's hardest 5 s in the first {@link #START_PEAK_WINDOW_SEC} — the start sprint of a mass
 * start. Pure; phone-only.
 */
public final class RaceDetector {

    private RaceDetector() {}

    static final Pattern NAME_PATTERN =
            Pattern.compile("(?i)\\b(race|event|wedstrijd|criterium|crit)\\b");
    public static final double RACE_INTENSITY_FACTOR = 0.90;
    public static final int START_PEAK_WINDOW_SEC = 180;

    /** Index of the 1 and 5 min entries in {@code PowerCurveAnalyzer.DURATIONS_SEC}. */
    private static final int CURVE_1_MIN = 1;
    private static final int CURVE_5_MIN = 2;

    public enum Reason { NAME, INTENSITY }

    public static final class Race {
        public final StoredRide ride;
        public final Reason reason;
        /** Average W/kg over the ride with that day's weight; null without power or weight. */
        public final Double avgWattsPerKg;
        /** Best 1 and 5 min power; null without a power curve. */
        public final Integer peak1MinWatts;
        public final Integer peak5MinWatts;
        /** Elapsed time, the race clock; moving time when Strava had none. */
        public final int timeSec;

        Race(StoredRide ride, Reason reason, Double avgWattsPerKg, Integer peak1MinWatts,
             Integer peak5MinWatts, int timeSec) {
            this.ride = ride;
            this.reason = reason;
            this.avgWattsPerKg = avgWattsPerKg;
            this.peak1MinWatts = peak1MinWatts;
            this.peak5MinWatts = peak5MinWatts;
            this.timeSec = timeSec;
        }
    }

    /** Why {@code ride} is a race, or null when it is a training ride. */
    public static Reason reason(StoredRide ride, StoredRideStreamStats stats, int ftpWatts) {
        if (ride == null) return null;
        if (ride.name != null && NAME_PATTERN.matcher(ride.name).find()) return Reason.NAME;
        RideIntensity intensity = RideIntensity.of(ride, stats, ftpWatts);
        if (intensity == null || intensity.intensityFactor < RACE_INTENSITY_FACTOR) return null;
        boolean startPeak = stats != null && stats.sprint5sAtSec != null
                && stats.sprint5sAtSec <= START_PEAK_WINDOW_SEC;
        return startPeak ? Reason.INTENSITY : null;
    }

    /** The races among {@code rides}, in the given order. */
    public static List<Race> detect(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                    int ftpWatts, WeightHistory weights) {
        List<Race> out = new ArrayList<>();
        if (rides == null) return out;
        for (StoredRide r : rides) {
            StoredRideStreamStats s = stats != null ? stats.get(r.activityId) : null;
            Reason why = reason(r, s, ftpWatts);
            if (why == null) continue;
            Integer avg = r.avgWatts != null && r.avgWatts > 0 ? Math.round(r.avgWatts) : null;
            Double wkg = weights != null ? weights.wattsPerKg(avg, r.startEpochSec) : null;
            out.add(new Race(r, why, wkg, curve(s, CURVE_1_MIN), curve(s, CURVE_5_MIN),
                    r.elapsedTimeSec > 0 ? r.elapsedTimeSec : r.movingTimeSec));
        }
        return out;
    }

    private static Integer curve(StoredRideStreamStats s, int index) {
        if (s == null || s.powerCurve == null || s.powerCurve.length <= index) return null;
        int w = s.powerCurve[index];
        return w > 0 ? w : null;
    }
}
