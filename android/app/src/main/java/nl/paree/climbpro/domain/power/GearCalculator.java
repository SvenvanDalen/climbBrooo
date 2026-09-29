package nl.paree.climbpro.domain.power;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gear calculator for one climb (issue #188): which cadence each chainring × sprocket
 * combination gives at the speed the rider holds on the climb's steepest segment and at its
 * average gradient. Speeds come from the same power model as the climb time estimate
 * ({@link PowerSpeedSolver}); the caller passes the sustainable power for the climb. Pure.
 * Phone-only, never sent to the watch.
 *
 * <p>Cadence (rpm) = speed / (wheel circumference × chainring / sprocket) × 60.
 */
public final class GearCalculator {

    /** Common cassettes by their "smallest-largest" shorthand, sprockets small to large. */
    public static final Map<String, int[]> CASSETTES = new LinkedHashMap<>();

    static {
        CASSETTES.put("11-25", new int[]{11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 25});
        CASSETTES.put("11-28", new int[]{11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28});
        CASSETTES.put("11-30", new int[]{11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30});
        CASSETTES.put("11-32", new int[]{11, 12, 13, 14, 16, 18, 20, 22, 25, 28, 32});
        CASSETTES.put("11-34", new int[]{11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34});
        CASSETTES.put("11-36", new int[]{11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 31, 36});
        CASSETTES.put("10-33", new int[]{10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33});
        CASSETTES.put("10-36", new int[]{10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 36});
        CASSETTES.put("10-44", new int[]{10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44});
    }

    public static final int DEFAULT_WHEEL_CIRCUMFERENCE_MM = 2105; // 700x25c
    public static final int DEFAULT_TARGET_CADENCE_RPM = 80;
    static final int MIN_TEETH = 9;
    static final int MAX_TEETH = 60;
    /** Largest sprocket we'd suggest before saying "a smaller chainring" instead. */
    static final int MAX_SUGGESTED_SPROCKET = 52;

    /** One chainring × sprocket combination. */
    public static final class Gear {
        public final int chainring;
        public final int sprocket;
        public final double cadenceSteepRpm;
        public final double cadenceAvgRpm;

        Gear(int chainring, int sprocket, double cadenceSteepRpm, double cadenceAvgRpm) {
            this.chainring = chainring;
            this.sprocket = sprocket;
            this.cadenceSteepRpm = cadenceSteepRpm;
            this.cadenceAvgRpm = cadenceAvgRpm;
        }

        public double ratio() { return chainring / (double) sprocket; }
    }

    public static final class Result {
        public final double steepestGradient;
        public final double avgGradient;
        public final double speedSteepMps;
        public final double speedAvgMps;
        public final int targetCadenceRpm;
        /** Every combination, easiest (lowest ratio) first. */
        public final List<Gear> gears;
        /** Sprocket needed on the smallest chainring for the target cadence on the steepest part. */
        public final int neededSprocket;

        Result(double steepestGradient, double avgGradient, double speedSteepMps,
               double speedAvgMps, int targetCadenceRpm, List<Gear> gears, int neededSprocket) {
            this.steepestGradient = steepestGradient;
            this.avgGradient = avgGradient;
            this.speedSteepMps = speedSteepMps;
            this.speedAvgMps = speedAvgMps;
            this.targetCadenceRpm = targetCadenceRpm;
            this.gears = gears;
            this.neededSprocket = neededSprocket;
        }

        public Gear easiest() { return gears.get(0); }

        public boolean easiestIsEnough() {
            return easiest().cadenceSteepRpm >= targetCadenceRpm;
        }
    }

    private static final Locale NL = new Locale("nl", "NL");

    private GearCalculator() {}

    /**
     * @param chainrings        teeth, any order
     * @param sprockets         teeth, any order
     * @param steepestGradient  fraction (0.12 = 12 %)
     * @param avgGradient       fraction
     * @param powerWatts        sustainable pedal power on the climb
     * @param crr               rolling resistance of the road surface
     */
    public static Result compute(int[] chainrings, int[] sprockets, int wheelCircumferenceMm,
                                 int targetCadenceRpm, double steepestGradient,
                                 double avgGradient, double powerWatts, double totalMassKg,
                                 double crr) {
        if (chainrings == null || chainrings.length == 0 || sprockets == null
                || sprockets.length == 0) {
            throw new IllegalArgumentException("chainrings and sprockets are required");
        }
        if (wheelCircumferenceMm <= 0 || targetCadenceRpm <= 0) {
            throw new IllegalArgumentException("circumference and cadence must be positive");
        }
        double vSteep = PowerSpeedSolver.speedMetersPerSecond(
                powerWatts, totalMassKg, steepestGradient, crr);
        double vAvg = PowerSpeedSolver.speedMetersPerSecond(
                powerWatts, totalMassKg, avgGradient, crr);

        List<Gear> gears = new ArrayList<>();
        for (int c : chainrings) {
            for (int s : sprockets) {
                gears.add(new Gear(c, s, cadence(vSteep, c, s, wheelCircumferenceMm),
                        cadence(vAvg, c, s, wheelCircumferenceMm)));
            }
        }
        gears.sort((a, b) -> {
            int byRatio = Double.compare(a.ratio(), b.ratio());
            return byRatio != 0 ? byRatio : Integer.compare(a.chainring, b.chainring);
        });

        int smallest = Arrays.stream(chainrings).min().getAsInt();
        return new Result(steepestGradient, avgGradient, vSteep, vAvg, targetCadenceRpm, gears,
                neededSprocket(vSteep, smallest, targetCadenceRpm, wheelCircumferenceMm));
    }

    /** Pedal cadence in rpm at {@code speedMps} in the given gear. */
    public static double cadence(double speedMps, int chainring, int sprocket,
                                 int wheelCircumferenceMm) {
        double metresPerCrankRev = wheelCircumferenceMm / 1000.0 * chainring / sprocket;
        return speedMps / metresPerCrankRev * 60.0;
    }

    /** Smallest sprocket on {@code chainring} that reaches {@code cadence} at {@code speed}. */
    static int neededSprocket(double speedMps, int chainring, int cadenceRpm,
                              int wheelCircumferenceMm) {
        // cadence = v*60*s / (circ*c)  →  s = cadence*circ*c / (v*60)
        double s = cadenceRpm * (wheelCircumferenceMm / 1000.0) * chainring / (speedMps * 60.0);
        return (int) Math.ceil(s - 1e-9);
    }

    /**
     * Parses "50/34", "50-34", "50 34" or "42" into chainring teeth (largest first).
     *
     * @throws IllegalArgumentException with a Dutch message on invalid input
     */
    public static int[] parseChainrings(String text) {
        int[] teeth = parseTeeth(text, "[/\\-\\s,]+");
        if (teeth.length > 3) throw new IllegalArgumentException("Maximaal 3 kettingbladen");
        return sortDescending(teeth);
    }

    /**
     * Parses a cassette: a known shorthand like "11-34" (see {@link #CASSETTES}) or an explicit
     * list "11,12,13,…". Returns sprockets smallest first.
     *
     * @throws IllegalArgumentException with a Dutch message on invalid input
     */
    public static int[] parseCassette(String text) {
        String t = text == null ? "" : text.trim().replace(" ", "");
        int[] preset = CASSETTES.get(t);
        if (preset != null) return preset.clone();
        if (!t.contains(",")) {
            throw new IllegalArgumentException("Onbekende cassette \"" + t
                    + "\" — kies er een uit de lijst of typ alle tandwielen, bv. 11,12,13,15,17");
        }
        int[] teeth = parseTeeth(t, ",+");
        if (teeth.length < 2) throw new IllegalArgumentException("Minstens 2 tandwielen nodig");
        int[] sorted = sortDescending(teeth);
        int[] asc = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) asc[i] = sorted[sorted.length - 1 - i];
        return asc;
    }

    private static int[] parseTeeth(String text, String separator) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Vul het aantal tanden in");
        String[] parts = t.split(separator);
        List<Integer> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            int n;
            try {
                n = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + p + "\" is geen aantal tanden");
            }
            if (n < MIN_TEETH || n > MAX_TEETH) {
                throw new IllegalArgumentException(n + " tanden is geen realistisch tandwiel");
            }
            if (!out.contains(n)) out.add(n);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Vul het aantal tanden in");
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static int[] sortDescending(int[] teeth) {
        int[] copy = teeth.clone();
        Arrays.sort(copy);
        for (int i = 0; i < copy.length / 2; i++) {
            int tmp = copy[i];
            copy[i] = copy[copy.length - 1 - i];
            copy[copy.length - 1 - i] = tmp;
        }
        return copy;
    }

    /** Dutch one-paragraph advice for the easiest gear on the steepest segment. */
    public static String verdict(Result r) {
        Gear e = r.easiest();
        String gear = e.chainring + "×" + e.sprocket;
        String steep = String.format(NL, "%.0f%%", r.steepestGradient * 100);
        long rpm = Math.round(e.cadenceSteepRpm);
        if (r.easiestIsEnough()) {
            return "Je lichtste versnelling (" + gear + ") geeft " + rpm + " rpm op het steilste "
                    + "stuk (" + steep + ") — genoeg voor je doel van " + r.targetCadenceRpm
                    + " rpm.";
        }
        String base = "Je lichtste versnelling (" + gear + ") geeft maar " + rpm + " rpm op het "
                + "steilste stuk (" + steep + "). ";
        if (r.neededSprocket <= MAX_SUGGESTED_SPROCKET) {
            return base + "Voor " + r.targetCadenceRpm + " rpm heb je " + e.chainring + "×"
                    + r.neededSprocket + " nodig, of een kleiner kettingblad.";
        }
        return base + "Voor " + r.targetCadenceRpm + " rpm is een veel kleiner kettingblad "
                + "nodig; reken op een lage cadans of een stukje staan.";
    }
}
