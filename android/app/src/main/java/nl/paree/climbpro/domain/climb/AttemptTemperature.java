package nl.paree.climbpro.domain.climb;

import java.util.List;
import java.util.Locale;

/**
 * Extreme-temperature note for a climb attempt (issue #80). Pure, phone-only.
 *
 * <p>The temperature comes from the Strava {@code temp} activity stream (the recording device's
 * own sensor, whole °C). Many devices don't record temperature at all — then the attempt simply
 * has no temperature ({@code null}) and no note is shown; that is never an error.
 *
 * <p>Caveat: a device sensor mounted in direct sun (handlebar, top tube) typically reads several
 * degrees above the real air temperature, and body heat can bias a wrist-worn device. That is why
 * the heat threshold is deliberately on the high side — the note is context for a disappointing
 * time, not a weather report.
 *
 * <p>Attempts matched before this existed have no temperature: there is no backfill, because it
 * would mean re-fetching the streams of every already-synced activity from Strava.
 */
public final class AttemptTemperature {

    /** Average ≥ this (°C) over the pass counts as riding in heat. */
    public static final double HEAT_THRESHOLD_C = 30.0;
    /** Average ≤ this (°C) over the pass counts as riding in cold. */
    public static final double COLD_THRESHOLD_C = 5.0;

    public enum Kind { NONE, HEAT, COLD }

    private AttemptTemperature() {}

    /**
     * Average of {@code temps[entryIdx..exitIdx]} (both inclusive — the matcher's pass spans
     * entry sample through exit sample). {@code null} entries (no reading for that sample) are
     * skipped; indices past the end of {@code temps} (a shorter temp stream) are ignored.
     *
     * @return average rounded to 0.1 °C, or {@code null} when there is no reading at all in the
     *         range (absent/empty/too-short stream, invalid range).
     */
    public static Double averageOverPass(List<? extends Number> temps, int entryIdx, int exitIdx) {
        if (temps == null || entryIdx < 0 || exitIdx < entryIdx) return null;
        int last = Math.min(exitIdx, temps.size() - 1);
        double sum = 0;
        int n = 0;
        for (int i = entryIdx; i <= last; i++) {
            Number t = temps.get(i);
            if (t == null) continue;
            double v = t.doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) continue;
            sum += v;
            n++;
        }
        if (n == 0) return null;
        return Math.round(sum / n * 10.0) / 10.0;
    }

    public static Kind classify(Double avgTempC) {
        if (avgTempC == null) return Kind.NONE;
        if (avgTempC >= HEAT_THRESHOLD_C) return Kind.HEAT;
        if (avgTempC <= COLD_THRESHOLD_C) return Kind.COLD;
        return Kind.NONE;
    }

    /**
     * Dutch display note, e.g. {@code "🔥 Heet (32 °C)"} / {@code "❄️ Koud (3 °C)"}, or
     * {@code null} when the temperature is unknown or not extreme.
     */
    public static String label(Double avgTempC) {
        Kind kind = classify(avgTempC);
        if (kind == Kind.NONE) return null;
        String deg = String.format(Locale.ROOT, "%d °C", Math.round(avgTempC));
        return kind == Kind.HEAT ? "🔥 Heet (" + deg + ")" : "❄️ Koud (" + deg + ")";
    }
}
