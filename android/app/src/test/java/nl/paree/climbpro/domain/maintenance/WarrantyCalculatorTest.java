package nl.paree.climbpro.domain.maintenance;

import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.dueReminders;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.expiryEpochSec;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.hasWarranty;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.notificationText;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.notificationTitle;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.reminderDue;
import static nl.paree.climbpro.domain.maintenance.WarrantyCalculator.statusText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WarrantyCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final long DAY = 24L * 60 * 60;

    private static long at(int y, int m, int d) {
        return ZonedDateTime.of(y, m, d, 12, 0, 0, 0, ZONE).toEpochSecond();
    }

    private static MaintenanceComponent part(long purchase, int months) {
        MaintenanceComponent c = new MaintenanceComponent("wheels", "Wielset", 0, 0);
        c.warrantyPurchaseEpochSec = purchase;
        c.warrantyMonths = months;
        return c;
    }

    @Test
    public void noWarrantyWithoutDateOrTerm() {
        long now = at(2026, 1, 1);
        for (MaintenanceComponent c : Arrays.asList(part(0, 24), part(at(2025, 1, 1), 0), null)) {
            assertFalse(hasWarranty(c));
            assertEquals(0, expiryEpochSec(c, ZONE));
            assertEquals(0, WarrantyCalculator.daysLeft(c, now, ZONE));
            assertFalse(reminderDue(c, now, ZONE));
            assertNull(statusText(c, now, ZONE));
        }
    }

    @Test
    public void expiryAddsCalendarMonthsClampedToMonthEnd() {
        assertEquals(at(2025, 2, 28), expiryEpochSec(part(at(2025, 1, 31), 1), ZONE));
        assertEquals(at(2027, 3, 15), expiryEpochSec(part(at(2025, 3, 15), 24), ZONE));
    }

    @Test
    public void reminderWindowIsLast30DaysBeforeExpiry() {
        MaintenanceComponent c = part(at(2025, 3, 15), 24);
        long exp = at(2027, 3, 15);
        assertFalse(reminderDue(c, exp - 30 * DAY - 1, ZONE));
        assertTrue(reminderDue(c, exp - 30 * DAY, ZONE));
        assertTrue(reminderDue(c, exp - 1, ZONE));
        assertFalse(reminderDue(c, exp, ZONE));
        assertFalse(reminderDue(c, exp + 10 * DAY, ZONE));
    }

    @Test
    public void reminderFiresOncePerExpiry() {
        MaintenanceComponent c = part(at(2025, 3, 15), 24);
        c.warrantyReminderSentForExpiryEpochSec = expiryEpochSec(c, ZONE);
        assertFalse(reminderDue(c, at(2027, 3, 1), ZONE));
    }

    @Test
    public void changedPurchaseDateReArmsReminder() {
        MaintenanceComponent c = part(at(2025, 3, 15), 24);
        c.warrantyReminderSentForExpiryEpochSec = expiryEpochSec(c, ZONE);
        c.warrantyPurchaseEpochSec = at(2025, 3, 20); // user corrected the receipt date
        assertTrue(reminderDue(c, at(2027, 3, 1), ZONE));
    }

    @Test
    public void dueRemindersFiltersInListOrder() {
        long now = at(2027, 3, 1);
        MaintenanceComponent a = part(at(2025, 3, 15), 24); // expires 15-3-2027: due
        MaintenanceComponent b = part(at(2025, 9, 1), 24);  // expires 1-9-2027: not yet
        MaintenanceComponent d = part(at(2026, 3, 20), 12); // expires 20-3-2027: due
        List<MaintenanceComponent> list = new ArrayList<>(Arrays.asList(a, null, b, d));
        assertEquals(Arrays.asList(a, d), dueReminders(list, now, ZONE));
        assertTrue(dueReminders(null, now, ZONE).isEmpty());
    }

    @Test
    public void statusTexts() {
        MaintenanceComponent c = part(at(2025, 3, 15), 24);
        assertEquals("Garantie tot 15-3-2027", statusText(c, at(2026, 1, 1), ZONE));
        assertEquals("Garantie tot 15-3-2027 (nog 14 dagen)", statusText(c, at(2027, 3, 1), ZONE));
        assertEquals("Garantie tot 15-3-2027 (nog 1 dag)",
                statusText(c, at(2027, 3, 15) - 60, ZONE));
        assertEquals("Garantie verlopen op 15-3-2027", statusText(c, at(2027, 3, 15), ZONE));
    }

    @Test
    public void notificationTexts() {
        MaintenanceComponent c = part(at(2025, 3, 15), 24);
        assertEquals("Garantie verloopt bijna: Wielset", notificationTitle(c));
        assertEquals("Nog 14 dagen garantie (tot 15-3-2027).",
                notificationText(c, at(2027, 3, 1), ZONE));
    }
}
