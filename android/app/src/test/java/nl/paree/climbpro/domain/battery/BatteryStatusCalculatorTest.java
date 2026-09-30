package nl.paree.climbpro.domain.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.battery.BatteryDevice;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BatteryStatusCalculatorTest {

    private static final long DAY = 86_400L;
    private static final long T0 = 1_700_000_000L;

    private static BatteryDevice device(String name, long charged, int interval) {
        BatteryDevice d = new BatteryDevice();
        d.id = name;
        d.name = name;
        d.kind = BatteryKind.LIGHT.name();
        d.lastChargedEpochSec = charged;
        d.intervalDays = interval;
        return d;
    }

    @Test
    public void noReminderWithoutIntervalOrCharge() {
        assertEquals(-1, BatteryStatusCalculator.dueEpochSec(device("a", T0, 0)));
        assertEquals(-1, BatteryStatusCalculator.dueEpochSec(device("a", 0, 14)));
        assertFalse(BatteryStatusCalculator.isDue(device("a", 0, 14), T0 + 100 * DAY));
    }

    @Test
    public void dueExactlyAfterInterval() {
        BatteryDevice d = device("a", T0, 14);
        assertFalse(BatteryStatusCalculator.isDue(d, T0 + 14 * DAY - 1));
        assertTrue(BatteryStatusCalculator.isDue(d, T0 + 14 * DAY));
    }

    @Test
    public void daysUntilDueRoundsUp() {
        BatteryDevice d = device("a", T0, 14);
        assertEquals(14, BatteryStatusCalculator.daysUntilDue(d, T0));
        assertEquals(1, BatteryStatusCalculator.daysUntilDue(d, T0 + 14 * DAY - 3600));
        assertEquals(0, BatteryStatusCalculator.daysUntilDue(d, T0 + 20 * DAY));
    }

    @Test
    public void dueRemindersSkipsAlreadyNotifiedCycle() {
        BatteryDevice due = device("due", T0, 7);
        BatteryDevice notified = device("notified", T0, 7);
        notified.reminderSentForChargeEpochSec = T0;
        BatteryDevice recharged = device("recharged", T0 + 8 * DAY, 7);
        recharged.reminderSentForChargeEpochSec = T0; // previous cycle
        List<BatteryDevice> out = BatteryStatusCalculator.dueReminders(
                Arrays.asList(due, notified, recharged, null), T0 + 16 * DAY);
        assertEquals(2, out.size());
        assertEquals("due", out.get(0).id);
        assertEquals("recharged", out.get(1).id);
    }

    @Test
    public void sortByUrgency_dueFirstThenSoonestThenNoReminder() {
        BatteryDevice never = device("z-never", 0, 14);
        BatteryDevice off = device("a-off", T0, 0);
        BatteryDevice soon = device("soon", T0, 10);
        BatteryDevice later = device("later", T0, 30);
        BatteryDevice overdue = device("overdue", T0 - 20 * DAY, 7);
        List<BatteryDevice> out = BatteryStatusCalculator.sortByUrgency(
                Arrays.asList(never, off, later, soon, overdue), T0);
        assertEquals(Arrays.asList("overdue", "soon", "later", "a-off", "z-never"),
                Arrays.asList(out.get(0).id, out.get(1).id, out.get(2).id, out.get(3).id,
                        out.get(4).id));
    }

    @Test
    public void statusTexts() {
        assertEquals("Nog niet geladen gelogd",
                BatteryStatusCalculator.statusText(device("a", 0, 14), T0));
        assertEquals("Vandaag geladen · opladen over 14 dagen",
                BatteryStatusCalculator.statusText(device("a", T0, 14), T0 + 60));
        assertEquals("Geladen 1 dag geleden · geen herinnering",
                BatteryStatusCalculator.statusText(device("a", T0, 0), T0 + DAY));
        assertEquals("Opladen! geladen 15 dagen geleden",
                BatteryStatusCalculator.statusText(device("a", T0, 14), T0 + 15 * DAY));
    }

    @Test
    public void kindFromName_fallsBackToOther() {
        assertEquals(BatteryKind.POWER_METER, BatteryKind.fromName("POWER_METER"));
        assertEquals(BatteryKind.OTHER, BatteryKind.fromName("bogus"));
        assertEquals(BatteryKind.OTHER, BatteryKind.fromName(null));
        assertTrue(BatteryStatusCalculator.dueReminders(null, T0).isEmpty());
        assertTrue(BatteryStatusCalculator.sortByUrgency(Collections.emptyList(), T0).isEmpty());
    }
}
