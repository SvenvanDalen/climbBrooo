package nl.paree.climbpro.domain.export;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
import nl.paree.climbpro.domain.power.IntervalBlock;
import nl.paree.climbpro.domain.power.SurfaceRollingResistance;
import nl.paree.climbpro.domain.power.RiderProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns a climb into an indoor workout (issue #223): Zwift ({@code .zwo}, power as a fraction
 * of FTP) or ERG ({@code .erg}, absolute watts per minute), for any trainer app that reads
 * either. Each climb segment becomes one steady block. Its duration comes from the fresh
 * (non-fatigued) climb time estimate, and its target power rises and falls with the segment's
 * gradient around the power that estimate assumes, the way you'd pace the real climb. A
 * 10-minute warm-up ramp comes first and a 5-minute cool-down last. Pure; phone-only.
 *
 * <p>Repeats mode (issue #19): the same file can hold "N× this climb" — the climb's segment
 * blocks N times with an easy recovery block ({@link #RECOVERY_FRACTION} of FTP, default
 * {@link #defaultRecoverySeconds half the climb time}) between each pass, still inside one
 * warm-up and cool-down.
 *
 * <p>Interval block (issue #180): when the climb carries an {@link IntervalBlock}, every climb
 * step holds the block's target instead of following the gradient, and the segment durations
 * are re-estimated at that fixed power. The per-segment gradient messages stay.
 */
public final class ClimbWorkoutWriter {

    private ClimbWorkoutWriter() {}

    /** Power change per unit of gradient difference: +1 % gradient is +2 % power. */
    static final double POWER_PER_GRADIENT = 2.0;
    /** Largest deviation from the climb's average power on any segment (±15 %). */
    static final double MAX_SWING = 0.15;

    static final int WARMUP_SEC = 600;
    static final double WARMUP_LOW = 0.45;
    static final double WARMUP_HIGH = 0.75;
    static final int COOLDOWN_SEC = 300;
    static final double COOLDOWN_HIGH = 0.60;
    static final double COOLDOWN_LOW = 0.40;

    /** Longest workout name we hand MyWhoosh; its library list truncates longer ones anyway. */
    static final int MYWHOOSH_NAME_MAX = 40;

    // Repeats mode (issue #19): "N× this climb" with recovery blocks in between.
    public static final int MIN_REPEATS = 2;
    public static final int MAX_REPEATS = 10;
    public static final int DEFAULT_REPEATS = 5;
    /**
     * Recovery power: 50 % FTP, inside the active-recovery zone (Coggan Z1, below 55 %). Easy
     * enough to flush the legs between efforts, like freewheeling and soft-pedalling a descent.
     */
    public static final double RECOVERY_FRACTION = 0.50;
    /** Default recovery never shorter than 3 min: heart rate needs that long to settle. */
    public static final int MIN_RECOVERY_SEC = 180;
    /** ...and never longer than 10 min, so long climbs don't turn into a half-day session. */
    public static final int MAX_RECOVERY_SEC = 600;

    public static final class Step {
        public final int seconds;
        /** Target power as a fraction of FTP. */
        public final double ftpFraction;
        /** Gradient as a fraction (0.06 = 6 %), shown in the on-screen message. */
        public final double gradient;
        /** True for a recovery block between two repeats (issue #19); gradient is then 0. */
        public final boolean recovery;

        Step(int seconds, double ftpFraction, double gradient) {
            this(seconds, ftpFraction, gradient, false);
        }

        private Step(int seconds, double ftpFraction, double gradient, boolean recovery) {
            this.seconds = seconds;
            this.ftpFraction = ftpFraction;
            this.gradient = gradient;
            this.recovery = recovery;
        }
    }

    public static final class Plan {
        public final List<Step> steps;
        /** Average power over the climb as a fraction of FTP (the estimate's assumption). */
        public final double avgFraction;
        public final int ftpWatts;
        /** {@link IntervalBlock#label()} when the plan follows an interval block, else null. */
        public final String blockLabel;

        Plan(List<Step> steps, double avgFraction, int ftpWatts) {
            this(steps, avgFraction, ftpWatts, null);
        }

        Plan(List<Step> steps, double avgFraction, int ftpWatts, String blockLabel) {
            this.steps = steps;
            this.avgFraction = avgFraction;
            this.ftpWatts = ftpWatts;
            this.blockLabel = blockLabel;
        }
    }

    /**
     * Segment blocks for the climb, or null when the rider profile is incomplete (FTP and
     * weights are needed for the time estimate) or the climb has no segments.
     */
    public static Plan plan(int[] distances, double[] gradients, int[] surfaces,
                            RiderProfile profile) {
        if (distances == null || distances.length == 0 || profile == null
                || !profile.isComplete()) {
            return null;
        }
        ClimbTimeEstimate est = ClimbTimeEstimator.estimate(distances, gradients, surfaces, profile);
        if (est == null || est.totalSeconds <= 0) return null;

        int n = distances.length;
        double avgGradient = 0;
        for (int i = 0; i < n; i++) avgGradient += gradients[i] * est.segmentSeconds[i];
        avgGradient /= est.totalSeconds;

        // Relative factors, then rescaled so the time-weighted average stays at the estimate.
        double[] factor = new double[n];
        double weighted = 0;
        for (int i = 0; i < n; i++) {
            double f = 1 + POWER_PER_GRADIENT * (gradients[i] - avgGradient);
            factor[i] = Math.max(1 - MAX_SWING, Math.min(1 + MAX_SWING, f));
            weighted += factor[i] * est.segmentSeconds[i];
        }
        double scale = est.totalSeconds / weighted;
        double avgFraction = est.assumedPowerWatts / profile.ftpWatts;

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(new Step(Math.max(1, est.segmentSeconds[i]),
                    avgFraction * factor[i] * scale, gradients[i]));
        }
        return new Plan(Collections.unmodifiableList(steps), avgFraction, profile.ftpWatts);
    }

    /**
     * Like {@link #plan(int[], double[], int[], RiderProfile)}, but with an interval block
     * (issue #180) every climb step holds {@link IntervalBlock#targetFraction()} and lasts as
     * long as that segment takes at the block's power. A null block is the default plan.
     */
    public static Plan plan(int[] distances, double[] gradients, int[] surfaces,
                            RiderProfile profile, IntervalBlock block) {
        if (block == null) return plan(distances, gradients, surfaces, profile);
        if (distances == null || distances.length == 0 || profile == null
                || !profile.isComplete()) {
            return null;
        }
        int n = distances.length;
        if (gradients.length != n || surfaces.length != n) {
            throw new IllegalArgumentException(
                    "distances, gradients and surface types must be the same length");
        }
        double[] crr = new double[n];
        for (int i = 0; i < n; i++) crr[i] = SurfaceRollingResistance.crr(surfaces[i]);
        double fraction = block.targetFraction();
        ClimbTimeEstimate est = ClimbTimeEstimator.estimateAtFixedPower(distances, gradients,
                crr, profile.totalMassKg(), fraction * profile.ftpWatts);
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            steps.add(new Step(Math.max(1, est.segmentSeconds[i]), fraction, gradients[i]));
        }
        return new Plan(Collections.unmodifiableList(steps), fraction, profile.ftpWatts,
                block.label());
    }

    /** Total climbing time of one pass up the climb. */
    public static int climbSeconds(List<Step> steps) {
        int total = 0;
        for (Step s : steps) total += s.seconds;
        return total;
    }

    /**
     * Default recovery between repeats (issue #19): half the climb time, rounded to whole
     * minutes and kept within {@link #MIN_RECOVERY_SEC}..{@link #MAX_RECOVERY_SEC}. Half
     * matches both the classic 2:1 work:rest ratio for threshold repeats and roughly how long
     * the descent back to the foot of a hill-repeat climb takes.
     */
    public static int defaultRecoverySeconds(List<Step> steps) {
        int half = (int) Math.round(climbSeconds(steps) / 2.0 / 60.0) * 60;
        return Math.max(MIN_RECOVERY_SEC, Math.min(MAX_RECOVERY_SEC, half));
    }

    /**
     * The main set between warm-up and cool-down: {@code repeats} passes of the climb with a
     * recovery block at {@link #RECOVERY_FRACTION} between each pair (none after the last one,
     * the cool-down follows). One repeat is just the climb.
     */
    public static List<Step> mainSet(List<Step> steps, int repeats, int recoverySec) {
        checkRepeats(repeats, recoverySec);
        List<Step> out = new ArrayList<>();
        for (int r = 0; r < repeats; r++) {
            if (r > 0) out.add(new Step(recoverySec, RECOVERY_FRACTION, 0, true));
            out.addAll(steps);
        }
        return Collections.unmodifiableList(out);
    }

    /** Whole session length in seconds: warm-up, main set and cool-down. */
    public static int totalSeconds(List<Step> steps, int repeats, int recoverySec) {
        return WARMUP_SEC + climbSeconds(mainSet(steps, repeats, recoverySec)) + COOLDOWN_SEC;
    }

    public static String toZwo(String climbName, List<Step> steps) {
        return toZwo(climbName, steps, 1, 0);
    }

    /** Zwift workout of {@code repeats} passes; one repeat is the plain climb export. */
    public static String toZwo(String climbName, List<Step> steps, int repeats, int recoverySec) {
        return toZwo(climbName, steps, repeats, recoverySec, null);
    }

    /**
     * Zwift workout; {@code blockLabel} ({@link Plan#blockLabel}) marks an interval-block plan
     * (issue #180) in the description and tags. Null is the plain gradient-paced export.
     */
    public static String toZwo(String climbName, List<Step> steps, int repeats, int recoverySec,
                               String blockLabel) {
        checkRepeats(repeats, recoverySec);
        String name = displayName(climbName);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<workout_file>\n");
        sb.append("  <author>ClimbPro</author>\n");
        sb.append("  <name>").append(xml(title(name, repeats))).append("</name>\n");
        sb.append("  <description>")
                .append(xml(description(steps, name, repeats, recoverySec, blockLabel)))
                .append("</description>\n");
        sb.append("  <sportType>bike</sportType>\n");
        sb.append("  <tags>\n    <tag name=\"CLIMB\"/>\n");
        if (repeats > 1 || blockLabel != null) sb.append("    <tag name=\"INTERVALS\"/>\n");
        sb.append("  </tags>\n");
        sb.append("  <workout>\n");
        sb.append(String.format(Locale.US,
                "    <Warmup Duration=\"%d\" PowerLow=\"%.2f\" PowerHigh=\"%.2f\"/>\n",
                WARMUP_SEC, WARMUP_LOW, WARMUP_HIGH));
        Locale nl = new Locale("nl");
        for (int r = 0; r < repeats; r++) {
            if (r > 0) {
                zwoSteady(sb, recoverySec, RECOVERY_FRACTION, String.format(nl,
                        "Herstel %d/%d: rustig trappen", r, repeats - 1));
            }
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                String segment = String.format(nl, "%d/%d: %.1f %%",
                        i + 1, steps.size(), s.gradient * 100);
                zwoSteady(sb, s.seconds, s.ftpFraction, repeats > 1
                        ? String.format(nl, "Herhaling %d/%d · segment %s", r + 1, repeats, segment)
                        : "Segment " + segment);
            }
        }
        sb.append(String.format(Locale.US,
                "    <Cooldown Duration=\"%d\" PowerLow=\"%.2f\" PowerHigh=\"%.2f\"/>\n",
                COOLDOWN_SEC, COOLDOWN_HIGH, COOLDOWN_LOW));
        sb.append("  </workout>\n");
        sb.append("</workout_file>\n");
        return sb.toString();
    }

    /**
     * MyWhoosh flavour of the {@code .zwo} (issue #85). MyWhoosh can't import custom routes, but
     * its web workout builder uploads {@code .zwo} files, so the climb goes there as a workout.
     * Its importer is stricter than Zwift's: only plain self-closing warm-up, steady and
     * cool-down steps, power as whole percentages of FTP, no tags or on-screen text events, and
     * a short name. The per-segment gradients move into the description instead.
     */
    public static String toMyWhooshZwo(String climbName, List<Step> steps) {
        return toMyWhooshZwo(climbName, steps, null);
    }

    /** MyWhoosh flavour with an optional interval-block label (issue #180). */
    public static String toMyWhooshZwo(String climbName, List<Step> steps, String blockLabel) {
        String name = "Klim: " + displayName(climbName);
        if (name.length() > MYWHOOSH_NAME_MAX) {
            name = name.substring(0, MYWHOOSH_NAME_MAX - 1).trim() + "…";
        }
        StringBuilder gradients = new StringBuilder();
        for (Step s : steps) {
            if (gradients.length() > 0) gradients.append(" · ");
            gradients.append(String.format(new Locale("nl"), "%.1f %%", s.gradient * 100));
        }
        List<Block> blocks = new ArrayList<>();
        blocks.add(Block.warmup(WARMUP_SEC, WARMUP_LOW, WARMUP_HIGH));
        for (Step s : steps) blocks.add(Block.steady(s.seconds, s.ftpFraction));
        blocks.add(Block.cooldown(COOLDOWN_SEC, COOLDOWN_HIGH, COOLDOWN_LOW));
        return toPlainZwo(name, description(steps, displayName(climbName), 1, 0, blockLabel)
                + " Hellingen per segment: " + gradients, blocks);
    }

    /**
     * One block of a {@link #toPlainZwo plain} {@code .zwo}: a warm-up ramp, a steady block or
     * a cool-down ramp, with power as a fraction of FTP.
     */
    public static final class Block {
        public enum Kind { WARMUP, STEADY, COOLDOWN }

        public final Kind kind;
        public final int seconds;
        /** Power at the start of the block (the only power of a steady block). */
        public final double from;
        /** Power at the end of the block; equal to {@link #from} for a steady block. */
        public final double to;

        private Block(Kind kind, int seconds, double from, double to) {
            this.kind = kind;
            this.seconds = seconds;
            this.from = from;
            this.to = to;
        }

        public static Block warmup(int seconds, double from, double to) {
            return new Block(Kind.WARMUP, seconds, from, to);
        }

        public static Block steady(int seconds, double fraction) {
            return new Block(Kind.STEADY, seconds, fraction, fraction);
        }

        public static Block cooldown(int seconds, double from, double to) {
            return new Block(Kind.COOLDOWN, seconds, from, to);
        }
    }

    /**
     * The lowest common denominator {@code .zwo} (issue #85, reused by the FTP test of issue
     * #181): only self-closing warm-up, steady and cool-down steps with power as fractions of
     * FTP, no tags and no text events. MyWhoosh's web builder accepts it and so does Zwift.
     * The caller keeps {@code name} within {@link #MYWHOOSH_NAME_MAX}. Like Zwift's own files,
     * a ramp's {@code PowerLow} is its start and {@code PowerHigh} its end, so a cool-down
     * reads high-to-low.
     */
    public static String toPlainZwo(String name, String description, List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<workout_file>\n");
        sb.append("  <author>ClimbPro</author>\n");
        sb.append("  <name>").append(xml(name)).append("</name>\n");
        sb.append("  <description>").append(xml(description)).append("</description>\n");
        sb.append("  <sportType>bike</sportType>\n");
        sb.append("  <workout>\n");
        for (Block b : blocks) {
            if (b.kind == Block.Kind.STEADY) {
                sb.append(String.format(Locale.US,
                        "    <SteadyState Duration=\"%d\" Power=\"%.2f\"/>\n", b.seconds, b.from));
            } else {
                sb.append(String.format(Locale.US,
                        "    <%s Duration=\"%d\" PowerLow=\"%.2f\" PowerHigh=\"%.2f\"/>\n",
                        b.kind == Block.Kind.WARMUP ? "Warmup" : "Cooldown",
                        b.seconds, b.from, b.to));
            }
        }
        sb.append("  </workout>\n");
        sb.append("</workout_file>\n");
        return sb.toString();
    }

    /** ERG: absolute watts against cumulative minutes; a step repeats its minute mark. */
    public static String toErg(String climbName, List<Step> steps, int ftpWatts) {
        return toErg(climbName, steps, ftpWatts, 1, 0);
    }

    /** ERG workout of {@code repeats} passes; one repeat is the plain climb export. */
    public static String toErg(String climbName, List<Step> steps, int ftpWatts,
                               int repeats, int recoverySec) {
        return toErg(climbName, steps, ftpWatts, repeats, recoverySec, null);
    }

    /** ERG workout with an optional interval-block label (issue #180). */
    public static String toErg(String climbName, List<Step> steps, int ftpWatts,
                               int repeats, int recoverySec, String blockLabel) {
        List<Step> set = mainSet(steps, repeats, recoverySec);
        String name = displayName(climbName);
        StringBuilder sb = new StringBuilder();
        sb.append("[COURSE HEADER]\n");
        sb.append("VERSION = 2\n");
        sb.append("UNITS = METRIC\n");
        sb.append("DESCRIPTION = ")
                .append(oneLine(description(steps, name, repeats, recoverySec, blockLabel)))
                .append('\n');
        sb.append("FILE NAME = ").append(oneLine(title(name, repeats))).append('\n');
        sb.append("FTP = ").append(ftpWatts).append('\n');
        sb.append("MINUTES WATTS\n");
        sb.append("[END COURSE HEADER]\n");
        sb.append("[COURSE DATA]\n");
        double t = 0;
        ergLine(sb, t, WARMUP_LOW * ftpWatts);
        t += WARMUP_SEC / 60.0;
        ergLine(sb, t, WARMUP_HIGH * ftpWatts);
        for (Step s : set) {
            double watts = s.ftpFraction * ftpWatts;
            ergLine(sb, t, watts);
            t += s.seconds / 60.0;
            ergLine(sb, t, watts);
        }
        ergLine(sb, t, COOLDOWN_HIGH * ftpWatts);
        t += COOLDOWN_SEC / 60.0;
        ergLine(sb, t, COOLDOWN_LOW * ftpWatts);
        sb.append("[END COURSE DATA]\n");
        return sb.toString();
    }

    /** File name for a repeats export: {@code 5x_col_d_izoard.zwo}; one repeat has no prefix. */
    public static String fileName(String climbName, String extension, int repeats) {
        String base = fileName(climbName, extension);
        return repeats > 1 ? repeats + "x_" + base : base;
    }

    /** Lower-case ASCII file name from the climb name, e.g. {@code col_d_izoard.zwo}. */
    public static String fileName(String climbName, String extension) {
        String base = climbName == null ? "" : java.text.Normalizer
                .normalize(climbName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "klim";
        return base + "." + extension;
    }

    private static String title(String name, int repeats) {
        return repeats > 1 ? repeats + "× " + name : "Klim: " + name;
    }

    private static String description(List<Step> steps, String name, int repeats,
                                      int recoverySec, String blockLabel) {
        int climb = climbSeconds(steps);
        if (blockLabel != null) {
            String block = String.format(new Locale("nl"),
                    "Intervalblok %s uit ClimbPro: %s, %d segmenten, %d:%02d min van voet tot top "
                            + "op vast doelvermogen.",
                    blockLabel, name, steps.size(), climb / 60, climb % 60);
            if (repeats > 1) {
                int total = totalSeconds(steps, repeats, recoverySec);
                block += String.format(new Locale("nl"),
                        " %d× met %d:%02d min herstel op %d %% FTP ertussen. Totaal %d:%02d min.",
                        repeats, recoverySec / 60, recoverySec % 60,
                        Math.round(RECOVERY_FRACTION * 100), total / 60, total % 60);
            }
            return block + " 10 min opwarmen en 5 min uitrijden.";
        }
        if (repeats <= 1) {
            return String.format(new Locale("nl"),
                    "Klimsimulatie uit ClimbPro: %d segmenten, %d:%02d min klimmen, vermogen per "
                            + "segment volgt de helling. 10 min opwarmen en 5 min uitrijden.",
                    steps.size(), climb / 60, climb % 60);
        }
        int total = totalSeconds(steps, repeats, recoverySec);
        return String.format(new Locale("nl"),
                "Herhaal-klim uit ClimbPro: %d× %s (%d segmenten, %d:%02d min per keer) met "
                        + "%d:%02d min herstel op %d %% FTP ertussen. Vermogen per segment volgt "
                        + "de helling. 10 min opwarmen en 5 min uitrijden. Totaal %d:%02d min.",
                repeats, name, steps.size(), climb / 60, climb % 60,
                recoverySec / 60, recoverySec % 60, Math.round(RECOVERY_FRACTION * 100),
                total / 60, total % 60);
    }

    private static void checkRepeats(int repeats, int recoverySec) {
        if (repeats < 1) throw new IllegalArgumentException("repeats < 1: " + repeats);
        if (recoverySec < 0) throw new IllegalArgumentException("recovery < 0: " + recoverySec);
    }

    private static void zwoSteady(StringBuilder sb, int seconds, double fraction, String message) {
        sb.append(String.format(Locale.US,
                "    <SteadyState Duration=\"%d\" Power=\"%.3f\">\n", seconds, fraction));
        sb.append("      <textevent timeoffset=\"0\" message=\"").append(xml(message))
                .append("\"/>\n");
        sb.append("    </SteadyState>\n");
    }

    private static String displayName(String name) {
        return name == null || name.trim().isEmpty() ? "Klim" : name.trim();
    }

    private static void ergLine(StringBuilder sb, double minutes, double watts) {
        sb.append(String.format(Locale.US, "%.2f\t%d\n", minutes, Math.round(watts)));
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ");
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
