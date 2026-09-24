package nl.paree.climbpro.domain.planning;

import nl.paree.climbpro.data.planning.PlannedClimb;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure scheduling-decision logic for planned climbs (issue #70). No Android dependencies —
 * this is the part of the reminder pipeline that PlannedClimbRepositoryTest /
 * PlannedClimbSchedulerTest exercise directly; PlannedClimbReminderWorker (Android/WorkManager
 * side) delegates to it rather than re-implementing the date arithmetic.
 */
public final class PlannedClimbScheduler {

    private PlannedClimbScheduler() {}

    /** All plans whose planned date is today-or-later (local calendar day), soonest first. */
    public static List<PlannedClimb> upcoming(List<PlannedClimb> all, long nowEpochSec, ZoneId zone) {
        long startOfToday = startOfDay(nowEpochSec, zone);
        List<PlannedClimb> out = new ArrayList<>();
        for (PlannedClimb p : all) {
            if (p.plannedAtEpochSec >= startOfToday) out.add(p);
        }
        out.sort(Comparator.comparingLong(p -> p.plannedAtEpochSec));
        return out;
    }

    /**
     * Plans whose planned local calendar day is "today" or earlier (relative to {@code
     * nowEpochSec} in {@code zone}) and that have not yet had a reminder fired. Includes
     * overdue plans — not just an exact day match — so a plan scheduled in the past (the date
     * picker allows this; {@link #delaySeconds} clamps it to fire immediately) or a worker run
     * that slips past midnight still gets a reminder instead of being silently dropped forever.
     * This is what PlannedClimbReminderWorker checks before notifying — it must survive being
     * run more than once (e.g. WorkManager retries) without re-firing, which {@code
     * reminderSent} guards.
     */
    public static List<PlannedClimb> dueToday(List<PlannedClimb> all, long nowEpochSec, ZoneId zone) {
        LocalDate today = Instant.ofEpochSecond(nowEpochSec).atZone(zone).toLocalDate();
        List<PlannedClimb> out = new ArrayList<>();
        for (PlannedClimb p : all) {
            if (p.reminderSent) continue;
            LocalDate plannedDay = Instant.ofEpochSecond(p.plannedAtEpochSec).atZone(zone).toLocalDate();
            if (!plannedDay.isAfter(today)) out.add(p);
        }
        out.sort(Comparator.comparingLong(p -> p.plannedAtEpochSec));
        return out;
    }

    /**
     * Delay, in seconds, between now and the moment a one-off WorkManager request for this
     * plan should fire. Never negative — a plan scheduled in the past (e.g. app was offline)
     * fires immediately so the user still gets a (late) reminder rather than none at all.
     */
    public static long delaySeconds(PlannedClimb plan, long nowEpochSec) {
        return Math.max(0L, plan.plannedAtEpochSec - nowEpochSec);
    }

    /**
     * The next plan that hasn't started yet (planned at or after {@code nowEpochSec}), or
     * null. Unlike {@link #upcoming}, a plan from earlier today is not "next" any more.
     */
    public static PlannedClimb next(List<PlannedClimb> all, long nowEpochSec) {
        PlannedClimb best = null;
        for (PlannedClimb p : all) {
            if (p.plannedAtEpochSec < nowEpochSec) continue;
            if (best == null || p.plannedAtEpochSec < best.plannedAtEpochSec) best = p;
        }
        return best;
    }

    private static long startOfDay(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toEpochSecond();
    }
}
