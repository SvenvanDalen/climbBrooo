package nl.paree.climbpro.domain.ride;

/**
 * Time spent at each heart rate and power level in one ride (issue #218), as histograms rather
 * than zone totals: zones depend on the rider's max heart rate and FTP, which can change later
 * without re-fetching the streams. {@link ZoneCalculator} maps the histograms onto zones.
 * Pure; called by {@link RideStreamAnalyzer}.
 */
public final class ZoneHistogramAnalyzer {

    private ZoneHistogramAnalyzer() {}

    /** Heart-rate histogram: bin {@code i} holds seconds at {@code HR_MIN_BPM + i} bpm. */
    public static final int HR_MIN_BPM = 40;
    public static final int HR_MAX_BPM = 230;
    /** Power histogram: bin {@code i} holds seconds at {@code i * POWER_BIN_WATTS} to +9 W. */
    public static final int POWER_BIN_WATTS = 10;
    /** Everything from this bin up (1500 W) is lumped into the last one. */
    static final int POWER_MAX_BIN = 150;
    /**
     * A sample counts for the time until the next one, up to this gap; longer gaps are pauses
     * (auto-pause or a stop) and don't count towards any zone.
     */
    static final int MAX_GAP_SEC = 10;

    /** Seconds per bpm from {@link #HR_MIN_BPM}, trimmed after the last non-zero bin; or null. */
    public static int[] heartRateSeconds(RideStreams s) {
        if (s == null || !s.isUsable() || s.heartrate == null) return null;
        int[] bins = new int[HR_MAX_BPM - HR_MIN_BPM + 1];
        for (int i = 0; i + 1 < s.time.length; i++) {
            int dt = s.time[i + 1] - s.time[i];
            double hr = s.heartrate[i];
            if (dt <= 0 || dt > MAX_GAP_SEC || Double.isNaN(hr)) continue;
            int bpm = (int) Math.round(hr);
            if (bpm < HR_MIN_BPM || bpm > HR_MAX_BPM) continue;
            bins[bpm - HR_MIN_BPM] += dt;
        }
        return trim(bins);
    }

    /**
     * Seconds per {@link #POWER_BIN_WATTS} W bin, trimmed after the last non-zero bin; or null.
     * Coasting at 0 W counts (bin 0), as in any zone analysis; glitches above
     * {@link SprintAnalyzer#MAX_PLAUSIBLE_WATTS} are skipped.
     */
    public static int[] powerSeconds(RideStreams s) {
        if (s == null || !s.isUsable() || s.watts == null) return null;
        int[] bins = new int[POWER_MAX_BIN + 1];
        for (int i = 0; i + 1 < s.time.length; i++) {
            int dt = s.time[i + 1] - s.time[i];
            double w = s.watts[i];
            if (dt <= 0 || dt > MAX_GAP_SEC || Double.isNaN(w) || w < 0
                    || w > SprintAnalyzer.MAX_PLAUSIBLE_WATTS) continue;
            bins[Math.min(POWER_MAX_BIN, (int) (w / POWER_BIN_WATTS))] += dt;
        }
        return trim(bins);
    }

    private static int[] trim(int[] bins) {
        int last = bins.length - 1;
        while (last >= 0 && bins[last] == 0) last--;
        if (last < 0) return null;
        int[] out = new int[last + 1];
        System.arraycopy(bins, 0, out, 0, out.length);
        return out;
    }
}
