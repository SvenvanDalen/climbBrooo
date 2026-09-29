package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredIntervalBlock;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.Locale;

/**
 * An interval block attached to a climb (issue #180): ride the whole climb, foot to top, inside
 * a power band expressed as percent of FTP. Outdoors the watch starts it at the climb-start
 * trigger and shows the band; indoors it drives the climb steps of the .zwo/.erg export. The
 * band is stored in % FTP so it follows FTP changes; absolute watts are only computed when a
 * payload or workout is built. Immutable and pure.
 */
public final class IntervalBlock {

    /** Presets (Dutch labels) plus a custom target. */
    public enum Preset {
        DREMPEL("Drempel", 95, 100),
        SWEET_SPOT("Sweet spot", 88, 93),
        VO2MAX("VO2max", 110, 120),
        TEMPO("Tempo", 80, 85),
        CUSTOM("Eigen doel", 0, 0);

        public final String label;
        final int lowPct;
        final int highPct;

        Preset(String label, int lowPct, int highPct) {
            this.label = label;
            this.lowPct = lowPct;
            this.highPct = highPct;
        }
    }

    /** Custom target: the band is target ± this many percent points of FTP. */
    public static final int CUSTOM_HALF_BAND_PCT = 3;
    /** Sensible custom target range: easy tempo up to short VO2max/anaerobic efforts. */
    public static final int MIN_TARGET_PCT = 50;
    public static final int MAX_TARGET_PCT = 150;
    /** Outer bounds a stored band must respect to be trusted. */
    static final int MIN_BAND_PCT = MIN_TARGET_PCT - CUSTOM_HALF_BAND_PCT;
    static final int MAX_BAND_PCT = MAX_TARGET_PCT + CUSTOM_HALF_BAND_PCT;

    public final Preset preset;
    public final int lowPct;
    public final int highPct;

    private IntervalBlock(Preset preset, int lowPct, int highPct) {
        this.preset = preset;
        this.lowPct = lowPct;
        this.highPct = highPct;
    }

    public static IntervalBlock of(Preset preset) {
        if (preset == null || preset == Preset.CUSTOM) {
            throw new IllegalArgumentException("use custom(targetPct) for a custom block");
        }
        return new IntervalBlock(preset, preset.lowPct, preset.highPct);
    }

    public static IntervalBlock custom(int targetPct) {
        if (targetPct < MIN_TARGET_PCT || targetPct > MAX_TARGET_PCT) {
            throw new IllegalArgumentException("target % FTP out of range: " + targetPct);
        }
        return new IntervalBlock(Preset.CUSTOM,
                targetPct - CUSTOM_HALF_BAND_PCT, targetPct + CUSTOM_HALF_BAND_PCT);
    }

    /** Validated block from storage, or null when absent or out of range. */
    public static IntervalBlock fromStored(StoredIntervalBlock s) {
        if (s == null || s.lowPct < MIN_BAND_PCT || s.highPct > MAX_BAND_PCT
                || s.lowPct > s.highPct) {
            return null;
        }
        Preset p = Preset.CUSTOM;
        if (s.preset != null) {
            try {
                p = Preset.valueOf(s.preset);
            } catch (IllegalArgumentException unknown) {
                p = Preset.CUSTOM;
            }
        }
        return new IntervalBlock(p, s.lowPct, s.highPct);
    }

    public StoredIntervalBlock toStored() {
        StoredIntervalBlock s = new StoredIntervalBlock();
        s.preset = preset.name();
        s.lowPct = lowPct;
        s.highPct = highPct;
        return s;
    }

    /** Middle of the band as a fraction of FTP (0.975 for Drempel). */
    public double targetFraction() {
        return (lowPct + highPct) / 200.0;
    }

    /**
     * Wire value 'ib': {@code [targetWatts, lowWatts, highWatts]} at this FTP, or null when the
     * FTP is unknown (the watch then shows nothing rather than a band around 0 W).
     */
    public int[] wireWatts(int ftpWatts) {
        if (ftpWatts <= 0) return null;
        return new int[]{
                (int) Math.round(ftpWatts * targetFraction()),
                (int) Math.round(ftpWatts * lowPct / 100.0),
                (int) Math.round(ftpWatts * highPct / 100.0)};
    }

    /** "Drempel · 95–100 % FTP". */
    public String label() {
        return String.format(Locale.ROOT, "%s · %d–%d %% FTP", preset.label, lowPct, highPct);
    }

    /**
     * Stable signature of every climb's block in the route, so a changed block makes the
     * background sync resend the route. Empty for a null route.
     */
    public static String signature(StoredRoute route) {
        if (route == null || route.climbs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < route.climbs.size(); i++) {
            StoredClimb c = route.climbs.get(i);
            IntervalBlock b = c == null ? null : fromStored(c.intervalBlock);
            if (b == null) continue;
            sb.append(i).append(':').append(b.lowPct).append('-').append(b.highPct).append(';');
        }
        return sb.toString();
    }
}
