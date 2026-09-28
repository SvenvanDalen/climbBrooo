package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time in heart-rate and power zones per ride (issue #218), from the histograms that
 * {@link ZoneHistogramAnalyzer} stored. Heart rate uses five zones as a share of max heart rate;
 * power uses Coggan's seven zones as a share of FTP. Also sums the last
 * {@link #TOTAL_WINDOW_DAYS} days.
 */
public final class ZoneCalculator {

    private ZoneCalculator() {}

    /** Upper bounds of heart-rate zones 1-4 as a fraction of max heart rate; zone 5 is above. */
    public static final double[] HR_ZONE_UPPER = {0.60, 0.70, 0.80, 0.90};
    /** Upper bounds of Coggan power zones 1-6 as a fraction of FTP; zone 7 is above. */
    public static final double[] POWER_ZONE_UPPER = {0.55, 0.75, 0.90, 1.05, 1.20, 1.50};

    public static final int TOTAL_WINDOW_DAYS = 28;

    /** Time above a heart rate needed before it counts as the rider's max (not a spike). */
    static final int MAX_HR_MIN_SEC = 30;

    public static final class Entry {
        public final StoredRide ride;
        /** Seconds per heart-rate zone (5); null without heart rate or max heart rate. */
        public final int[] hrZones;
        /** Seconds per power zone (7); null without power, FTP, or on an e-bike. */
        public final int[] powerZones;

        Entry(StoredRide ride, int[] hrZones, int[] powerZones) {
            this.ride = ride;
            this.hrZones = hrZones;
            this.powerZones = powerZones;
        }
    }

    public static final class Result {
        /** Rides with at least one distribution, newest first. */
        public final List<Entry> entries;
        /** Sums over the last {@link #TOTAL_WINDOW_DAYS} days; null when nothing counted. */
        public final int[] recentHrZones;
        public final int[] recentPowerZones;

        Result(List<Entry> entries, int[] recentHrZones, int[] recentPowerZones) {
            this.entries = entries;
            this.recentHrZones = recentHrZones;
            this.recentPowerZones = recentPowerZones;
        }
    }

    /**
     * @param maxHr rider's max heart rate, 0 when unknown (then no heart-rate zones)
     * @param ftp   rider's FTP in watts, 0 when unknown (then no power zones)
     */
    public static Result compute(List<StoredRide> rides, List<StoredRideStreamStats> stats,
                                 int maxHr, int ftp, long nowEpochSec) {
        Map<Long, StoredRideStreamStats> byId = new HashMap<>();
        if (stats != null) {
            for (StoredRideStreamStats s : stats) if (s != null) byId.put(s.activityId, s);
        }
        List<Entry> entries = new ArrayList<>();
        if (rides != null) {
            for (StoredRide r : rides) {
                StoredRideStreamStats s = r != null ? byId.get(r.activityId) : null;
                if (s == null) continue;
                int[] hr = heartRateZones(s.hrSecondsPerBpm, maxHr);
                int[] power = RideRecordsCalculator.isEBike(r.type)
                        ? null : powerZones(s.powerSecondsPer10W, ftp);
                if (hr != null || power != null) entries.add(new Entry(r, hr, power));
            }
        }
        entries.sort((a, b) -> Long.compare(b.ride.startEpochSec, a.ride.startEpochSec));

        long cutoff = nowEpochSec - TOTAL_WINDOW_DAYS * 86_400L;
        int[] hrSum = null;
        int[] powerSum = null;
        for (Entry e : entries) {
            if (e.ride.startEpochSec < cutoff || e.ride.startEpochSec > nowEpochSec) continue;
            hrSum = add(hrSum, e.hrZones);
            powerSum = add(powerSum, e.powerZones);
        }
        return new Result(entries, hrSum, powerSum);
    }

    /** Seconds per heart-rate zone, or null without a histogram, max heart rate or any time. */
    public static int[] heartRateZones(int[] secondsPerBpm, int maxHr) {
        if (secondsPerBpm == null || maxHr <= 0) return null;
        int[] zones = new int[HR_ZONE_UPPER.length + 1];
        for (int i = 0; i < secondsPerBpm.length; i++) {
            int bpm = ZoneHistogramAnalyzer.HR_MIN_BPM + i;
            zones[zoneOf(bpm / (double) maxHr, HR_ZONE_UPPER)] += secondsPerBpm[i];
        }
        return total(zones) > 0 ? zones : null;
    }

    /**
     * Seconds per power zone, or null without a histogram, FTP or any time. Each 10 W bin is
     * placed by its middle, so a zone boundary is off by at most 5 W.
     */
    public static int[] powerZones(int[] secondsPer10W, int ftp) {
        if (secondsPer10W == null || ftp <= 0) return null;
        int[] zones = new int[POWER_ZONE_UPPER.length + 1];
        for (int i = 0; i < secondsPer10W.length; i++) {
            double watts = i * ZoneHistogramAnalyzer.POWER_BIN_WATTS
                    + ZoneHistogramAnalyzer.POWER_BIN_WATTS / 2.0;
            zones[zoneOf(watts / ftp, POWER_ZONE_UPPER)] += secondsPer10W[i];
        }
        return total(zones) > 0 ? zones : null;
    }

    /**
     * The highest heart rate held for at least {@link #MAX_HR_MIN_SEC} seconds in any ride, as a
     * default max heart rate when the rider hasn't set one; 0 without heart-rate data. It
     * underestimates a true max unless the rider went all-out at some point.
     */
    public static int observedMaxHr(List<StoredRideStreamStats> stats) {
        int best = 0;
        if (stats == null) return 0;
        for (StoredRideStreamStats s : stats) {
            int[] h = s != null ? s.hrSecondsPerBpm : null;
            if (h == null) continue;
            int above = 0;
            for (int i = h.length - 1; i >= 0; i--) {
                above += h[i];
                if (above >= MAX_HR_MIN_SEC) {
                    best = Math.max(best, ZoneHistogramAnalyzer.HR_MIN_BPM + i);
                    break;
                }
            }
        }
        return best;
    }

    public static int total(int[] zones) {
        int sum = 0;
        if (zones != null) for (int z : zones) sum += z;
        return sum;
    }

    private static int zoneOf(double fraction, double[] upper) {
        for (int k = 0; k < upper.length; k++) if (fraction < upper[k]) return k;
        return upper.length;
    }

    private static int[] add(int[] sum, int[] zones) {
        if (zones == null) return sum;
        if (sum == null) sum = new int[zones.length];
        for (int k = 0; k < zones.length; k++) sum[k] += zones[k];
        return sum;
    }
}
