package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * This week's climbing totals for the home-screen widget (issue #259): climbs ridden, their
 * elevation gain and time on them, for the current Monday-to-Sunday week in the given zone.
 * Pure; the caller supplies each climb's gain (by {@link ClimbIdentity} key) because attempts
 * don't store it. Attempts on climbs no longer in any route still count, with 0 gain.
 */
public final class WeekSummaryCalculator {

    public static final class WeekSummary {
        public final int climbCount;
        public final int elevationM;
        public final int climbingSec;

        public WeekSummary(int climbCount, int elevationM, int climbingSec) {
            this.climbCount = climbCount;
            this.elevationM = elevationM;
            this.climbingSec = climbingSec;
        }

        /** "5 klimmen · 420 hm · 38 min", or a nudge when the week is still empty. */
        public String label() {
            if (climbCount == 0) return "Nog geen klimmen deze week";
            return climbCount + (climbCount == 1 ? " klim" : " klimmen") + " · "
                    + String.format(Locale.GERMANY, "%,d", elevationM) + " hm · " + duration(climbingSec);
        }

        private static String duration(int sec) {
            int min = Math.round(sec / 60f);
            return min < 60 ? min + " min" : (min / 60) + " u " + (min % 60) + " min";
        }
    }

    private WeekSummaryCalculator() {}

    public static WeekSummary compute(List<StoredClimbAttempt> attempts,
                                      Map<String, Integer> gainByClimbId,
                                      long nowEpochSec, ZoneId zone) {
        LocalDate monday = Instant.ofEpochSecond(nowEpochSec).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weekStart = monday.atStartOfDay(zone).toEpochSecond();
        long weekEnd = monday.plusWeeks(1).atStartOfDay(zone).toEpochSecond();

        int count = 0, gain = 0, sec = 0;
        for (StoredClimbAttempt a : attempts) {
            if (a.dateEpochSec < weekStart || a.dateEpochSec >= weekEnd) continue;
            count++;
            Integer g = gainByClimbId.get(a.climbId);
            if (g != null && g > 0) gain += g;
            if (a.elapsedSec > 0) sec += a.elapsedSec;
        }
        return new WeekSummary(count, gain, sec);
    }
}
