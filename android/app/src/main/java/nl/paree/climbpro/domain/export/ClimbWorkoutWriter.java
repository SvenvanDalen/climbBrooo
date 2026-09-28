package nl.paree.climbpro.domain.export;

import nl.paree.climbpro.domain.power.ClimbTimeEstimate;
import nl.paree.climbpro.domain.power.ClimbTimeEstimator;
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

    public static final class Step {
        public final int seconds;
        /** Target power as a fraction of FTP. */
        public final double ftpFraction;
        /** Gradient as a fraction (0.06 = 6 %), shown in the on-screen message. */
        public final double gradient;

        Step(int seconds, double ftpFraction, double gradient) {
            this.seconds = seconds;
            this.ftpFraction = ftpFraction;
            this.gradient = gradient;
        }
    }

    public static final class Plan {
        public final List<Step> steps;
        /** Average power over the climb as a fraction of FTP (the estimate's assumption). */
        public final double avgFraction;
        public final int ftpWatts;

        Plan(List<Step> steps, double avgFraction, int ftpWatts) {
            this.steps = steps;
            this.avgFraction = avgFraction;
            this.ftpWatts = ftpWatts;
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

    public static String toZwo(String climbName, List<Step> steps) {
        String name = displayName(climbName);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<workout_file>\n");
        sb.append("  <author>ClimbPro</author>\n");
        sb.append("  <name>").append(xml("Klim: " + name)).append("</name>\n");
        sb.append("  <description>").append(xml(description(steps))).append("</description>\n");
        sb.append("  <sportType>bike</sportType>\n");
        sb.append("  <tags>\n    <tag name=\"CLIMB\"/>\n  </tags>\n");
        sb.append("  <workout>\n");
        sb.append(String.format(Locale.US,
                "    <Warmup Duration=\"%d\" PowerLow=\"%.2f\" PowerHigh=\"%.2f\"/>\n",
                WARMUP_SEC, WARMUP_LOW, WARMUP_HIGH));
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            sb.append(String.format(Locale.US,
                    "    <SteadyState Duration=\"%d\" Power=\"%.3f\">\n", s.seconds, s.ftpFraction));
            sb.append("      <textevent timeoffset=\"0\" message=\"")
                    .append(xml(String.format(new Locale("nl"), "Segment %d/%d: %.1f %%",
                            i + 1, steps.size(), s.gradient * 100)))
                    .append("\"/>\n");
            sb.append("    </SteadyState>\n");
        }
        sb.append(String.format(Locale.US,
                "    <Cooldown Duration=\"%d\" PowerLow=\"%.2f\" PowerHigh=\"%.2f\"/>\n",
                COOLDOWN_SEC, COOLDOWN_HIGH, COOLDOWN_LOW));
        sb.append("  </workout>\n");
        sb.append("</workout_file>\n");
        return sb.toString();
    }

    /** ERG: absolute watts against cumulative minutes; a step repeats its minute mark. */
    public static String toErg(String climbName, List<Step> steps, int ftpWatts) {
        String name = displayName(climbName);
        StringBuilder sb = new StringBuilder();
        sb.append("[COURSE HEADER]\n");
        sb.append("VERSION = 2\n");
        sb.append("UNITS = METRIC\n");
        sb.append("DESCRIPTION = ").append(oneLine(description(steps))).append('\n');
        sb.append("FILE NAME = ").append(oneLine("Klim: " + name)).append('\n');
        sb.append("FTP = ").append(ftpWatts).append('\n');
        sb.append("MINUTES WATTS\n");
        sb.append("[END COURSE HEADER]\n");
        sb.append("[COURSE DATA]\n");
        double t = 0;
        ergLine(sb, t, WARMUP_LOW * ftpWatts);
        t += WARMUP_SEC / 60.0;
        ergLine(sb, t, WARMUP_HIGH * ftpWatts);
        for (Step s : steps) {
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

    private static String description(List<Step> steps) {
        int total = 0;
        for (Step s : steps) total += s.seconds;
        return String.format(new Locale("nl"),
                "Klimsimulatie uit ClimbPro: %d segmenten, %d:%02d min klimmen, vermogen per "
                        + "segment volgt de helling. 10 min opwarmen en 5 min uitrijden.",
                steps.size(), total / 60, total % 60);
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
