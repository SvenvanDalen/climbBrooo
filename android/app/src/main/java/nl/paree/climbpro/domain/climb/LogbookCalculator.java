package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure presentation logic over stored climb attempts. */
public final class LogbookCalculator {

    private LogbookCalculator() {}

    /** Per-climb roll-up for the logbook list. */
    public static final class Summary {
        public final String climbId;
        public final int    prSec;        // fastest elapsed
        public final int    attemptCount;
        public final long   lastDateSec;  // most recent attempt date

        public Summary(String climbId, int prSec, int attemptCount, long lastDateSec) {
            this.climbId = climbId;
            this.prSec = prSec;
            this.attemptCount = attemptCount;
            this.lastDateSec = lastDateSec;
        }
    }

    /** One attempt as shown on the detail history list. */
    public static final class HistoryRow {
        public final long dateEpochSec;
        public final int  elapsedSec;
        public final int  deltaToPrSec; // elapsedSec - prSec (>= 0)

        /**
         * True only on the most recent attempt of the climb when it is also the fastest
         * attempt of that climb recorded within the current calendar year (issue #34).
         * Resets every Jan 1 — independent of {@link #deltaToPrSec}, which tracks the
         * all-time PR. See {@link BestOfYearCalculator}.
         */
        public final boolean bestOfYear;

        /**
         * True when this attempt's GPS track diverged from the climb's known geometry
         * (see {@link nl.paree.climbpro.domain.matching.ClimbRouteDeviationDetector}) and
         * was therefore excluded from {@link #deltaToPrSec}'s PR baseline and
         * {@link #bestOfYear} — the attempt itself is still shown, just marked (issue #77).
         */
        public final boolean routeDeviation;

        /**
         * (activityId, passIndex) alongside the climbId already known by the caller of
         * {@link #historyFor} — together the identity key {@code ClimbAttemptRepository} uses
         * to find and replace this row's underlying {@link
         * nl.paree.climbpro.data.route.StoredClimbAttempt} when the user attaches a note/photo
         * (issue #46).
         */
        public final long activityId;
        public final int  passIndex;

        /** Phone-only diary fields carried straight through for display; see issue #46. */
        public final String note;
        public final String photoFileName;

        /**
         * Average device temperature over the pass (°C), null when unknown — see
         * {@link AttemptTemperature} for the extreme-heat/cold note shown from it (issue #80).
         */
        public final Double avgTempC;

        public HistoryRow(long dateEpochSec, int elapsedSec, int deltaToPrSec,
                          boolean routeDeviation, boolean bestOfYear) {
            this(dateEpochSec, elapsedSec, deltaToPrSec, routeDeviation, bestOfYear,
                    0L, 0, null, null);
        }

        public HistoryRow(long dateEpochSec, int elapsedSec, int deltaToPrSec,
                           boolean routeDeviation, boolean bestOfYear,
                           long activityId, int passIndex, String note, String photoFileName) {
            this(dateEpochSec, elapsedSec, deltaToPrSec, routeDeviation, bestOfYear,
                    activityId, passIndex, note, photoFileName, null);
        }

        public HistoryRow(long dateEpochSec, int elapsedSec, int deltaToPrSec,
                           boolean routeDeviation, boolean bestOfYear,
                           long activityId, int passIndex, String note, String photoFileName,
                           Double avgTempC) {
            this.dateEpochSec = dateEpochSec;
            this.elapsedSec = elapsedSec;
            this.deltaToPrSec = deltaToPrSec;
            this.routeDeviation = routeDeviation;
            this.bestOfYear = bestOfYear;
            this.activityId = activityId;
            this.passIndex = passIndex;
            this.note = note;
            this.photoFileName = photoFileName;
            this.avgTempC = avgTempC;
        }
    }

