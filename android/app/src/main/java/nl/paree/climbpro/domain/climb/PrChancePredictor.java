package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.training.FitnessCalculator;
import nl.paree.climbpro.domain.weather.HourlyForecast;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pre-ride PR chance on one climb (issue #58). Phone-only, no wire-format impact. Combines three
 * existing signals into a points score and a simple category:
 * <ul>
 *   <li><b>History</b> on this climb ({@link StoredClimbAttempt}s, PR from clean attempts like
 *       {@link LogbookCalculator}): a recent PR or a recent attempt close to it counts in favour,
 *       recent attempts far off the PR count against, and a PR from only one or two attempts is
 *       easier to beat.</li>
 *   <li><b>Fitness and form</b> from {@link FitnessCalculator}: fitness (CTL) today versus on the
 *       day of the PR, and today's form (TSB).</li>
 *   <li><b>Weather</b> on the top right now, from the same Open-Meteo {@link HourlyForecast} the
 *       summit weather uses. Optional: offline (null) it simply drops out of the reasons.</li>
 * </ul>
 * Each factor is worth −2…+2 points; the sum maps to {@link Chance}. Pure: the caller supplies the
 * clock, the fitness numbers and the forecast.
 */
public final class PrChancePredictor {

    private PrChancePredictor() {}

    /** Attempts in this window count as "recent" for the history factors. */
    static final int RECENT_DAYS = 90;
    /** Recent best within this % of the PR: in reach. */
    static final double CLOSE_GAP_PCT = 3.0;
    /** Recent best more than this % slower than the PR: out of reach. */
    static final double FAR_GAP_PCT = 8.0;
    /** A PR set on at most this many attempts usually has room left. */
    static final int FEW_ATTEMPTS = 2;
    /** Fitness ratio (today / PR day) at or above this counts as fitter. */
    static final double FITTER_RATIO = 1.05;
    /** Fitness ratio at or below this counts as clearly less fit. */
    static final double LESS_FIT_RATIO = 0.90;
    /** PR-day fitness below this is too small to compare against. */
    static final double MIN_COMPARABLE_CTL = 1.0;

    static final int RAIN_PCT = 50;
    static final double WIND_KMH = 30;
    static final double COLD_C = 5;
    static final double HOT_C = 28;
    static final double IDEAL_MIN_C = 10;
    static final double IDEAL_MAX_C = 24;
    static final double IDEAL_MAX_WIND_KMH = 20;
    static final int IDEAL_MAX_RAIN_PCT = 30;

    /** Score at or above this is {@link Chance#GOOD}; below zero is {@link Chance#UNLIKELY}. */
    static final int GOOD_SCORE = 2;

    public enum Chance { GOOD, MODERATE, UNLIKELY }

    /** What a reason is about; the UI maps each to a Dutch string with {@link Reason#value}. */
    public enum Factor {
        /** No attempt yet: the first ride is a PR by definition. */
        FIRST_ATTEMPT,
        /** value = days since the PR. */
        RECENT_PR,
        /** value = % the recent best is slower than the PR. */
        CLOSE_TO_PR,
        /** value = % the recent best is slower than the PR. */
        FAR_FROM_PR,
        /** value = days since the last attempt, NaN when no attempt is dated. */
        NO_RECENT_ATTEMPT,
        /** value = number of attempts. */
        FEW_ATTEMPTS,
        /** value = % fitness gained since the PR day. */
        FITTER_THAN_PR,
        /** value = % fitness lost since the PR day. */
        LESS_FIT_THAN_PR,
        /** value = form (TSB). */
        FRESH,
        NEUTRAL_FORM,
        TIRED,
        VERY_TIRED,
        /** value = apparent temperature (°C). */
        WEATHER_IDEAL,
        /** value = rain chance (%). */
        WEATHER_RAIN,
        /** value = wind speed (km/h). */
        WEATHER_WIND,
        /** value = apparent temperature (°C). */
        WEATHER_COLD,
        /** value = apparent temperature (°C). */
        WEATHER_HOT
    }

    public static final class Reason {
        public final Factor factor;
        /** Contribution to the score: positive helps, negative hurts, 0 is informative. */
        public final int points;
        public final double value;

        Reason(Factor factor, int points, double value) {
            this.factor = factor;
            this.points = points;
            this.value = value;
        }
    }

    /** Fitness numbers; {@link #prDayCtl} is NaN when the archive can't tell it. */
    public static final class Fitness {
        public final double todayCtl;
        public final double todayTsb;
        public final double prDayCtl;

        public Fitness(double todayCtl, double todayTsb, double prDayCtl) {
            this.todayCtl = todayCtl;
            this.todayTsb = todayTsb;
            this.prDayCtl = prDayCtl;
        }
    }

    /** Weather on the climb for the hour of the ride; each field may be unknown. */
    public static final class Weather {
        /** Apparent temperature (°C), NaN when unknown. */
        public final double apparentC;
        /** Wind speed (km/h), NaN when unknown. */
        public final double windKmh;
        /** Rain chance (%), null when unknown. */
        public final Integer rainPct;

        public Weather(double apparentC, double windKmh, Integer rainPct) {
            this.apparentC = apparentC;
            this.windKmh = windKmh;
            this.rainPct = rainPct;
        }

        /** The forecast hour containing {@code when}, or null outside the forecast / no forecast. */
        public static Weather from(HourlyForecast f, Instant when) {
            if (f == null || when == null) return null;
            int i = f.indexAt(when);
            if (i < 0) return null;
            return new Weather(f.apparent[i], f.windKmh[i], f.rainPct[i]);
        }
    }

    public static final class Prediction {
        /** Null for a {@link #firstAttempt}: there's no PR to beat. */
        public final Chance chance;
        public final int score;
        public final boolean firstAttempt;
        /** The PR to beat (seconds); 0 on a first attempt. */
        public final int prSec;
        /** When the PR was set (epoch seconds); 0 on a first attempt or an undated PR. */
        public final long prDateEpochSec;
        /** Attempts the PR comes from (clean ones when there are any). */
        public final int attemptCount;
        /** Heaviest reasons first. */
        public final List<Reason> reasons;

        Prediction(Chance chance, int score, boolean firstAttempt, int prSec, long prDateEpochSec,
                   int attemptCount, List<Reason> reasons) {
            this.chance = chance;
            this.score = score;
            this.firstAttempt = firstAttempt;
            this.prSec = prSec;
            this.prDateEpochSec = prDateEpochSec;
            this.attemptCount = attemptCount;
            this.reasons = reasons;
        }
    }

    /**
     * Fitness today and on {@code prDate} from a {@link FitnessCalculator} result whose window
     * reaches back to {@code prDate}. The PR-day fitness is left NaN when that day predates the
     * archive or falls in its first {@link FitnessCalculator#CTL_DAYS} days, when fitness is still
     * building up from zero. Null without rides.
     */
    public static Fitness fitnessFrom(FitnessCalculator.Result r, LocalDate prDate) {
        if (r == null || r.today == null) return null;
        double prCtl = Double.NaN;
        if (prDate != null) {
            LocalDate firstRide = r.today.date.minusDays(Math.max(0, r.historyDays - 1));
            if (!prDate.isBefore(firstRide.plusDays(FitnessCalculator.CTL_DAYS))) {
                for (FitnessCalculator.Day d : r.days) {
                    if (d.date.equals(prDate)) {
                        prCtl = d.ctl;
                        break;
                    }
                }
            }
        }
        return new Fitness(r.today.ctl, r.today.tsb, prCtl);
    }

    /**
     * @param attempts   all stored attempts (any climb); null is treated as none.
     * @param fitness    null when there is no ride archive.
     * @param weather    null when offline or outside the forecast.
     * @param nowEpochSec the moment of the prediction.
     */
    public static Prediction predict(String climbId, List<StoredClimbAttempt> attempts,
                                     Fitness fitness, Weather weather, long nowEpochSec) {
        List<StoredClimbAttempt> clean = new ArrayList<>();
        List<StoredClimbAttempt> all = new ArrayList<>();
        if (attempts != null) {
            for (StoredClimbAttempt a : attempts) {
                if (a == null || a.elapsedSec <= 0 || climbId == null
                        || !climbId.equals(a.climbId)) continue;
                all.add(a);
                if (!a.routeDeviation) clean.add(a);
            }
        }
        // Like LogbookCalculator: fall back to deviated attempts only when there's no clean one.
        List<StoredClimbAttempt> pool = clean.isEmpty() ? all : clean;
        if (pool.isEmpty()) {
            List<Reason> only = new ArrayList<>();
            only.add(new Reason(Factor.FIRST_ATTEMPT, 0, Double.NaN));
            return new Prediction(null, 0, true, 0, 0L, 0, only);
        }

        StoredClimbAttempt pr = pool.get(0);
        for (StoredClimbAttempt a : pool) if (a.elapsedSec < pr.elapsedSec) pr = a;

        List<Reason> reasons = new ArrayList<>();
        addHistory(reasons, pool, pr, nowEpochSec);
        if (fitness != null) addFitness(reasons, fitness);
        if (weather != null) addWeather(reasons, weather);

        // Stable sort: heaviest first, equal weights keep history → fitness → weather order.
        Collections.sort(reasons, (x, y) -> Integer.compare(Math.abs(y.points), Math.abs(x.points)));
        int score = 0;
        for (Reason r : reasons) score += r.points;
        Chance chance = score >= GOOD_SCORE ? Chance.GOOD
                : score >= 0 ? Chance.MODERATE : Chance.UNLIKELY;
        return new Prediction(chance, score, false, pr.elapsedSec,
                Math.max(0L, pr.dateEpochSec), pool.size(), reasons);
    }

    private static void addHistory(List<Reason> out, List<StoredClimbAttempt> pool,
                                   StoredClimbAttempt pr, long now) {
        long recentFrom = now - RECENT_DAYS * 86_400L;
        StoredClimbAttempt recentBest = null;
        long lastDated = 0;
        for (StoredClimbAttempt a : pool) {
            if (a.dateEpochSec <= 0) continue; // undated: no recency
            if (a.dateEpochSec > lastDated) lastDated = a.dateEpochSec;
            if (a.dateEpochSec >= recentFrom
                    && (recentBest == null || a.elapsedSec < recentBest.elapsedSec)) {
                recentBest = a;
            }
        }
        if (recentBest == null) {
            double days = lastDated > 0 ? daysBetween(lastDated, now) : Double.NaN;
            out.add(new Reason(Factor.NO_RECENT_ATTEMPT, 0, days));
        } else if (recentBest.elapsedSec == pr.elapsedSec) {
            out.add(new Reason(Factor.RECENT_PR, 2, daysBetween(pr.dateEpochSec, now)));
        } else {
            double gapPct = (recentBest.elapsedSec - pr.elapsedSec) * 100.0 / pr.elapsedSec;
            if (gapPct <= CLOSE_GAP_PCT) out.add(new Reason(Factor.CLOSE_TO_PR, 1, gapPct));
            else if (gapPct > FAR_GAP_PCT) out.add(new Reason(Factor.FAR_FROM_PR, -1, gapPct));
        }
        if (pool.size() <= FEW_ATTEMPTS) {
            out.add(new Reason(Factor.FEW_ATTEMPTS, 1, pool.size()));
        }
    }

    private static void addFitness(List<Reason> out, Fitness f) {
        if (!Double.isNaN(f.prDayCtl) && f.prDayCtl >= MIN_COMPARABLE_CTL
                && !Double.isNaN(f.todayCtl)) {
            double ratio = f.todayCtl / f.prDayCtl;
            double pct = (ratio - 1) * 100;
            if (ratio >= FITTER_RATIO) out.add(new Reason(Factor.FITTER_THAN_PR, 1, pct));
            else if (ratio <= LESS_FIT_RATIO) out.add(new Reason(Factor.LESS_FIT_THAN_PR, -1, -pct));
        }
        if (Double.isNaN(f.todayTsb)) return;
        switch (FitnessCalculator.formOf(f.todayTsb)) {
            case VERY_FRESH:
            case FRESH:
                out.add(new Reason(Factor.FRESH, 1, f.todayTsb));
                break;
            case NEUTRAL:
                out.add(new Reason(Factor.NEUTRAL_FORM, 0, f.todayTsb));
                break;
            case PRODUCTIVE:
                out.add(new Reason(Factor.TIRED, -1, f.todayTsb));
                break;
            default:
                out.add(new Reason(Factor.VERY_TIRED, -2, f.todayTsb));
                break;
        }
    }

    private static void addWeather(List<Reason> out, Weather w) {
        boolean tempKnown = !Double.isNaN(w.apparentC);
        boolean windKnown = !Double.isNaN(w.windKmh);
        boolean rainKnown = w.rainPct != null;
        int before = out.size();
        if (rainKnown && w.rainPct >= RAIN_PCT) out.add(new Reason(Factor.WEATHER_RAIN, -1, w.rainPct));
        if (windKnown && w.windKmh >= WIND_KMH) out.add(new Reason(Factor.WEATHER_WIND, -1, w.windKmh));
        if (tempKnown && w.apparentC < COLD_C) out.add(new Reason(Factor.WEATHER_COLD, -1, w.apparentC));
        if (tempKnown && w.apparentC > HOT_C) out.add(new Reason(Factor.WEATHER_HOT, -1, w.apparentC));
        boolean anyBad = out.size() > before;
        if (!anyBad && tempKnown && windKnown && rainKnown
                && w.apparentC >= IDEAL_MIN_C && w.apparentC <= IDEAL_MAX_C
                && w.windKmh < IDEAL_MAX_WIND_KMH && w.rainPct < IDEAL_MAX_RAIN_PCT) {
            out.add(new Reason(Factor.WEATHER_IDEAL, 1, w.apparentC));
        }
    }

    private static double daysBetween(long fromEpochSec, long toEpochSec) {
        return Math.max(0, (toEpochSec - fromEpochSec) / 86_400L);
    }
}
