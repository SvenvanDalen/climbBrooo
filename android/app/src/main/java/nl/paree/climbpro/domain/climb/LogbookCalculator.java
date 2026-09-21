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

        public HistoryRow(long dateEpochSec, int elapsedSec, int deltaToPrSec, boolean bestOfYear) {
            this.dateEpochSec = dateEpochSec;
            this.elapsedSec = elapsedSec;
            this.deltaToPrSec = deltaToPrSec;
            this.bestOfYear = bestOfYear;
        }
    }

    public static Map<String, Summary> summaries(List<StoredClimbAttempt> attempts) {
        Map<String, int[]> acc = new LinkedHashMap<>(); // climbId -> {pr, count}
        Map<String, Long> lastDate = new LinkedHashMap<>();
        for (StoredClimbAttempt a : attempts) {
            int[] v = acc.get(a.climbId);
            if (v == null) {
                acc.put(a.climbId, new int[]{a.elapsedSec, 1});
                lastDate.put(a.climbId, a.dateEpochSec);
            } else {
                if (a.elapsedSec < v[0]) v[0] = a.elapsedSec;
                v[1]++;
                if (a.dateEpochSec > lastDate.get(a.climbId)) {
                    lastDate.put(a.climbId, a.dateEpochSec);
                }
            }
        }
        Map<String, Summary> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : acc.entrySet()) {
            out.put(e.getKey(), new Summary(
                    e.getKey(), e.getValue()[0], e.getValue()[1], lastDate.get(e.getKey())));
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
        int pr = Integer.MAX_VALUE;
        for (StoredClimbAttempt a : attempts) {
            if (climbId.equals(a.climbId)) {
                mine.add(a);
                if (a.elapsedSec < pr) pr = a.elapsedSec;
            }
        }
        mine.sort(Comparator.comparingLong((StoredClimbAttempt a) -> a.dateEpochSec).reversed());
        boolean mostRecentIsBestOfYear =
                BestOfYearCalculator.isMostRecentBestOfYear(climbId, attempts, nowEpochSec);
        List<HistoryRow> rows = new ArrayList<>(mine.size());
        for (int i = 0; i < mine.size(); i++) {
            StoredClimbAttempt a = mine.get(i);
            boolean bestOfYear = i == 0 && mostRecentIsBestOfYear; // rows are newest-first
            rows.add(new HistoryRow(a.dateEpochSec, a.elapsedSec, a.elapsedSec - pr, bestOfYear));
        }
        return rows;
    }
}
