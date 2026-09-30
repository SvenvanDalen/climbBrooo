package nl.paree.climbpro.domain.training;

import nl.paree.climbpro.domain.climb.DifficultyScoreCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Klim-gebaseerde trainingsblok-periodisering (issue #63). Pure, Android-free planner that
 * turns the climbs a rider already knows into a multi-week block of gradually increasing
 * climbing load: by default three build weeks followed by one recovery week (3:1).
 *
 * <h2>Load</h2>
 * Load is plain elevation gain on climbs, the same axis {@code RecoveryAdvisor} uses, so the
 * starting point is the rider's own recent weekly average ({@code baselineWeeklyGainM}). Build
 * week {@code k} (1-based) targets {@code start * (1 + WEEKLY_STEP * k)}: +10 % per week, the
 * usual "10 % rule" ceiling for safe load progression. The recovery week drops to
 * {@link #RECOVERY_FACTOR} of the start. Without any history {@link #DEFAULT_START_WEEKLY_GAIN_M}
 * is used; a tiny baseline is raised to {@link #MIN_START_WEEKLY_GAIN_M}.
 *
 * <h2>Intensity</h2>
 * Besides volume, the climbs themselves get harder: candidates are sorted by
 * {@link DifficultyScoreCalculator} (gain x gradient, no fatigue term) and build week {@code k}
 * may only use the easiest {@code ceil(n * (k + 1) / (buildWeeks + 1))} of them, so the hardest
 * allowed climb rises each week and the last build week reaches the hardest one. The recovery
 * week uses only the easiest third.
 *
 * <h2>Sessions</h2>
 * Each week gets up to {@link #MAX_SESSIONS_PER_WEEK} sessions (recovery: {@link
 * #MAX_RECOVERY_SESSIONS}), each one climb ridden {@code repeats} times (the repeat-climb idea
 * of issue #19, spread over a block). Sessions take the hardest allowed climbs first, each a
 * distinct climb, and split the remaining weekly target evenly over the sessions still to
 * come, capped at {@link #MAX_REPEATS}. The planned load can therefore undershoot the target
 * when the known climbs are small; callers show both numbers.
 *
 * <p>Climbs the rider has actually ridden (attempts &gt; 0) are preferred; only when none are
 * ridden does the whole catalog serve as the pool. Phone-only, no wire-format impact.
 */
public final class ClimbPeriodizationPlanner {

    private ClimbPeriodizationPlanner() {}

    public static final int DEFAULT_BUILD_WEEKS = 3;
    /** Load increase per build week, relative to the starting weekly load. */
    static final double WEEKLY_STEP = 0.10;
    /** Recovery week load as a fraction of the starting weekly load. */
    static final double RECOVERY_FACTOR = 0.60;
    /** Starting weekly climbing load when the rider has no usable history. */
    public static final int DEFAULT_START_WEEKLY_GAIN_M = 600;
    /** Lower bound for the starting weekly load, so a nearly empty history still plans something. */
    public static final int MIN_START_WEEKLY_GAIN_M = 200;
    public static final int MAX_SESSIONS_PER_WEEK = 3;
    public static final int MAX_RECOVERY_SESSIONS = 2;
    /** Most repeats of one climb in a single session. */
    public static final int MAX_REPEATS = 6;

    /** A known climb that may be used in the plan. */
    public static final class Candidate {
        public final String climbId;
        public final String name;
        public final int elevationGainM;
        public final int lengthM;
        /** Average gradient as a fraction (0.06 = 6 %). */
        public final double avgGradient;
        /** How often the rider has ridden this climb. */
        public final int attempts;

        public Candidate(String climbId, String name, int elevationGainM, int lengthM,
                         double avgGradient, int attempts) {
            this.climbId = climbId;
            this.name = name;
            this.elevationGainM = elevationGainM;
            this.lengthM = lengthM;
            this.avgGradient = avgGradient;
            this.attempts = attempts;
        }

        public double difficulty() {
            return DifficultyScoreCalculator.score(elevationGainM, avgGradient, 0);
        }
    }

    /** One training session: a single climb ridden {@link #repeats} times. */
    public static final class Session {
        public final Candidate climb;
        public final int repeats;

        Session(Candidate climb, int repeats) {
            this.climb = climb;
            this.repeats = repeats;
        }

        public int gainM() {
            return climb.elevationGainM * repeats;
        }
    }

    public static final class Week {
        /** 1-based week number within the block. */
        public final int number;
        public final boolean recovery;
        public final int targetGainM;
        public final int plannedGainM;
        public final List<Session> sessions;

        Week(int number, boolean recovery, int targetGainM, List<Session> sessions) {
            this.number = number;
            this.recovery = recovery;
            this.targetGainM = targetGainM;
            this.sessions = Collections.unmodifiableList(sessions);
            int sum = 0;
            for (Session s : sessions) sum += s.gainM();
            this.plannedGainM = sum;
        }
    }

    public static final class Plan {
        public final List<Week> weeks;
        /** The weekly load the block starts from. */
        public final int startWeeklyGainM;
        /** False when the start load is the default because there was no history. */
        public final boolean baselineFromHistory;
        /** False when no climb was ridden yet and the whole catalog was used. */
        public final boolean fromRiddenClimbs;

        Plan(List<Week> weeks, int startWeeklyGainM, boolean baselineFromHistory,
             boolean fromRiddenClimbs) {
            this.weeks = Collections.unmodifiableList(weeks);
            this.startWeeklyGainM = startWeeklyGainM;
            this.baselineFromHistory = baselineFromHistory;
            this.fromRiddenClimbs = fromRiddenClimbs;
        }
    }

    /** Default 3:1 block. */
    public static Plan plan(List<Candidate> climbs, double baselineWeeklyGainM) {
        return plan(climbs, baselineWeeklyGainM, DEFAULT_BUILD_WEEKS);
    }

    /**
     * @param climbs              known climbs (any order; duplicate ids are merged).
     * @param baselineWeeklyGainM the rider's recent average weekly climbing load in metres;
     *                            0 or less means no history.
     * @param buildWeeks          number of build weeks before the single recovery week (>= 1).
     * @return the plan, or null when no climb with positive elevation gain is available.
     */
    public static Plan plan(List<Candidate> climbs, double baselineWeeklyGainM, int buildWeeks) {
        if (climbs == null || buildWeeks < 1) return null;

        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : climbs) {
            if (c == null || c.climbId == null || c.elevationGainM <= 0) continue;
            Candidate existing = byId.get(c.climbId);
            if (existing == null || c.attempts > existing.attempts) byId.put(c.climbId, c);
        }
        if (byId.isEmpty()) return null;

        List<Candidate> ridden = new ArrayList<>();
        for (Candidate c : byId.values()) if (c.attempts > 0) ridden.add(c);
        boolean fromRidden = !ridden.isEmpty();
        List<Candidate> pool = fromRidden ? ridden : new ArrayList<>(byId.values());
        Collections.sort(pool, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                int byDifficulty = Double.compare(a.difficulty(), b.difficulty());
                return byDifficulty != 0 ? byDifficulty : a.climbId.compareTo(b.climbId);
            }
        });

        boolean fromHistory = baselineWeeklyGainM > 0 && !Double.isNaN(baselineWeeklyGainM);
        int start = fromHistory
                ? Math.max(MIN_START_WEEKLY_GAIN_M, (int) Math.round(baselineWeeklyGainM))
                : DEFAULT_START_WEEKLY_GAIN_M;

        int n = pool.size();
        List<Week> weeks = new ArrayList<>();
        for (int k = 1; k <= buildWeeks; k++) {
            int target = (int) Math.round(start * (1 + WEEKLY_STEP * k));
            int allowed = ceilDiv(n * (k + 1), buildWeeks + 1);
            weeks.add(new Week(k, false, target,
                    fill(pool.subList(0, allowed), target, MAX_SESSIONS_PER_WEEK)));
        }
        int recoveryTarget = (int) Math.round(start * RECOVERY_FACTOR);
        int easiest = ceilDiv(n, 3);
        weeks.add(new Week(buildWeeks + 1, true, recoveryTarget,
                fill(pool.subList(0, easiest), recoveryTarget, MAX_RECOVERY_SESSIONS)));

        return new Plan(weeks, start, fromHistory, fromRidden);
    }

    /** Spreads {@code target} over distinct climbs from {@code allowed}, hardest first. */
    private static List<Session> fill(List<Candidate> allowed, int target, int maxSessions) {
        List<Session> sessions = new ArrayList<>();
        int count = Math.min(maxSessions, allowed.size());
        int remaining = target;
        for (int i = 0; i < count; i++) {
            Candidate c = allowed.get(allowed.size() - 1 - i);
            // Enough load already; a further session would mostly overshoot.
            if (!sessions.isEmpty() && remaining * 2 < c.elevationGainM) break;
            double share = remaining / (double) (count - i);
            int repeats = (int) Math.round(share / c.elevationGainM);
            repeats = Math.max(1, Math.min(MAX_REPEATS, repeats));
            sessions.add(new Session(c, repeats));
            remaining -= repeats * c.elevationGainM;
        }
        return sessions;
    }

    private static int ceilDiv(int a, int b) {
        return Math.max(1, (a + b - 1) / b);
    }
}
