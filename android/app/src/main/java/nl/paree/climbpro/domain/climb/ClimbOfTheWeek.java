package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.weather.DailyForecast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Klim van de week" (issue #40). Pure, Android-free and deterministic: picks one known climb
 * to suggest this week from the rider's climb catalog, riding history, location and the
 * weather outlook. Phone-only; nothing here touches the wire format.
 *
 * <p><b>Eligibility.</b> Climbs ridden in the last {@link #RECENT_DAYS} days are skipped, and
 * — when the rider's location is known — so are climbs starting more than
 * {@link #MAX_DISTANCE_M} away. Each filter is dropped again when it would leave nothing to
 * suggest, so there is always a suggestion as long as there are climbs.
 *
 * <p><b>Score</b> (weighted mean of the factors that are available, each in [0, 1]):
 * <ul>
 *   <li><i>freshness</i> ({@link #W_FRESHNESS}) — never ridden = 1.0; otherwise grows with
 *       the days since the last attempt, up to 0.9 at {@link #FRESH_DAYS} days.</li>
 *   <li><i>distance</i> ({@link #W_DISTANCE}) — {@code 1 / (1 + d / DISTANCE_HALF_M)};
 *       skipped when the location is unknown.</li>
 *   <li><i>weather fit</i> ({@link #W_WEATHER}) — the best day of the week decides the
 *       {@link Outlook}: good weather favours the hardest climbs, mixed weather middling
 *       ones, poor weather the easiest. Difficulty is {@link DifficultyScoreCalculator}
 *       relative to the hardest eligible climb. Skipped when there is no forecast
 *       (offline).</li>
 * </ul>
 * A tiny tie-break jitter seeded by the ISO week and climb id rotates between otherwise
 * equal climbs from week to week while staying fixed within a week.
 *
 * <p><b>Pin.</b> The caller remembers the climb it showed this week and passes it back as
 * {@code pinnedClimbId}; while that climb still exists it stays the suggestion for the
 * rest of the week, even when location or weather change. A pinned climb ridden this week
 * is flagged {@link Suggestion#completedThisWeek}.
 */
public final class ClimbOfTheWeek {

    public static final int    RECENT_DAYS = 14;
    public static final int    FRESH_DAYS = 180;
    public static final double MAX_DISTANCE_M = 80_000;
    public static final double DISTANCE_HALF_M = 40_000;

    static final double W_FRESHNESS = 0.45;
    static final double W_DISTANCE  = 0.30;
    static final double W_WEATHER   = 0.25;
    static final double JITTER_WEIGHT = 0.015;

    static final double GOOD_THRESHOLD  = 0.7;
    static final double MIXED_THRESHOLD = 0.4;

    private ClimbOfTheWeek() {}

    /** Overall weather outlook for the week, from its best day. */
    public enum Outlook { GOOD, MIXED, POOR }

    /** A known climb that may be suggested. Coordinates in degrees. */
    public static final class Candidate {
        /** Route-independent identity ({@link ClimbIdentity}); used for dedupe and pinning. */
        public final String climbId;
        public final String routeId;
        public final int    climbIndex;
        public final String name;
        public final double startLat;
        public final double startLon;
        public final int    elevationGainM;
        /** Average gradient as a fraction (0.072 = 7.2 %). */
        public final double avgGradient;
        public final int    lengthM;
        /** Local epoch day of the last attempt; null when never ridden. */
        public final Long   lastRiddenEpochDay;

        private Candidate(Builder b) {
            this.climbId            = b.climbId;
            this.routeId            = b.routeId;
            this.climbIndex         = b.climbIndex;
            this.name               = b.name;
            this.startLat           = b.startLat;
            this.startLon           = b.startLon;
            this.elevationGainM     = b.elevationGainM;
            this.avgGradient        = b.avgGradient;
            this.lengthM            = b.lengthM;
            this.lastRiddenEpochDay = b.lastRiddenEpochDay;
        }

        /** Builds a {@link Candidate}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private String climbId;
            private String routeId;
            private int climbIndex;
            private String name;
            private double startLat;
            private double startLon;
            private int elevationGainM;
            private double avgGradient;
            private int lengthM;
            private Long lastRiddenEpochDay;

            public Builder climbId(String climbId) {
                this.climbId = climbId;
                return this;
            }

            public Builder routeId(String routeId) {
                this.routeId = routeId;
                return this;
            }

            public Builder climbIndex(int climbIndex) {
                this.climbIndex = climbIndex;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder startLat(double startLat) {
                this.startLat = startLat;
                return this;
            }

            public Builder startLon(double startLon) {
                this.startLon = startLon;
                return this;
            }

            public Builder elevationGainM(int elevationGainM) {
                this.elevationGainM = elevationGainM;
                return this;
            }

            public Builder avgGradient(double avgGradient) {
                this.avgGradient = avgGradient;
                return this;
            }

            public Builder lengthM(int lengthM) {
                this.lengthM = lengthM;
                return this;
            }

            public Builder lastRiddenEpochDay(Long lastRiddenEpochDay) {
                this.lastRiddenEpochDay = lastRiddenEpochDay;
                return this;
            }

            public Candidate build() {
                return new Candidate(this);
            }
        }

        String key() {
            return climbId != null ? climbId : ("route:" + routeId + "#" + climbIndex);
        }
    }

    /** A scored climb plus the facts the screen turns into reasons. */
    public static final class Suggestion {
        public final Candidate candidate;
        public final double    score;
        /** Straight-line distance to the climb start in metres; NaN when location unknown. */
        public final double    distanceM;
        public final boolean   distanceUsed;
        /** Days since the last attempt; null when never ridden. */
        public final Integer   daysSinceRidden;
        public final boolean   weatherUsed;
        /** Best day of the forecast; null without weather. */
        public final DailyForecast.Day bestDay;
        /** Null without weather. */
        public final Outlook   outlook;
        /** True when this is the climb already shown earlier this week. */
        public final boolean   pinned;
        /** Pinned climb that was ridden on or after this week's Monday. */
        public final boolean   completedThisWeek;

        private Suggestion(Builder b) {
            this.candidate         = b.candidate;
            this.score             = b.score;
            this.distanceM         = b.distanceM;
            this.distanceUsed      = b.distanceUsed;
            this.daysSinceRidden   = b.daysSinceRidden;
            this.weatherUsed       = b.weatherUsed;
            this.bestDay           = b.bestDay;
            this.outlook           = b.outlook;
            this.pinned            = b.pinned;
            this.completedThisWeek = b.completedThisWeek;
        }

        /** Builds a {@link Suggestion}; every value defaults to Java's default for its type. */
        public static final class Builder {
            private Candidate candidate;
            private double score;
            private double distanceM;
            private boolean distanceUsed;
            private Integer daysSinceRidden;
            private boolean weatherUsed;
            private DailyForecast.Day bestDay;
            private Outlook outlook;
            private boolean pinned;
            private boolean completedThisWeek;

            public Builder candidate(Candidate candidate) {
                this.candidate = candidate;
                return this;
            }

            public Builder score(double score) {
                this.score = score;
                return this;
            }

            public Builder distanceM(double distanceM) {
                this.distanceM = distanceM;
                return this;
            }

            public Builder distanceUsed(boolean distanceUsed) {
                this.distanceUsed = distanceUsed;
                return this;
            }

            public Builder daysSinceRidden(Integer daysSinceRidden) {
                this.daysSinceRidden = daysSinceRidden;
                return this;
            }

            public Builder weatherUsed(boolean weatherUsed) {
                this.weatherUsed = weatherUsed;
                return this;
            }

            public Builder bestDay(DailyForecast.Day bestDay) {
                this.bestDay = bestDay;
                return this;
            }

            public Builder outlook(Outlook outlook) {
                this.outlook = outlook;
                return this;
            }

            public Builder pinned(boolean pinned) {
                this.pinned = pinned;
                return this;
            }

            public Builder completedThisWeek(boolean completedThisWeek) {
                this.completedThisWeek = completedThisWeek;
                return this;
            }

            public Suggestion build() {
                return new Suggestion(this);
            }
        }
    }

    /** The suggestion for this week, or null when there are no usable climbs. */
    public static Suggestion suggest(List<Candidate> candidates, double originLat,
                                     double originLon, LocalDate today,
                                     List<DailyForecast.Day> forecast, String pinnedClimbId) {
        List<Suggestion> ranked = rank(candidates, originLat, originLon, today, forecast,
                pinnedClimbId);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * All eligible climbs, best first (a valid pin always first).
     *
     * @param originLat NaN when the rider's location is unknown (no distance factor)
     * @param forecast  null or empty when offline (no weather factor)
     */
    public static List<Suggestion> rank(List<Candidate> candidates, double originLat,
                                        double originLon, LocalDate today,
                                        List<DailyForecast.Day> forecast, String pinnedClimbId) {
        if (candidates == null || today == null) return Collections.emptyList();

        Map<String, Candidate> unique = uniqueValid(candidates);
        if (unique.isEmpty()) return Collections.emptyList();

        boolean haveOrigin = validCoord(originLat, originLon);
        String week = weekKey(today);
        LocalDate monday = today.with(DayOfWeek.MONDAY);

        // Pin first: it survives the eligibility filters for the rest of the week.
        Candidate pinned = pinnedClimbId != null ? unique.get(pinnedClimbId) : null;

        List<Candidate> pool = new ArrayList<>(unique.values());
        pool = keepIfAny(pool, c -> {
            Integer days = daysSince(c, today);
            return days == null || days >= RECENT_DAYS;
        });
        if (haveOrigin) {
            pool = keepIfAny(pool, c -> distance(originLat, originLon, c) <= MAX_DISTANCE_M);
        }

        DailyForecast.Day best = bestDay(forecast, today);
        Outlook outlook = best == null ? null : outlookFor(dayQuality(best));
        double maxDifficulty = maxDifficulty(pool, pinned);

        List<Suggestion> out = new ArrayList<>();
        for (Candidate c : pool) {
            if (c == pinned) continue;
            out.add(score(new ScoreInput()
                    .c(c)
                    .originLat(originLat)
                    .originLon(originLon)
                    .haveOrigin(haveOrigin)
                    .today(today)
                    .best(best)
                    .outlook(outlook)
                    .maxDifficulty(maxDifficulty)
                    .week(week)
                    .pinned(false)
                    .done(false)));
        }
        Collections.sort(out, ORDER);
        if (pinned != null) {
            boolean done = pinned.lastRiddenEpochDay != null
                    && pinned.lastRiddenEpochDay >= monday.toEpochDay();
            out.add(0, score(new ScoreInput()
                    .c(pinned)
                    .originLat(originLat)
                    .originLon(originLon)
                    .haveOrigin(haveOrigin)
                    .today(today)
                    .best(best)
                    .outlook(outlook)
                    .maxDifficulty(maxDifficulty)
                    .week(week)
                    .pinned(true)
                    .done(done)));
        }
        return out;
    }

    private static Suggestion score(ScoreInput in) {
        Integer days = daysSince(in.c, in.today);
        double sum = W_FRESHNESS * freshness(days);
        double weights = W_FRESHNESS;

        double d = Double.NaN;
        if (in.haveOrigin) {
            d = distance(in.originLat, in.originLon, in.c);
            sum += W_DISTANCE * (1.0 / (1.0 + d / DISTANCE_HALF_M));
            weights += W_DISTANCE;
        }
        if (in.outlook != null) {
            double norm = in.maxDifficulty > 0 ? difficulty(in.c) / in.maxDifficulty : 0.5;
            sum += W_WEATHER * weatherFit(in.outlook, norm);
            weights += W_WEATHER;
        }
        double score = sum / weights + JITTER_WEIGHT * jitter(in.week, in.c.key());
        return new Suggestion.Builder()
                .candidate(in.c)
                .score(score)
                .distanceM(d)
                .distanceUsed(in.haveOrigin)
                .daysSinceRidden(days)
                .weatherUsed(in.outlook != null)
                .bestDay(in.best)
                .outlook(in.outlook)
                .pinned(in.pinned)
                .completedThisWeek(in.done)
                .build();
    }

    static double freshness(Integer daysSince) {
        if (daysSince == null) return 1.0;
        return 0.9 * Math.min(Math.max(daysSince, 0), FRESH_DAYS) / FRESH_DAYS;
    }

    static double weatherFit(Outlook outlook, double normDifficulty) {
        switch (outlook) {
            case GOOD:  return normDifficulty;
            case POOR:  return 1.0 - normDifficulty;
            default:    return 1.0 - 2.0 * Math.abs(normDifficulty - 0.5);
        }
    }

    static Outlook outlookFor(double quality) {
        if (quality >= GOOD_THRESHOLD) return Outlook.GOOD;
        if (quality >= MIXED_THRESHOLD) return Outlook.MIXED;
        return Outlook.POOR;
    }

    /**
     * Riding quality of one day in [0, 1]: 50 % rain chance, 30 % wind (full marks up to
     * 15 km/h, zero from 50 km/h), 20 % temperature (full marks 10–25 °C, zero at 0 and
     * 35 °C). Unknown values count as half marks.
     */
    public static double dayQuality(DailyForecast.Day day) {
        double rain = day.rainPct == null ? 0.5
                : 1.0 - clamp01(day.rainPct / 100.0);
        double wind = Double.isNaN(day.windMaxKmh) ? 0.5
                : 1.0 - clamp01((day.windMaxKmh - 15.0) / 35.0);
        double temp;
        if (Double.isNaN(day.tempMaxC)) temp = 0.5;
        else if (day.tempMaxC < 10) temp = clamp01(day.tempMaxC / 10.0);
        else if (day.tempMaxC > 25) temp = clamp01((35.0 - day.tempMaxC) / 10.0);
        else temp = 1.0;
        return 0.5 * rain + 0.3 * wind + 0.2 * temp;
    }

    /** Best riding day from {@code today} on (earliest on a tie); null when there is none. */
    public static DailyForecast.Day bestDay(List<DailyForecast.Day> forecast, LocalDate today) {
        if (forecast == null) return null;
        DailyForecast.Day best = null;
        double bestQ = -1;
        for (DailyForecast.Day d : forecast) {
            if (d == null || d.date == null || d.date.isBefore(today)) continue;
            double q = dayQuality(d);
            if (q > bestQ || (q == bestQ && d.date.isBefore(best.date))) {
                best = d;
                bestQ = q;
            }
        }
        return best;
    }

    /** ISO-8601 week key, e.g. {@code 2026-W40}. */
    public static String weekKey(LocalDate date) {
        return String.format(Locale.ROOT, "%d-W%02d",
                date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** Stored form of this week's pin: {@code <isoWeek>|<climbId>}. */
    public static String encodePin(String weekKey, String climbId) {
        return weekKey + "|" + climbId;
    }

    /** Climb id from a stored pin when it belongs to {@code weekKey}; null otherwise. */
    public static String pinnedClimbId(String storedPin, String weekKey) {
        if (storedPin == null || weekKey == null) return null;
        int bar = storedPin.indexOf('|');
        if (bar <= 0 || !storedPin.substring(0, bar).equals(weekKey)) return null;
        String id = storedPin.substring(bar + 1);
        return id.isEmpty() ? null : id;
    }

    private interface Filter { boolean keep(Candidate c); }

    /** Candidates with a valid start coordinate, first one per key. */
    private static Map<String, Candidate> uniqueValid(List<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c == null || !validCoord(c.startLat, c.startLon)) continue;
            unique.putIfAbsent(c.key(), c);
        }
        return unique;
    }

    private static double maxDifficulty(List<Candidate> pool, Candidate pinned) {
        double max = 0;
        for (Candidate c : pool) max = Math.max(max, difficulty(c));
        if (pinned != null) max = Math.max(max, difficulty(pinned));
        return max;
    }

    private static List<Candidate> keepIfAny(List<Candidate> in, Filter f) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) if (f.keep(c)) out.add(c);
        return out.isEmpty() ? in : out;
    }

    private static Integer daysSince(Candidate c, LocalDate today) {
        if (c.lastRiddenEpochDay == null) return null;
        return (int) Math.max(0, today.toEpochDay() - c.lastRiddenEpochDay);
    }

    private static double distance(double lat, double lon, Candidate c) {
        return CumulativeDistance.haversine(lat, lon, c.startLat, c.startLon);
    }

    private static double difficulty(Candidate c) {
        return DifficultyScoreCalculator.score(c.elevationGainM, c.avgGradient, 0);
    }

    /** Deterministic value in [0, 1) from week + climb (SplitMix64 finaliser). */
    static double jitter(String week, String key) {
        long z = ((long) week.hashCode() << 32) ^ (key.hashCode() & 0xffffffffL);
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static boolean validCoord(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static final Comparator<Suggestion> ORDER = (a, b) -> {
        int c = Double.compare(b.score, a.score);
        if (c != 0) return c;
        String an = a.candidate.name != null ? a.candidate.name : "";
        String bn = b.candidate.name != null ? b.candidate.name : "";
        c = an.compareToIgnoreCase(bn);
        return c != 0 ? c : a.candidate.key().compareTo(b.candidate.key());
    };

    /** Arguments of {@link #score}. */
    public static final class ScoreInput {
        private Candidate c;
        private double originLat;
        private double originLon;
        private boolean haveOrigin;
        private LocalDate today;
        private DailyForecast.Day best;
        private Outlook outlook;
        private double maxDifficulty;
        private String week;
        private boolean pinned;
        private boolean done;

        public ScoreInput c(Candidate c) {
            this.c = c;
            return this;
        }

        public ScoreInput originLat(double originLat) {
            this.originLat = originLat;
            return this;
        }

        public ScoreInput originLon(double originLon) {
            this.originLon = originLon;
            return this;
        }

        public ScoreInput haveOrigin(boolean haveOrigin) {
            this.haveOrigin = haveOrigin;
            return this;
        }

        public ScoreInput today(LocalDate today) {
            this.today = today;
            return this;
        }

        public ScoreInput best(DailyForecast.Day best) {
            this.best = best;
            return this;
        }

        public ScoreInput outlook(Outlook outlook) {
            this.outlook = outlook;
            return this;
        }

        public ScoreInput maxDifficulty(double maxDifficulty) {
            this.maxDifficulty = maxDifficulty;
            return this;
        }

        public ScoreInput week(String week) {
            this.week = week;
            return this;
        }

        public ScoreInput pinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        public ScoreInput done(boolean done) {
            this.done = done;
            return this;
        }
    }
}
