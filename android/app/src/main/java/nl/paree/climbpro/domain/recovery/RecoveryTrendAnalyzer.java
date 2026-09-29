package nl.paree.climbpro.domain.recovery;

import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.ride.StoredRide;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recovery trend (issue #183): joins each post-ride check (RPE and sleep) with the archived
 * ride's performance data and compares the most recent checks with the ones before. Pure,
 * no Android dependencies.
 *
 * <p>The comparison window is {@code min(MAX_WINDOW, n / 2)} checks, so a trend shows from
 * four logged rides on. A direction only counts when the averages differ by at least
 * {@link #RPE_THRESHOLD} / {@link #SLEEP_THRESHOLD}; rides feeling harder while sleep gets
 * worse raises {@link Trend#recoveryWarning}.
 */
public final class RecoveryTrendAnalyzer {

    public static final int MIN_RPE = 1;
    public static final int MAX_RPE = 10;
    public static final int MIN_SLEEP = 1;
    public static final int MAX_SLEEP = 5;
    static final float MAX_SLEEP_HOURS = 24f;
    /** Checks per comparison window at most. */
    static final int MAX_WINDOW = 5;
    /** Fewest checks for a recent-vs-previous comparison (two windows of two). */
    static final int MIN_POINTS_FOR_COMPARISON = 4;
    static final double RPE_THRESHOLD = 0.5;
    static final double SLEEP_THRESHOLD = 0.3;

    public enum Direction { NONE, UP, DOWN, STABLE }

    /** One logged ride: the check next to the ride's objective data. */
    public static final class Point {
        public final long rideActivityId;
        public final String rideName;
        public final long startEpochSec;
        public final int rpe;
        public final int sleepQuality;
        public final Float sleepHours;
        public final String note;
        public final double distanceKm;
        public final int movingTimeSec;
        public final double avgSpeedKmh;
        public final float elevationGainM;
        /** Average watts (weighted when a power meter reported it); null without power. */
        public final Integer avgWatts;
        /** Session load (Foster): RPE × moving minutes. */
        public final int sessionLoad;

        Point(RecoveryCheck c, StoredRide r) {
            this.rideActivityId = r.activityId;
            this.rideName = r.name;
            this.startEpochSec = r.startEpochSec;
            this.rpe = clampRpe(c.rpe);
            this.sleepQuality = clampSleepQuality(c.sleepQuality);
            this.sleepHours = clampSleepHours(c.sleepHours);
            this.note = c.note;
            this.distanceKm = r.distanceM / 1000.0;
            this.movingTimeSec = Math.max(0, r.movingTimeSec);
            this.avgSpeedKmh = r.avgSpeedMps * 3.6;
            this.elevationGainM = r.elevationGainM;
            this.avgWatts = r.weightedAvgWatts != null ? r.weightedAvgWatts
                    : r.avgWatts != null ? Integer.valueOf(Math.round(r.avgWatts)) : null;
            this.sessionLoad = sessionLoad(this.rpe, this.movingTimeSec);
        }
    }

    public static final class Trend {
        /** Logged rides, oldest first. */
        public final List<Point> points;
        /** Checks per window; 0 when there are too few for a comparison. */
        public final int windowSize;
        /** Averages over the latest window (or all points without a comparison). */
        public final double rpeAvgRecent;
        public final double sleepAvgRecent;
        /** Averages over the window before; NaN without a comparison. */
        public final double rpeAvgPrevious;
        public final double sleepAvgPrevious;
        public final Direction rpeDirection;
        public final Direction sleepDirection;
        /** Rides feel harder while sleep got worse: time to plan some rest. */
        public final boolean recoveryWarning;

        Trend(List<Point> points, int windowSize, double rpeAvgRecent, double sleepAvgRecent,
              double rpeAvgPrevious, double sleepAvgPrevious,
              Direction rpeDirection, Direction sleepDirection) {
            this.points = points;
            this.windowSize = windowSize;
            this.rpeAvgRecent = rpeAvgRecent;
            this.sleepAvgRecent = sleepAvgRecent;
            this.rpeAvgPrevious = rpeAvgPrevious;
            this.sleepAvgPrevious = sleepAvgPrevious;
            this.rpeDirection = rpeDirection;
            this.sleepDirection = sleepDirection;
            this.recoveryWarning = rpeDirection == Direction.UP && sleepDirection == Direction.DOWN;
        }

        public boolean hasComparison() { return windowSize > 0; }
    }

    private RecoveryTrendAnalyzer() { }

    public static int clampRpe(int rpe) {
        return Math.max(MIN_RPE, Math.min(MAX_RPE, rpe));
    }

    public static int clampSleepQuality(int q) {
        return Math.max(MIN_SLEEP, Math.min(MAX_SLEEP, q));
    }

    /** One decimal, at most 24 h; null for missing, zero, negative or NaN values. */
    public static Float clampSleepHours(Float hours) {
        if (hours == null || hours.isNaN() || hours <= 0f) return null;
        float h = Math.min(MAX_SLEEP_HOURS, hours);
        return Math.round(h * 10f) / 10f;
    }

    /** Foster session RPE: RPE × moving minutes, rounded. */
    public static int sessionLoad(int rpe, int movingTimeSec) {
        if (movingTimeSec <= 0) return 0;
        return (int) Math.round(rpe * movingTimeSec / 60.0);
    }

    /**
     * Joins checks with archived rides (checks for rides no longer in the archive are skipped,
     * a later check for the same ride wins) and computes the trend. Null lists count as empty.
     */
    public static Trend analyze(List<RecoveryCheck> checks, List<StoredRide> rides) {
        Map<Long, StoredRide> rideById = new HashMap<>();
        if (rides != null) {
            for (StoredRide r : rides) if (r != null) rideById.put(r.activityId, r);
        }
        Map<Long, RecoveryCheck> checkByRide = new LinkedHashMap<>();
        if (checks != null) {
            for (RecoveryCheck c : checks) if (c != null) checkByRide.put(c.rideActivityId, c);
        }
        List<Point> points = new ArrayList<>();
        for (RecoveryCheck c : checkByRide.values()) {
            StoredRide r = rideById.get(c.rideActivityId);
            if (r != null) points.add(new Point(c, r));
        }
        points.sort((a, b) -> Long.compare(a.startEpochSec, b.startEpochSec));
        List<Point> frozen = Collections.unmodifiableList(points);

        int n = points.size();
        if (n < MIN_POINTS_FOR_COMPARISON) {
            return new Trend(frozen, 0, avgRpe(points, 0, n), avgSleep(points, 0, n),
                    Double.NaN, Double.NaN, Direction.NONE, Direction.NONE);
        }
        int k = Math.min(MAX_WINDOW, n / 2);
        double rpeRecent = avgRpe(points, n - k, n);
        double rpePrev = avgRpe(points, n - 2 * k, n - k);
        double sleepRecent = avgSleep(points, n - k, n);
        double sleepPrev = avgSleep(points, n - 2 * k, n - k);
        return new Trend(frozen, k, rpeRecent, sleepRecent, rpePrev, sleepPrev,
                direction(rpeRecent - rpePrev, RPE_THRESHOLD),
                direction(sleepRecent - sleepPrev, SLEEP_THRESHOLD));
    }

    private static Direction direction(double delta, double threshold) {
        if (delta >= threshold) return Direction.UP;
        if (delta <= -threshold) return Direction.DOWN;
        return Direction.STABLE;
    }

    private static double avgRpe(List<Point> p, int from, int to) {
        if (to <= from) return Double.NaN;
        double sum = 0;
        for (int i = from; i < to; i++) sum += p.get(i).rpe;
        return sum / (to - from);
    }

    private static double avgSleep(List<Point> p, int from, int to) {
        if (to <= from) return Double.NaN;
        double sum = 0;
        for (int i = from; i < to; i++) sum += p.get(i).sleepQuality;
        return sum / (to - from);
    }
}
