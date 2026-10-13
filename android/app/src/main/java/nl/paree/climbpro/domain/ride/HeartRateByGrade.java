package nl.paree.climbpro.domain.ride;

import nl.paree.climbpro.data.ride.StoredRideStreamStats;

import java.util.Collection;

/**
 * The heart-rate zone the rider usually rides at per gradient class (issue #24), over all
 * analysed rides: seconds and heartbeats per class are summed (so every second weighs the
 * same), averaged to bpm and turned into a zone 1-5 with {@link ZoneCalculator}'s share of
 * max heart rate. Sent to the watch as 'hg', which colors the climb profile by it. Pure.
 */
public final class HeartRateByGrade {

    private HeartRateByGrade() {}

    /** Classes with less riding than this get no zone (0). */
    public static final int MIN_CLASS_SEC = 120;

    /**
     * Zone 1-5 per class (index 0-5), 0 = too little data; null without a max heart rate or
     * when no class has enough data.
     */
    public static int[] zones(Collection<StoredRideStreamStats> stats, int maxHr) {
        if (stats == null || maxHr <= 0) return null;
        int n = CadenceByGradeAnalyzer.CLASSES;
        long[] secs = new long[n];
        long[] beats = new long[n];
        for (StoredRideStreamStats s : stats) {
            if (s == null || s.hrGradeSec == null || s.hrGradeBeats == null
                    || s.hrGradeSec.length != n || s.hrGradeBeats.length != n) {
                continue;
            }
            for (int c = 0; c < n; c++) {
                secs[c] += Math.max(0, s.hrGradeSec[c]);
                beats[c] += Math.max(0, s.hrGradeBeats[c]);
            }
        }
        int[] out = new int[n];
        boolean any = false;
        for (int c = 0; c < n; c++) {
            if (secs[c] < MIN_CLASS_SEC) continue;
            double bpm = beats[c] * 60.0 / secs[c];
            out[c] = ZoneCalculator.heartRateZoneIndex(bpm / maxHr) + 1;
            any = true;
        }
        return any ? out : null;
    }
}
