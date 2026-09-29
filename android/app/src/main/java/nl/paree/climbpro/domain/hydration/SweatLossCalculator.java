package nl.paree.climbpro.domain.hydration;

import java.util.List;

/**
 * Zweetverlies-schatter (issue #186): sweat loss from a before/after weigh-in plus the fluid
 * drunk on the bike, and personal drinking advice for future rides.
 *
 * <p>Sweat loss (L) = (weight before − weight after, kg) + fluid drunk (L); 1 kg of body mass
 * is taken as 1 L of fluid. Urine and food are ignored — the screen tells the user to weigh
 * without clothes and after using the toilet. The advice replaces {@link #REPLACE_FRACTION} of
 * the sweat rate (keeps a typical ride well under the 2 % body-mass loss where performance
 * suffers) and never exceeds {@link #MAX_ADVISED_ML_PER_H}, roughly what the gut absorbs.
 *
 * <p>Pure: no Android, no IO. Phone-only; never part of the wire payload.
 */
public final class SweatLossCalculator {

    public static final double MIN_WEIGHT_KG = 30.0;
    public static final double MAX_WEIGHT_KG = 250.0;
    public static final int MIN_DURATION_MIN = 15;
    public static final int MAX_DURATION_MIN = 24 * 60;
    public static final int MAX_FLUID_ML = 20_000;
    /** A larger weight change in one ride is almost certainly a typo. */
    public static final double MAX_CHANGE_FRACTION = 0.10;

    /** Share of the sweat rate the advice aims to drink back. */
    public static final double REPLACE_FRACTION = 0.8;
    /** Upper bound of the advice: more per hour is poorly absorbed. */
    public static final int MAX_ADVISED_ML_PER_H = 1000;
    public static final int ADVICE_STEP_ML = 50;
    public static final int DEFAULT_BOTTLE_ML = 500;

    /** Body-mass loss (%) where the status bands change. */
    public static final double GOOD_MAX_LOSS_PCT = 1.0;
    public static final double HIGH_MIN_LOSS_PCT = 2.0;
    private static final double EPS = 1e-9;

    /** Why a measurement cannot be used. */
    public enum Invalid { WEIGHT_OUT_OF_RANGE, DURATION_OUT_OF_RANGE, FLUID_OUT_OF_RANGE,
        IMPLAUSIBLE_CHANGE }

    /** How well the rider kept up, judged by the body-mass change. */
    public enum Status {
        /** Heavier after than before: drank more than needed. */
        GAINED,
        /** Lost less than 1 % of body mass. */
        GOOD,
        /** Lost 1–2 %. */
        MODERATE,
        /** Lost 2 % or more: performance likely suffered. */
        HIGH
    }

    /** One evaluated measurement. */
    public static final class Result {
        public final double sweatLossL;
        public final double sweatRateLPerH;
        /** Body-mass loss as % of the weight before; negative when the rider gained weight. */
        public final double massLossPct;
        public final int durationMin;
        public final Status status;
        public final int advisedMlPerHour;

        Result(double sweatLossL, double sweatRateLPerH, double massLossPct, int durationMin,
               Status status, int advisedMlPerHour) {
            this.sweatLossL = sweatLossL;
            this.sweatRateLPerH = sweatRateLPerH;
            this.massLossPct = massLossPct;
            this.durationMin = durationMin;
            this.status = status;
            this.advisedMlPerHour = advisedMlPerHour;
        }
    }

    /** Personal average over all measurements. */
    public static final class Summary {
        public final int count;
        /** Duration-weighted: total sweat over total time. */
        public final double avgSweatRateLPerH;
        public final double minSweatRateLPerH;
        public final double maxSweatRateLPerH;
        public final int advisedMlPerHour;
        public final boolean exceedsAbsorption;

        Summary(int count, double avg, double min, double max) {
            this.count = count;
            this.avgSweatRateLPerH = avg;
            this.minSweatRateLPerH = min;
            this.maxSweatRateLPerH = max;
            this.advisedMlPerHour = advisedMlPerHour(avg);
            this.exceedsAbsorption = exceedsAbsorption(avg);
        }
    }

