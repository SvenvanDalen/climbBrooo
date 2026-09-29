package nl.paree.climbpro.domain.weather;

import nl.paree.climbpro.domain.weather.ClimateNormals.Cell;
import nl.paree.climbpro.domain.weather.ClimateNormals.DayPart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Picks the most favourable months and part of the day to ride a climb from its
 * {@link ClimateNormals} (issue #41). Every month × day-part gets a 0–100 comfort score:
 * full marks for a dry, calm 12–22 °C, points off per degree outside that band, per km/h of
 * wind above a light breeze, for the chance of a wet ride and per km/h of mean headwind along
 * the climb. Pure.
 */
public final class BestTimeScorer {

    static final double COMFORT_MIN_C = 12;
    static final double COMFORT_MAX_C = 22;
    static final double PER_DEGREE = 3;
    static final double CALM_WIND_KMH = 12;
    static final double PER_KMH_WIND = 2;
    static final double RAIN_WEIGHT = 50;
    static final double PER_KMH_HEADWIND = 1.5;
    /** Months within this many points of the best month count as "best". */
    static final double BEST_MARGIN = 8;

    static final String[] MONTHS = {"januari", "februari", "maart", "april", "mei", "juni",
            "juli", "augustus", "september", "oktober", "november", "december"};
    static final String[] MONTHS_SHORT = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul",
            "aug", "sep", "okt", "nov", "dec"};

    /** One month's best day-part. {@code bestPart} is null when the month has no data. */
    public static final class Month {
        public final int month;
        public final DayPart bestPart;
        public final double score;
        public final Cell cell;

        Month(int month, DayPart bestPart, double score, Cell cell) {
            this.month = month;
            this.bestPart = bestPart;
            this.score = score;
            this.cell = cell;
        }
    }

    public static final class Result {
        /** Best months, 1–12 in calendar order. */
        public final List<Integer> bestMonths;
        public final DayPart bestDayPart;
        /** Index 0 = January. */
        public final Month[] months;

        Result(List<Integer> bestMonths, DayPart bestDayPart, Month[] months) {
            this.bestMonths = bestMonths;
            this.bestDayPart = bestDayPart;
            this.months = months;
        }

        /** E.g. {@code "juni–september, ochtend"}. */
        public String summary() {
            return monthRanges(bestMonths) + ", " + bestDayPart.label;
        }

        /** Twelve lines, best months starred: {@code "★ jul  17°  8 km/u  regen 25%  ochtend"}. */
        public String monthTable() {
            StringBuilder sb = new StringBuilder();
            for (Month m : months) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(bestMonths.contains(m.month) ? "★ " : "  ")
                        .append(MONTHS_SHORT[m.month - 1]);
                if (m.bestPart == null) {
                    sb.append("  –");
                    continue;
                }
                Cell c = m.cell;
                sb.append(String.format(Locale.ROOT, " %3d° %2d km/u  regen %2d%%  %s",
                        Math.round(c.meanTempC),
                        Double.isNaN(c.meanWindKmh) ? 0 : Math.round(c.meanWindKmh),
                        Double.isNaN(c.rainChance) ? 0 : Math.round(c.rainChance * 100),
                        m.bestPart.label));
            }
            return sb.toString();
        }
    }

    private BestTimeScorer() {}

    /** Comfort score 0–100, or NaN for a cell without data. */
    public static double score(Cell c, double bearingDeg) {
        if (!c.hasData()) return Double.NaN;
        double s = 100;
        if (c.meanTempC < COMFORT_MIN_C) s -= (COMFORT_MIN_C - c.meanTempC) * PER_DEGREE;
        if (c.meanTempC > COMFORT_MAX_C) s -= (c.meanTempC - COMFORT_MAX_C) * PER_DEGREE;
        if (!Double.isNaN(c.meanWindKmh) && c.meanWindKmh > CALM_WIND_KMH) {
            s -= (c.meanWindKmh - CALM_WIND_KMH) * PER_KMH_WIND;
        }
        if (!Double.isNaN(c.rainChance)) s -= c.rainChance * RAIN_WEIGHT;
        s -= Math.max(0, c.headwindKmh(bearingDeg)) * PER_KMH_HEADWIND;
        return Math.max(0, s);
    }

    /** Best months and day-part, or null when the climatology holds no data at all. */
    public static Result evaluate(ClimateNormals normals, double bearingDeg) {
        Month[] months = new Month[12];
        double top = Double.NEGATIVE_INFINITY;
        for (int m = 1; m <= 12; m++) {
            DayPart best = null;
            double bestScore = Double.NaN;
            for (DayPart p : DayPart.values()) {
                double s = score(normals.cell(m, p), bearingDeg);
                if (!Double.isNaN(s) && (best == null || s > bestScore)) {
                    best = p;
                    bestScore = s;
                }
            }
            months[m - 1] = new Month(m, best, bestScore,
                    best == null ? Cell.EMPTY : normals.cell(m, best));
            if (best != null) top = Math.max(top, bestScore);
        }
        if (top == Double.NEGATIVE_INFINITY) return null;

        List<Integer> bestMonths = new ArrayList<>();
        for (Month m : months) {
            if (m.bestPart != null && m.score >= top - BEST_MARGIN) bestMonths.add(m.month);
        }
        // The day-part that is best on average over the best months.
        DayPart bestPart = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        for (DayPart p : DayPart.values()) {
            double sum = 0;
            int n = 0;
            for (int month : bestMonths) {
                double s = score(normals.cell(month, p), bearingDeg);
                if (!Double.isNaN(s)) {
                    sum += s;
                    n++;
                }
            }
            if (n > 0 && sum / n > bestMean) {
                bestMean = sum / n;
                bestPart = p;
            }
        }
        return new Result(Collections.unmodifiableList(bestMonths), bestPart, months);
    }

    /** "juni–september", "november–februari", "april–mei en september", "het hele jaar". */
    static String monthRanges(List<Integer> months) {
        if (months.size() >= 12) return "het hele jaar";
        boolean[] in = new boolean[13];
        for (int m : months) in[m] = true;
        List<String> runs = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            int prev = m == 1 ? 12 : m - 1;
            if (!in[m] || in[prev]) continue; // not the start of a run
            int end = m;
            while (in[end == 12 ? 1 : end + 1]) end = end == 12 ? 1 : end + 1;
            // A run may cross New Year (november–februari); runs are listed by start month.
            String run = end == m ? MONTHS[m - 1] : MONTHS[m - 1] + "–" + MONTHS[end - 1];
            runs.add(run);
        }
        if (runs.size() == 1) return runs.get(0);
        return String.join(", ", runs.subList(0, runs.size() - 1))
                + " en " + runs.get(runs.size() - 1);
    }
}