    /**
     * @param attempts all stored attempts (any climb). The all-time PR ({@link Summary#prSec})
     *                 is computed from non-{@link StoredClimbAttempt#routeDeviation} attempts
     *                 only, falling back to the fastest deviated attempt when a climb has no
     *                 clean attempt yet — never leaving a climb without a PR at all (issue #77).
     *                 {@link Summary#attemptCount}/last-date still count every attempt.
     */
    public static Map<String, Summary> summaries(List<StoredClimbAttempt> attempts) {
        Map<String, Integer> count = new LinkedHashMap<>();
        Map<String, Long> lastDate = new LinkedHashMap<>();
        Map<String, Integer> prAny = new LinkedHashMap<>();
        Map<String, Integer> prValid = new LinkedHashMap<>();
        for (StoredClimbAttempt a : attempts) {
            Integer c = count.get(a.climbId);
            count.put(a.climbId, c == null ? 1 : c + 1);

            Long ld = lastDate.get(a.climbId);
            if (ld == null || a.dateEpochSec > ld) lastDate.put(a.climbId, a.dateEpochSec);

            Integer pa = prAny.get(a.climbId);
            if (pa == null || a.elapsedSec < pa) prAny.put(a.climbId, a.elapsedSec);

            if (!a.routeDeviation) {
                Integer pv = prValid.get(a.climbId);
                if (pv == null || a.elapsedSec < pv) prValid.put(a.climbId, a.elapsedSec);
            }
        }
        Map<String, Summary> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            String climbId = e.getKey();
            Integer pr = prValid.containsKey(climbId) ? prValid.get(climbId) : prAny.get(climbId);
            out.put(climbId, new Summary(climbId, pr, e.getValue(), lastDate.get(climbId)));
        }
        return out;
    }

    /**
     * All attempts across every climb/route, sorted most-recent-first. Unlike
     * {@link #summaries}, this does not dedupe or roll up by climb — it is the
     * per-attempt chronological view ("what did I climb this month"), independent
     * of route grouping. Does not mutate the input list.
     */
    public static List<StoredClimbAttempt> timeline(List<StoredClimbAttempt> attempts) {
        List<StoredClimbAttempt> out = new ArrayList<>(attempts);
        out.sort(Comparator.comparingLong((StoredClimbAttempt a) -> a.dateEpochSec).reversed());
        return out;
    }

    public static List<HistoryRow> historyFor(String climbId, List<StoredClimbAttempt> attempts) {
        return historyFor(climbId, attempts, System.currentTimeMillis() / 1000L);
    }

    /** Overload with an injectable clock so "current calendar year" is testable. */
    public static List<HistoryRow> historyFor(
            String climbId, List<StoredClimbAttempt> attempts, long nowEpochSec) {
        List<StoredClimbAttempt> mine = new ArrayList<>();
        int prAny = Integer.MAX_VALUE;
        int prValid = Integer.MAX_VALUE;
        for (StoredClimbAttempt a : attempts) {
            if (climbId.equals(a.climbId)) {
                mine.add(a);
                if (a.elapsedSec < prAny) prAny = a.elapsedSec;
                if (!a.routeDeviation && a.elapsedSec < prValid) prValid = a.elapsedSec;
            }
        }
        // Prefer the PR from clean (non-deviated) attempts; fall back to the fastest
        // deviated one only when the climb has no clean attempt at all (issue #77).
        int pr = prValid != Integer.MAX_VALUE ? prValid : prAny;
        mine.sort(Comparator.comparingLong((StoredClimbAttempt a) -> a.dateEpochSec).reversed());
        boolean mostRecentIsBestOfYear =
                BestOfYearCalculator.isMostRecentBestOfYear(climbId, attempts, nowEpochSec);
        // The badge belongs to the most recent NON-DEVIATED attempt within the current year —
        // not necessarily row 0, since `mine` (unlike BestOfYearCalculator's own scan) still
        // includes deviated attempts, which can be newer without being eligible (issue #77/#122).
        int bestOfYearIndex = -1;
        if (mostRecentIsBestOfYear) {
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            int currentYear = BestOfYearCalculator.yearOf(nowEpochSec, zone);
            for (int i = 0; i < mine.size(); i++) {
                StoredClimbAttempt a = mine.get(i);
                if (!a.routeDeviation && BestOfYearCalculator.yearOf(a.dateEpochSec, zone) == currentYear) {
                    bestOfYearIndex = i;
                    break;
                }
            }
        }
        List<HistoryRow> rows = new ArrayList<>(mine.size());
        for (int i = 0; i < mine.size(); i++) {
            StoredClimbAttempt a = mine.get(i);
            boolean bestOfYear = i == bestOfYearIndex;
            rows.add(new HistoryRow(a.dateEpochSec, a.elapsedSec, a.elapsedSec - pr,
                    a.routeDeviation, bestOfYear,
                    a.activityId, a.passIndex, a.note, a.photoFileName, a.avgTempC));
        }
        return rows;
    }
}
