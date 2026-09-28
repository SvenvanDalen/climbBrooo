package nl.paree.climbpro.domain.maintenance;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Warranty status of maintenance components (issue #239). The warranty ends
 * {@link MaintenanceComponent#warrantyMonths} calendar months after the purchase date (same
 * local time; a day that does not exist in the target month is clamped to its last day). One
 * reminder is due in the last {@link #REMINDER_DAYS_BEFORE} days before expiry, once per expiry
 * ({@link MaintenanceComponent#warrantyReminderSentForExpiryEpochSec}). An already expired
 * warranty never triggers a reminder.
 *
 * <p>Pure and static with an explicit {@code nowEpochSec} and zone, so it is unit-testable.
 */
public final class WarrantyCalculator {

    private WarrantyCalculator() {}

    /** The reminder fires this many days (or fewer) before the warranty expires. */
    public static final int REMINDER_DAYS_BEFORE = 30;

    private static final long SECONDS_PER_DAY = 24L * 60 * 60;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d-M-yyyy");

    public static boolean hasWarranty(MaintenanceComponent c) {
        return c != null && c.warrantyPurchaseEpochSec > 0 && c.warrantyMonths > 0;
    }

    /** Expiry moment (epoch seconds), or 0 when no warranty is tracked. */
    public static long expiryEpochSec(MaintenanceComponent c, ZoneId zone) {
        if (!hasWarranty(c)) return 0;
        return Instant.ofEpochSecond(c.warrantyPurchaseEpochSec).atZone(zone)
                .plusMonths(c.warrantyMonths).toEpochSecond();
    }

    /** Whole days left, rounded up; 0 when expired or no warranty. */
    public static int daysLeft(MaintenanceComponent c, long nowEpochSec, ZoneId zone) {
        long expiry = expiryEpochSec(c, zone);
        if (expiry == 0 || nowEpochSec >= expiry) return 0;
        return (int) ((expiry - nowEpochSec + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY);
    }

    public static boolean reminderDue(MaintenanceComponent c, long nowEpochSec, ZoneId zone) {
        long expiry = expiryEpochSec(c, zone);
        if (expiry == 0 || nowEpochSec >= expiry) return false;
        if (expiry - nowEpochSec > REMINDER_DAYS_BEFORE * SECONDS_PER_DAY) return false;
        return c.warrantyReminderSentForExpiryEpochSec != expiry;
    }

    /** Components whose reminder is due now, in list order. */
    public static List<MaintenanceComponent> dueReminders(List<MaintenanceComponent> components,
                                                          long nowEpochSec, ZoneId zone) {
        List<MaintenanceComponent> out = new ArrayList<>();
        if (components == null) return out;
        for (MaintenanceComponent c : components) {
            if (reminderDue(c, nowEpochSec, zone)) out.add(c);
        }
        return out;
    }

    public static String formatDate(long epochSec, ZoneId zone) {
        return DATE.format(Instant.ofEpochSecond(epochSec).atZone(zone));
    }

    /**
     * Card line, e.g. "Garantie tot 15-3-2027", with "(nog N dagen)" inside the reminder window,
     * or "Garantie verlopen op 15-3-2027"; null when no warranty is tracked.
     */
    public static String statusText(MaintenanceComponent c, long nowEpochSec, ZoneId zone) {
        long expiry = expiryEpochSec(c, zone);
        if (expiry == 0) return null;
        String date = formatDate(expiry, zone);
        if (nowEpochSec >= expiry) return "Garantie verlopen op " + date;
        int days = daysLeft(c, nowEpochSec, zone);
        if (days <= REMINDER_DAYS_BEFORE) {
            return "Garantie tot " + date + " (nog " + dayLabel(days) + ")";
        }
        return "Garantie tot " + date;
    }

    public static String notificationTitle(MaintenanceComponent c) {
        return "Garantie verloopt bijna: " + c.name;
    }

    public static String notificationText(MaintenanceComponent c, long nowEpochSec, ZoneId zone) {
        return "Nog " + dayLabel(daysLeft(c, nowEpochSec, zone)) + " garantie (tot "
                + formatDate(expiryEpochSec(c, zone), zone) + ").";
    }

    private static String dayLabel(int days) {
        return days == 1 ? "1 dag" : days + " dagen";
    }
}
