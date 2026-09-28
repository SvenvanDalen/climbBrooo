package nl.paree.climbpro.domain.battery;

import nl.paree.climbpro.data.battery.BatteryDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Charge status of a tracked battery (issue #238). A device is due {@code intervalDays} after
 * its last logged charge; interval 0 or no charge logged yet means "no reminder". The worker
 * notifies once per charge cycle ({@link BatteryDevice#reminderSentForChargeEpochSec}).
 *
 * <p>Pure and static with an explicit {@code nowEpochSec}, so it is unit-testable.
 */
public final class BatteryStatusCalculator {

    private BatteryStatusCalculator() {}

    static final long SECONDS_PER_DAY = 24L * 60 * 60;

    /** Due moment in epoch seconds, or -1 when the device has no reminder. */
    public static long dueEpochSec(BatteryDevice d) {
        if (d == null || d.intervalDays <= 0 || d.lastChargedEpochSec <= 0) return -1;
        return d.lastChargedEpochSec + d.intervalDays * SECONDS_PER_DAY;
    }

    public static boolean isDue(BatteryDevice d, long nowEpochSec) {
        long due = dueEpochSec(d);
        return due > 0 && nowEpochSec >= due;
    }

    /** Whole days since the last charge (0 when never charged or in the future). */
    public static int daysSinceCharge(BatteryDevice d, long nowEpochSec) {
        if (d == null || d.lastChargedEpochSec <= 0) return 0;
        return (int) Math.max(0, (nowEpochSec - d.lastChargedEpochSec) / SECONDS_PER_DAY);
    }

    /** Days until due, rounded up (a device due in 3 hours shows "1 dag"); 0 once due. */
    public static int daysUntilDue(BatteryDevice d, long nowEpochSec) {
        long due = dueEpochSec(d);
        if (due <= 0 || nowEpochSec >= due) return 0;
        return (int) ((due - nowEpochSec + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY);
    }

    /** Devices that are due and whose reminder was not yet shown for the current charge. */
    public static List<BatteryDevice> dueReminders(List<BatteryDevice> devices, long nowEpochSec) {
        List<BatteryDevice> out = new ArrayList<>();
        if (devices == null) return out;
        for (BatteryDevice d : devices) {
            if (d == null || !isDue(d, nowEpochSec)) continue;
            if (d.reminderSentForChargeEpochSec == d.lastChargedEpochSec) continue;
            out.add(d);
        }
        return out;
    }

    /** Devices sorted most urgent first: due, then soonest due, then no reminder / never. */
    public static List<BatteryDevice> sortByUrgency(List<BatteryDevice> devices, long nowEpochSec) {
        List<BatteryDevice> out = new ArrayList<>();
        if (devices != null) for (BatteryDevice d : devices) if (d != null) out.add(d);
        out.sort((a, b) -> {
            long da = dueEpochSec(a);
            long db = dueEpochSec(b);
            if (da < 0 && db < 0) return compareNames(a, b);
            if (da < 0) return 1;
            if (db < 0) return -1;
            int c = Long.compare(da, db);
            return c != 0 ? c : compareNames(a, b);
        });
        return out;
    }

    private static int compareNames(BatteryDevice a, BatteryDevice b) {
        String na = a.name != null ? a.name : "";
        String nb = b.name != null ? b.name : "";
        return na.compareToIgnoreCase(nb);
    }

    static String days(int n) {
        return n == 1 ? "1 dag" : n + " dagen";
    }

    /** Status line for the list card. */
    public static String statusText(BatteryDevice d, long nowEpochSec) {
        if (d.lastChargedEpochSec <= 0) return "Nog niet geladen gelogd";
        int since = daysSinceCharge(d, nowEpochSec);
        String charged = since == 0 ? "Vandaag geladen" : "Geladen " + days(since) + " geleden";
        if (dueEpochSec(d) < 0) return charged + " · geen herinnering";
        if (isDue(d, nowEpochSec)) return "Opladen! " + charged.toLowerCase(java.util.Locale.ROOT);
        return charged + " · opladen over " + days(daysUntilDue(d, nowEpochSec));
    }

    public static String notificationTitle(BatteryDevice d) {
        return "Accu opladen: " + d.name;
    }

    public static String notificationText(BatteryDevice d, long nowEpochSec) {
        return BatteryKind.fromName(d.kind).label + " — laatst geladen "
                + days(daysSinceCharge(d, nowEpochSec)) + " geleden. Laad hem op vóór je "
                + "volgende rit.";
    }
}
