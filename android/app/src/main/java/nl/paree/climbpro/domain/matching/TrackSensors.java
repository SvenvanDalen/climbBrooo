package nl.paree.climbpro.domain.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * Power, heart rate and cadence of a recorded track, one entry per track sample (null where
 * the device recorded nothing), so each matched climb pass gets its averages (issues #387,
 * #388). A stream the source lacks entirely yields null averages. Pure; phone-only.
 */
public final class TrackSensors {

    final List<Double> watts = new ArrayList<>();
    final List<Double> heartrate = new ArrayList<>();
    final List<Double> cadence = new ArrayList<>();

    private final boolean hasWatts;
    private final boolean hasHeartrate;
    private final boolean hasCadence;

    public TrackSensors(boolean hasWatts, boolean hasHeartrate, boolean hasCadence) {
        this.hasWatts = hasWatts;
        this.hasHeartrate = hasHeartrate;
        this.hasCadence = hasCadence;
    }

    /** Appends one track sample's readings; null for a missing value. */
    public void add(Double w, Double hr, Double cad) {
        watts.add(w);
        heartrate.add(hr);
        cadence.add(cad);
    }

    /**
     * Average power over samples {@code from..to} inclusive, zeros included (coasting is part
     * of the effort); null without power data there.
     */
    public Integer avgWatts(int from, int to) {
        return hasWatts ? average(watts, from, to, true) : null;
    }

    /** Average heart rate, zero readings (strap dropouts) skipped; null without data. */
    public Integer avgHeartrate(int from, int to) {
        return hasHeartrate ? average(heartrate, from, to, false) : null;
    }

    /** Average cadence while pedalling (zeros skipped, like a bike computer); null without. */
    public Integer avgCadence(int from, int to) {
        return hasCadence ? average(cadence, from, to, false) : null;
    }

    static Integer average(List<Double> values, int from, int to, boolean includeZero) {
        if (from < 0 || to < from) return null;
        double sum = 0;
        int n = 0;
        for (int i = from; i <= to && i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null || Double.isNaN(v) || v < 0) continue;
            if (v == 0 && !includeZero) continue;
            sum += v;
            n++;
        }
        return n > 0 ? (int) Math.round(sum / n) : null;
    }
}
