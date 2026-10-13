package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;
import nl.paree.climbpro.domain.ride.PowerCurveAnalyzer;
import nl.paree.climbpro.domain.ride.RideRecordsCalculator;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds a completed FTP test (issue #181) among the rides synced from Strava. The result
 * comes back through the normal Strava sync instead of a watch message: the stream analysis
 * already stores each ride's best 20-minute power ({@link StoredRideStreamStats#powerCurve}),
 * and FTP is {@link FtpTestPlan#FTP_OF_TWENTY_MINUTES} of it.
 *
 * <p>A ride counts as a test when it
 * <ul>
 *   <li>is named as one ("FTP" as a word, e.g. the exported MyWhoosh workout or "FTP test")
 *       and started in the last {@link #NAMED_LOOKBACK_DAYS} days, or</li>
 *   <li>started within {@link #EXPORT_WINDOW_DAYS} days after the test was exported and was
 *       a genuine all-out effort: its FTP is at least {@link #MIN_EFFORT_FRACTION} of the
 *       current FTP (an easy ride after exporting isn't a test). The hardest such ride wins.</li>
 * </ul>
 * Either way it needs at least 20 minutes of power, no e-bike, and a plausible result. When
 * both kinds exist, the one that started last wins. A ride the rider already applied or
 * dismissed is never offered again. Pure: the caller decides what to do with the result,
 * and never overwrites the FTP without asking.
 */
public final class FtpTestResultDetector {

    private FtpTestResultDetector() {}

    public static final int EXPORT_WINDOW_DAYS = 14;
    public static final int NAMED_LOOKBACK_DAYS = 30;
    /** An unnamed ride must reach 85 % of the current FTP to count as a maximal effort. */
    public static final double MIN_EFFORT_FRACTION = 0.85;

    private static final long DAY_SEC = 86_400L;
    private static final Pattern NAMED = Pattern.compile("(^|[^\\p{L}])ftp([^\\p{L}]|$)",
            Pattern.CASE_INSENSITIVE);

    public enum Reason { NAMED, AFTER_EXPORT }

    public static final class Result {
        public final StoredRide ride;
        public final int twentyMinuteWatts;
        public final int ftpWatts;
        public final Reason reason;

        Result(StoredRide ride, int twentyMinuteWatts, Reason reason) {
            this.ride = ride;
            this.twentyMinuteWatts = twentyMinuteWatts;
            this.ftpWatts = FtpTestPlan.ftpFromTwentyMinuteWatts(twentyMinuteWatts);
            this.reason = reason;
        }
    }

    /**
     * @param exportedAtEpochSec when the test workout was last exported; 0 if never
     * @param currentFtpWatts    the rider profile's FTP; 0 when not set
     * @param handledActivityId  ride already applied or dismissed; 0 for none
     * @return the detected test, or null
     */
    public static Result detect(List<StoredRide> rides, Map<Long, StoredRideStreamStats> stats,
                                long exportedAtEpochSec, int currentFtpWatts,
                                long handledActivityId, long nowEpochSec) {
        if (rides == null || stats == null) return null;
        int index = twentyMinuteIndex();
        long namedCutoff = nowEpochSec - NAMED_LOOKBACK_DAYS * DAY_SEC;
        long windowEnd = exportedAtEpochSec + EXPORT_WINDOW_DAYS * DAY_SEC;

        Pick named = new Pick();
        Pick window = new Pick();
        for (StoredRide r : rides) {
            int watts = usableTwentyMinuteWatts(r, stats, index, handledActivityId);
            if (watts <= 0) continue;
            int ftp = FtpTestPlan.ftpFromTwentyMinuteWatts(watts);
            if (isNamedTest(r.name) && r.startEpochSec >= namedCutoff) {
                named.keepLatest(r, watts);
            } else if (inExportWindow(r.startEpochSec, exportedAtEpochSec, windowEnd)
                    && isHardEnough(ftp, currentFtpWatts)) {
                window.keepStrongest(r, watts);
            }
        }
        if (named.ride == null && window.ride == null) return null;
        if (window.ride == null
                || (named.ride != null && named.ride.startEpochSec >= window.ride.startEpochSec)) {
            return new Result(named.ride, named.watts, Reason.NAMED);
        }
        return new Result(window.ride, window.watts, Reason.AFTER_EXPORT);
    }

    private static boolean inExportWindow(long startEpochSec, long exportedAtEpochSec,
                                          long windowEnd) {
        return exportedAtEpochSec > 0 && startEpochSec >= exportedAtEpochSec
                && startEpochSec <= windowEnd;
    }

    /** Without a current FTP any effort counts; otherwise it must be a real test effort. */
    private static boolean isHardEnough(int ftp, int currentFtpWatts) {
        return currentFtpWatts <= 0 || ftp >= MIN_EFFORT_FRACTION * currentFtpWatts;
    }

    /** A candidate test ride and its 20-minute power. */
    private static final class Pick {
        StoredRide ride;
        int watts;

        void keepLatest(StoredRide r, int w) {
            if (ride == null || r.startEpochSec > ride.startEpochSec) set(r, w);
        }

        void keepStrongest(StoredRide r, int w) {
            if (ride == null || w > watts) set(r, w);
        }

        private void set(StoredRide r, int w) {
            ride = r;
            watts = w;
        }
    }

    /**
     * The ride's best 20-minute power, or 0 when the ride is excluded (already handled,
     * e-bike), has no power curve that long, or implies an implausible FTP.
     */
    private static int usableTwentyMinuteWatts(StoredRide r,
                                               Map<Long, StoredRideStreamStats> stats,
                                               int index, long handledActivityId) {
        if (r == null || r.activityId == handledActivityId
                || RideRecordsCalculator.isEBike(r.type)) {
            return 0;
        }
        StoredRideStreamStats s = stats.get(r.activityId);
        if (s == null || s.powerCurve == null || s.powerCurve.length <= index) return 0;
        int watts = s.powerCurve[index];
        int ftp = FtpTestPlan.ftpFromTwentyMinuteWatts(watts);
        if (watts <= 0 || ftp > FtpEstimator.MAX_PLAUSIBLE_FTP_WATTS) return 0;
        return watts;
    }

    /** True when the ride name contains "FTP" as a word (any case). */
    public static boolean isNamedTest(String name) {
        return name != null && NAMED.matcher(name).find();
    }

    private static int twentyMinuteIndex() {
        int[] d = PowerCurveAnalyzer.DURATIONS_SEC;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == 20 * 60) return i;
        }
        throw new IllegalStateException("power curve has no 20-minute duration");
    }
}
