package nl.paree.climbpro.domain.tire;

import org.junit.Test;

import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.tire.TirePressureLogEntry;
import nl.paree.climbpro.domain.tire.TirePressureReminderCalculator.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TirePressureReminderCalculatorTest {

    private static final long DAY = 86_400L;
    private static final long T0  = 1_750_000_000L;

    private static TirePressureLogEntry entry(long ts) {
        TirePressureLogEntry e = new TirePressureLogEntry();
        e.id = "e" + ts;
        e.timestampEpochSec = ts;
        e.frontBar = 6.0;
        e.rearBar = 6.5;
        return e;
    }

    private static StoredRide ride(long start, float km, String type) {
        StoredRide r = new StoredRide();
        r.activityId = start;
        r.startEpochSec = start;
        r.distanceM = km * 1000f;
        r.type = type;
        return r;
    }

    @Test
    public void dueByDays() {
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), new ArrayList<>(), 7, 300, T0 + 9 * DAY + 5);
        assertTrue(s.due);
        assertTrue(s.dueByDays);
        assertFalse(s.dueByKm);
        assertEquals(9, s.daysSince);
        assertEquals("Bandenspanning controleren (9 dagen / 0 km geleden)",
                TirePressureReminderCalculator.bannerText(s));
    }

    @Test
    public void dueExactlyAtDayThreshold() {
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), null, 7, 0, T0 + 7 * DAY);
        assertTrue(s.dueByDays);
        Status before = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), null, 7, 0, T0 + 7 * DAY - 1);
        assertFalse(before.due);
        assertEquals(6, before.daysSince);
    }

    @Test
    public void dueByKm() {
        List<StoredRide> rides = Arrays.asList(
                ride(T0 + DAY, 150f, "Ride"),
                ride(T0 + 2 * DAY, 190f, "GravelRide"));
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 7, 300, T0 + 3 * DAY);
        assertTrue(s.due);
        assertTrue(s.dueByKm);
        assertFalse(s.dueByDays);
        assertEquals(340.0, s.kmSince, 1e-6);
        assertEquals("Bandenspanning controleren (3 dagen / 340 km geleden)",
                TirePressureReminderCalculator.bannerText(s));
    }

    @Test
    public void neitherThresholdReached() {
        List<StoredRide> rides = Collections.singletonList(ride(T0 + DAY, 100f, "Ride"));
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 7, 300, T0 + 2 * DAY);
        assertTrue(s.hasEntries);
        assertFalse(s.due);
        assertNull(TirePressureReminderCalculator.bannerText(s));
        assertEquals("2 dagen / 100 km geleden", TirePressureReminderCalculator.sinceText(s));
    }

    @Test
    public void thresholdsOff_neverDue() {
        List<StoredRide> rides = Collections.singletonList(ride(T0 + DAY, 5000f, "Ride"));
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 0, 0, T0 + 365 * DAY);
        assertFalse(s.due);
        assertEquals(365, s.daysSince);
    }

    @Test
    public void onlyKmOff_daysStillApply() {
        List<StoredRide> rides = Collections.singletonList(ride(T0 + DAY, 5000f, "Ride"));
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 7, 0, T0 + 2 * DAY);
        assertFalse(s.due);
        Status later = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 7, 0, T0 + 8 * DAY);
        assertTrue(later.dueByDays);
        assertFalse(later.dueByKm);
    }

    @Test
    public void noEntries_notDue() {
        List<StoredRide> rides = Collections.singletonList(ride(T0, 1000f, "Ride"));
        Status s = TirePressureReminderCalculator.evaluate(
                new ArrayList<>(), rides, 7, 300, T0 + 100 * DAY);
        assertFalse(s.hasEntries);
        assertFalse(s.due);
        assertNull(TirePressureReminderCalculator.bannerText(s));
        assertFalse(TirePressureReminderCalculator.evaluate(null, null, 7, 300, T0).due);
    }

    @Test
    public void virtualRideExcluded() {
        List<StoredRide> rides = Arrays.asList(
                ride(T0 + DAY, 400f, "VirtualRide"),
                ride(T0 + DAY, 50f, "Ride"));
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), rides, 0, 300, T0 + 2 * DAY);
        assertFalse(s.due);
        assertEquals(50.0, s.kmSince, 1e-6);
    }

    @Test
    public void onlyRidesAfterLatestEntryCount() {
        List<StoredRide> rides = Arrays.asList(
                ride(T0 - DAY, 500f, "Ride"),       // before the older check
                ride(T0 + DAY, 500f, "Ride"),       // between the two checks
                ride(T0 + 3 * DAY, 80f, "Ride"),    // after the latest check
                ride(0, 900f, "Ride"));             // unknown start date: skipped
        List<TirePressureLogEntry> entries = Arrays.asList(entry(T0 + 2 * DAY), entry(T0));
        Status s = TirePressureReminderCalculator.evaluate(entries, rides, 0, 300, T0 + 4 * DAY);
        assertFalse(s.due);
        assertEquals(80.0, s.kmSince, 1e-6);
        assertEquals(2, s.daysSince);
    }

    @Test
    public void entryInFuture_clampsDaysToZero() {
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0 + 5 * DAY)), null, 7, 300, T0);
        assertEquals(0, s.daysSince);
        assertFalse(s.due);
    }

    @Test
    public void singularDayText() {
        Status s = TirePressureReminderCalculator.evaluate(
                Collections.singletonList(entry(T0)), null, 1, 0, T0 + DAY);
        assertEquals("Bandenspanning controleren (1 dag / 0 km geleden)",
                TirePressureReminderCalculator.bannerText(s));
    }
}
