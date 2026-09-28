package nl.paree.climbpro.domain.tire;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.tire.TirePressureLogEntry;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a tire-pressure check is due (issue #155): due as soon as either enabled
 * threshold — X days or X km since the latest logged check — is reached. A threshold of 0 turns
 * that criterion off; with both off the reminder never fires.
 *
 * <p>Km since the last check = the sum of {@link StoredRide#distanceM} of archived rides
 * (issue #160 ride archive) that started after the latest check. {@code VirtualRide} is
 * excluded: indoor km on a trainer don't wear or deflate the road tyres. Rides with an unknown
 * start ({@code startEpochSec == 0}) are skipped because they cannot be placed before or after
 * the check. The archive only grows on a Strava sync, so km lag behind until the next sync.
 *
 * <p>No entries yet: <b>not due</b>. A first-time user who never logs a check would otherwise
 * get a permanent banner on the main screen; instead the log screen itself prompts to log a
 * first check ({@link Status#hasEntries} false).
 *
 * <p>Pure and static with an explicit {@code nowEpochSec}, so it is unit-testable.
 */
public final class TirePressureReminderCalculator {

    private TirePressureReminderCalculator() {}

    static final String VIRTUAL_RIDE_TYPE = "VirtualRide";
    private static final long SECONDS_PER_DAY = 24L * 60 * 60;

    /** Outcome of one evaluation. */
    public static final class Status {
        public final boolean hasEntries;
        public final boolean due;
        public final boolean dueByDays;
        public final boolean dueByKm;
        /** Whole days since the latest check (0 when there are no entries). */
        public final int     daysSince;
        /** Outdoor km since the latest check (0 when there are no entries). */
        public final double  kmSince;

        Status(boolean hasEntries, boolean dueByDays, boolean dueByKm, int daysSince, double kmSince) {
            this.hasEntries = hasEntries;
            this.dueByDays  = dueByDays;
            this.dueByKm    = dueByKm;
            this.due        = dueByDays || dueByKm;
            this.daysSince  = daysSince;
            this.kmSince    = kmSince;
        }
    }

    public static Status evaluate(List<TirePressureLogEntry> entries, List<StoredRide> rides,
                                  int reminderDays, int reminderKm, long nowEpochSec) {
        TirePressureLogEntry latest = latest(entries);
        if (latest == null) return new Status(false, false, false, 0, 0);

        long lastTs = latest.timestampEpochSec;
        int days = (int) Math.max(0, (nowEpochSec - lastTs) / SECONDS_PER_DAY);
        double km = outdoorKmSince(rides, lastTs);

        boolean byDays = reminderDays > 0 && days >= reminderDays;
        boolean byKm   = reminderKm > 0 && km >= reminderKm;
        return new Status(true, byDays, byKm, days, km);
    }

    /** The entry with the greatest timestamp, or null when there are none. */
    public static TirePressureLogEntry latest(List<TirePressureLogEntry> entries) {
        TirePressureLogEntry best = null;
        if (entries == null) return null;
        for (TirePressureLogEntry e : entries) {
            if (e != null && (best == null || e.timestampEpochSec > best.timestampEpochSec)) best = e;
        }
        return best;
    }

    static double outdoorKmSince(List<StoredRide> rides, long sinceEpochSec) {
        if (rides == null) return 0;
        double meters = 0;
        for (StoredRide r : rides) {
            if (r == null || r.startEpochSec <= 0 || r.startEpochSec <= sinceEpochSec) continue;
            if (VIRTUAL_RIDE_TYPE.equalsIgnoreCase(r.type)) continue;
            if (r.distanceM > 0) meters += r.distanceM;
        }
        return meters / 1000.0;
    }

    /** "9 dagen / 340 km geleden" — the part shared by the banner and the log-screen status. */
    public static String sinceText(Status s) {
        String days = s.daysSince == 1 ? "1 dag" : s.daysSince + " dagen";
        return String.format(Locale.getDefault(), "%s / %d km geleden",
                days, Math.round(s.kmSince));
    }

    /** Main-screen banner text, or null when no banner should show. */
    public static String bannerText(Status s) {
        if (s == null || !s.due) return null;
        return "Bandenspanning controleren (" + sinceText(s) + ")";
    }
}