    private SweatLossCalculator() { }

    /** Null when the measurement is usable, otherwise the first problem found. */
    public static Invalid validate(double weightBeforeKg, double weightAfterKg, int drunkMl,
                                   int durationMin) {
        if (!inWeightRange(weightBeforeKg) || !inWeightRange(weightAfterKg)) {
            return Invalid.WEIGHT_OUT_OF_RANGE;
        }
        if (durationMin < MIN_DURATION_MIN || durationMin > MAX_DURATION_MIN) {
            return Invalid.DURATION_OUT_OF_RANGE;
        }
        if (drunkMl < 0 || drunkMl > MAX_FLUID_ML) return Invalid.FLUID_OUT_OF_RANGE;
        double change = weightBeforeKg - weightAfterKg;
        if (Math.abs(change) > weightBeforeKg * MAX_CHANGE_FRACTION) {
            return Invalid.IMPLAUSIBLE_CHANGE;
        }
        if (change + drunkMl / 1000.0 < -EPS) return Invalid.IMPLAUSIBLE_CHANGE;
        return null;
    }

    /** @throws IllegalArgumentException when {@link #validate} rejects the input */
    public static Result calculate(double weightBeforeKg, double weightAfterKg, int drunkMl,
                                   int durationMin) {
        Invalid invalid = validate(weightBeforeKg, weightAfterKg, drunkMl, durationMin);
        if (invalid != null) throw new IllegalArgumentException(invalid.name());
        double change = weightBeforeKg - weightAfterKg;
        double sweatL = Math.max(0.0, change + drunkMl / 1000.0);
        double rate = sweatL / (durationMin / 60.0);
        double lossPct = change / weightBeforeKg * 100.0;
        return new Result(sweatL, rate, lossPct, durationMin, statusFor(lossPct),
                advisedMlPerHour(rate));
    }

    static Status statusFor(double massLossPct) {
        if (massLossPct < -EPS) return Status.GAINED;
        if (massLossPct < GOOD_MAX_LOSS_PCT - EPS) return Status.GOOD;
        if (massLossPct < HIGH_MIN_LOSS_PCT - EPS) return Status.MODERATE;
        return Status.HIGH;
    }

    /** Advised intake per hour for this sweat rate, rounded to 50 ml and capped. */
    public static int advisedMlPerHour(double sweatRateLPerH) {
        if (!(sweatRateLPerH > 0)) return 0;
        double ml = Math.min(sweatRateLPerH * 1000.0 * REPLACE_FRACTION, MAX_ADVISED_ML_PER_H);
        return (int) Math.round(ml / ADVICE_STEP_ML) * ADVICE_STEP_ML;
    }

    /** True when replacing the usual share would need more than the gut absorbs per hour. */
    public static boolean exceedsAbsorption(double sweatRateLPerH) {
        return sweatRateLPerH * 1000.0 * REPLACE_FRACTION > MAX_ADVISED_ML_PER_H + EPS;
    }

    /** Bottles per hour for {@code mlPerHour}, rounded up to half bottles. */
    public static double bottlesPerHour(int mlPerHour, int bottleMl) {
        if (mlPerHour <= 0 || bottleMl <= 0) return 0.0;
        return Math.ceil((double) mlPerHour / bottleMl * 2.0 - EPS) / 2.0;
    }

    /** Personal average over {@code results} (nulls skipped); null when there are none. */
    public static Summary summarize(List<Result> results) {
        if (results == null) return null;
        int count = 0;
        double totalL = 0.0;
        double totalH = 0.0;
        double min = Double.MAX_VALUE;
        double max = 0.0;
        for (Result r : results) {
            if (r == null || r.durationMin <= 0) continue;
            count++;
            totalL += r.sweatLossL;
            totalH += r.durationMin / 60.0;
            min = Math.min(min, r.sweatRateLPerH);
            max = Math.max(max, r.sweatRateLPerH);
        }
        if (count == 0) return null;
        return new Summary(count, totalL / totalH, min, max);
    }

    private static boolean inWeightRange(double kg) {
        return kg >= MIN_WEIGHT_KG && kg <= MAX_WEIGHT_KG;
    }
}
