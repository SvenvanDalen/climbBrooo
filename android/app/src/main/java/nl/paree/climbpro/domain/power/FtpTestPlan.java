package nl.paree.climbpro.domain.power;

import nl.paree.climbpro.domain.export.ClimbWorkoutWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The guided 20-minute FTP test (issue #181): warm-up, a 5-minute blow-out effort that burns
 * off the fresh anaerobic capacity, recovery, the 20-minute all-out test and a cool-down —
 * the classic protocol where FTP is 95 % of the 20-minute average power. Power targets are
 * fractions of the current FTP, so the same plan works without a known FTP; the phone shows
 * them in watts when the rider profile has one. Exported as a plain {@code .zwo} (via
 * {@link ClimbWorkoutWriter#toPlainZwo}) for MyWhoosh/Zwift; the result comes back through the
 * Strava sync ({@link FtpTestResultDetector}). Pure; phone-only.
 */
public final class FtpTestPlan {

    private FtpTestPlan() {}

    /** FTP is this share of the best 20-minute average power. */
    public static final double FTP_OF_TWENTY_MINUTES = 0.95;
    /** Target for the test block: holding the current FTP / 0.95 confirms it. */
    public static final double TEST_FRACTION = 1.05;

    /** Name in the trainer app's workout list; within MyWhoosh's 40-character limit. */
    public static final String WORKOUT_NAME = "FTP-test 20 min (ClimbPro)";
    public static final String FILE_NAME = "ftp_test_20_min.zwo";

    public enum PhaseType { WARMUP, BLOW_OUT, RECOVERY, TEST, COOLDOWN }

    /** One phase of the test; power ramps from {@link #fromFraction} to {@link #toFraction}. */
    public static final class Phase {
        public final PhaseType type;
        public final int seconds;
        public final double fromFraction;
        public final double toFraction;

        Phase(PhaseType type, int seconds, double fromFraction, double toFraction) {
            this.type = type;
            this.seconds = seconds;
            this.fromFraction = fromFraction;
            this.toFraction = toFraction;
        }

        public int fromWatts(int ftpWatts) {
            return (int) Math.round(fromFraction * ftpWatts);
        }

        public int toWatts(int ftpWatts) {
            return (int) Math.round(toFraction * ftpWatts);
        }

        public boolean isRamp() {
            return fromFraction != toFraction;
        }
    }

    private static final List<Phase> PHASES;

    static {
        List<Phase> p = new ArrayList<>();
        // 15 min rising from endurance to tempo gets the legs and heart rate ready.
        p.add(new Phase(PhaseType.WARMUP, 15 * 60, 0.50, 0.75));
        // Hard 5 minutes: empties the anaerobic reserve so the 20 minutes measure the aerobic
        // engine rather than a fresh sprint start.
        p.add(new Phase(PhaseType.BLOW_OUT, 5 * 60, TEST_FRACTION, TEST_FRACTION));
        p.add(new Phase(PhaseType.RECOVERY, 10 * 60, 0.50, 0.50));
        p.add(new Phase(PhaseType.TEST, 20 * 60, TEST_FRACTION, TEST_FRACTION));
        p.add(new Phase(PhaseType.COOLDOWN, 10 * 60, 0.60, 0.40));
        PHASES = Collections.unmodifiableList(p);
    }

    public static List<Phase> phases() {
        return PHASES;
    }

    public static int totalSeconds() {
        int total = 0;
        for (Phase p : PHASES) total += p.seconds;
        return total;
    }

    /** Average power to aim for in the 20-minute block; 0 without a known FTP. */
    public static int testTargetWatts(int currentFtpWatts) {
        return currentFtpWatts > 0
                ? (int) Math.round(currentFtpWatts / FTP_OF_TWENTY_MINUTES) : 0;
    }

    /** FTP from a 20-minute average power: 95 % of it, rounded. */
    public static int ftpFromTwentyMinuteWatts(int twentyMinuteWatts) {
        return twentyMinuteWatts > 0
                ? (int) Math.round(twentyMinuteWatts * FTP_OF_TWENTY_MINUTES) : 0;
    }

    /**
     * The test as a plain {@code .zwo}. The 20-minute block carries the target, but it is a
     * maximal effort: the description asks the rider to switch ERG off there and ride by feel.
     */
    public static String toZwo() {
        List<ClimbWorkoutWriter.Block> blocks = new ArrayList<>();
        for (Phase p : PHASES) {
            switch (p.type) {
                case WARMUP:
                    blocks.add(ClimbWorkoutWriter.Block.warmup(p.seconds, p.fromFraction, p.toFraction));
                    break;
                case COOLDOWN:
                    blocks.add(ClimbWorkoutWriter.Block.cooldown(p.seconds, p.fromFraction, p.toFraction));
                    break;
                default:
                    blocks.add(ClimbWorkoutWriter.Block.steady(p.seconds, p.fromFraction));
                    break;
            }
        }
        String description = "20-minuten FTP-test uit ClimbPro: 15 min opwarmen, 5 min hard "
                + "(blow-out), 10 min herstel, 20 min zo hard als je 20 minuten volhoudt en 10 min "
                + "uitrijden. Zet ERG uit voor de 5 en de 20 minuten: het doel is een richtwaarde, "
                + "rij op gevoel en gelijkmatig. Je FTP is 95 % van je gemiddelde vermogen over de "
                + "20 minuten; ClimbPro leest het uit na de Strava-sync.";
        return ClimbWorkoutWriter.toPlainZwo(WORKOUT_NAME, description, blocks);
    }
}
