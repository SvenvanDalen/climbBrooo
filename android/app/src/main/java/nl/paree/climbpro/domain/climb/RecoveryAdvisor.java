package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure presentation logic: a simple rest-day suggestion after a heavy climbing week
 * (issue #61). Phone-only, no wire-format impact — reuses the same
 * {@link nl.paree.climbpro.data.route.StoredClimbAttempt} logbook data that
 * {@link ClimbStreakCalculator} and {@link LogbookCalculator} already aggregate, just
 * bucketed by day and summed as elevation gain instead of as a streak/PR.
 *
 * <h2>The rule</h2>
 * This mirrors the "Acute:Chronic Workload Ratio" (ACWR) used in sports-science load
 * monitoring: the "acute" load is the trailing 7-day cumulative elevation gain, the
 * "chronic" load is that same trailing window averaged over a longer {@link
 * #BASELINE_WEEKS}-week horizon (the acute week is intentionally part of the chronic
 * window — it is a rolling ratio, not two disjoint periods). A ratio above ~1.5 is the
 * commonly cited threshold in that literature for meaningfully elevated
 * overtraining/injury risk, so it doubles as a deliberately simple, already-validated
 * rule of thumb rather than a bespoke one invented for this feature. The suggestion
 * only fires when there was also a ride today or yesterday — a spike from ten days ago
 * is not something a rest day *today* addresses.
 *
 * <p>Deliberately does NOT use {@link DifficultyScoreCalculator}'s fatigue-adjusted
 * score: that calculator scores a single climb's felt difficulty within one ride, which
 * is a different axis from "how much load has accumulated across several days". Plain
 * elevation gain is the simplest signal that is directly comparable day to day.
 */
public final class RecoveryAdvisor {

    private RecoveryAdvisor() {}

    /** The "acute" window: cumulative load of the last N days, N including today. */
    static final int RECENT_WINDOW_DAYS = 7;

    /** The "chronic" window: RECENT_WINDOW_DAYS * BASELINE_WEEKS days, averaged per week. */
    static final int BASELINE_WEEKS = 4;
    static final int BASELINE_WINDOW_DAYS = RECENT_WINDOW_DAYS * BASELINE_WEEKS; // 28

    /**
     * Acute:chronic ratio above which a rest day is suggested. 1.5 is the threshold most
     * commonly cited in sports-science ACWR literature as the point where injury/overtraining
     * risk becomes meaningfully elevated (values below ~0.8 or above ~1.5 are flagged; we only
     * care about the "too much, too soon" side here). Strictly greater-than: sitting exactly
     * at 1.5x is treated as still within normal variation, not yet a trigger.
     */
    static final double OVERLOAD_RATIO = 1.5;

    /** "Today or yesterday" — how recent a ride must be for the suggestion to still be timely. */
    static final int RECENT_RIDE_LOOKBACK_DAYS = 1;

    /** Result of {@link #compute}: whether to suggest rest, the numbers behind it, and a Dutch rationale. */
    public static final class Advice {
        public final boolean suggestRest;
        public final int recentGainM;
        public final double baselineWeeklyAvgGainM;
        public final String rationale;

        Advice(boolean suggestRest, int recentGainM, double baselineWeeklyAvgGainM, String rationale) {
            this.suggestRest = suggestRest;
            this.recentGainM = recentGainM;
            this.baselineWeeklyAvgGainM = baselineWeeklyAvgGainM;
            this.rationale = rationale;
        }
    }

    /** Convenience overload using the device's local timezone and the current date. */
    public static Advice compute(List<StoredClimbAttempt> attempts,
                                  Map<String, Integer> elevationGainByClimbId) {
        ZoneId zone = ZoneId.systemDefault();
        return compute(attempts, elevationGainByClimbId, zone, LocalDate.now(zone));
    }

    /**
     * @param attempts               all stored climb attempts (any order, any climb).
     * @param elevationGainByClimbId each known climb's elevation gain in metres, keyed by
     *                               {@link ClimbIdentity}. Attempts whose climbId is missing
     *                               from this map (climb no longer resolvable to a route)
     *                               don't contribute elevation gain, but still count as "a
     *                               ride happened that day" for the recency check.
     * @param zone                   timezone used to resolve each attempt's epoch-second
     *                               timestamp to a calendar day.
     * @param referenceDate          the "today" against which the windows are computed; pass
     *                               an explicit date in tests for determinism.
     */
    public static Advice compute(List<StoredClimbAttempt> attempts,
                                  Map<String, Integer> elevationGainByClimbId,
                                  ZoneId zone, LocalDate referenceDate) {
        if (attempts == null || attempts.isEmpty()) {
            return calm(0, 0);
        }
        Map<String, Integer> gainLookup = elevationGainByClimbId != null
                ? elevationGainByClimbId : new HashMap<>();

        Map<LocalDate, Integer> gainByDay = new HashMap<>();
        Set<LocalDate> rideDays = new HashSet<>();
        for (StoredClimbAttempt a : attempts) {
            LocalDate day = Instant.ofEpochSecond(a.dateEpochSec).atZone(zone).toLocalDate();
            rideDays.add(day);
            Integer gain = gainLookup.get(a.climbId);
            if (gain != null && gain > 0) {
                gainByDay.merge(day, gain, Integer::sum);
            }
        }

        int recentGain = sumRange(gainByDay, referenceDate.minusDays(RECENT_WINDOW_DAYS - 1), referenceDate);
        int baselineGain = sumRange(gainByDay, referenceDate.minusDays(BASELINE_WINDOW_DAYS - 1), referenceDate);
        double baselineWeeklyAvg = baselineGain / (double) BASELINE_WEEKS;

        boolean rodeRecently = false;
        for (int i = 0; i <= RECENT_RIDE_LOOKBACK_DAYS; i++) {
            if (rideDays.contains(referenceDate.minusDays(i))) {
                rodeRecently = true;
                break;
            }
        }

        boolean overloaded = rodeRecently
                && baselineWeeklyAvg > 0
                && recentGain > baselineWeeklyAvg * OVERLOAD_RATIO;

        if (overloaded) {
            String rationale = String.format(Locale.ROOT,
                    "Je hebt de afgelopen %d dagen %d hoogtemeters geklommen, ruim boven je "
                            + "gemiddelde van %.0f hm per week over de laatste %d weken. "
                            + "Overweeg vandaag een rustdag.",
                    RECENT_WINDOW_DAYS, recentGain, baselineWeeklyAvg, BASELINE_WEEKS);
            return new Advice(true, recentGain, baselineWeeklyAvg, rationale);
        }
        return calm(recentGain, baselineWeeklyAvg);
    }

    private static Advice calm(int recentGain, double baselineWeeklyAvg) {
        return new Advice(false, recentGain, baselineWeeklyAvg,
                "Je belasting is in lijn met je gemiddelde. Geen rustdag nodig.");
    }

    /** Sum of gainByDay values for every day in [start, end], inclusive. */
    private static int sumRange(Map<LocalDate, Integer> gainByDay, LocalDate start, LocalDate end) {
        int sum = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Integer v = gainByDay.get(d);
            if (v != null) sum += v;
        }
        return sum;
    }
}
