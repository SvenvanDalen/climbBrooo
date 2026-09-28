package nl.paree.climbpro.domain.maintenance;

import org.junit.Test;

import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator.Status;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MaintenanceCalculatorTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long DAY = 24L * 60 * 60;
    /** 2026-01-01T00:00Z. */
    private static final long SERVICED = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, UTC).toEpochSecond();

    private static StoredRide ride(long start, double km, String type) {
        StoredRide r = new StoredRide();
        r.startEpochSec = start;
        r.distanceM = (float) (km * 1000);
        r.type = type;
        return r;
    }

    private static MaintenanceComponent component(int km, int months, long lastServiced) {
        MaintenanceComponent c = new MaintenanceComponent("c", "Ketting", km, months);
        c.lastServicedEpochSec = lastServiced;
        return c;
    }

    @Test
    public void kmSinceCountsOnlyRidesAfterService() {
        List<StoredRide> rides = Arrays.asList(
                ride(SERVICED - DAY, 100, "Ride"),   // before service
                ride(SERVICED, 50, "Ride"),          // same instant: not after
                ride(SERVICED + DAY, 40, "Ride"),
                ride(SERVICED + 2 * DAY, 60, "GravelRide"));
        assertEquals(100.0, MaintenanceCalculator.kmSince(rides, SERVICED, false), 1e-6);
    }

    @Test
    public void virtualRideExcludedUnlessOptedIn() {
        List<StoredRide> rides = Arrays.asList(
                ride(SERVICED + DAY, 40, "Ride"),
                ride(SERVICED + DAY, 30, "VirtualRide"),
                ride(SERVICED + DAY, 20, "virtualride"));
        assertEquals(40.0, MaintenanceCalculator.kmSince(rides, SERVICED, false), 1e-6);
        assertEquals(90.0, MaintenanceCalculator.kmSince(rides, SERVICED, true), 1e-6);

        MaintenanceComponent c = component(3000, 0, SERVICED);
        assertEquals(40.0, MaintenanceCalculator.evaluate(c, rides, SERVICED + 2 * DAY, UTC).kmSince, 1e-6);
        c.includeVirtualRides = true;
        assertEquals(90.0, MaintenanceCalculator.evaluate(c, rides, SERVICED + 2 * DAY, UTC).kmSince, 1e-6);
    }

    @Test
    public void undatedNullAndNegativeRidesSkipped() {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(0, 500, "Ride"));
        rides.add(null);
        rides.add(ride(SERVICED + DAY, -5, "Ride"));
        rides.add(ride(SERVICED + DAY, 12, "Ride"));
        assertEquals(12.0, MaintenanceCalculator.kmSince(rides, SERVICED, false), 1e-6);
        assertEquals(0.0, MaintenanceCalculator.kmSince(null, SERVICED, false), 1e-6);
    }

    @Test
    public void unknownServiceDateIsNeverDue() {
        MaintenanceComponent c = component(100, 1, 0);
        Status s = MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED, 5000, "Ride")), SERVICED + 400 * DAY, UTC);
        assertFalse(s.configured);
        assertFalse(s.due);
        assertEquals(0.0, s.kmSince, 1e-6);
        assertNull(MaintenanceCalculator.stateLabel(s));
    }

    @Test
    public void dueByKmAtThreshold() {
        MaintenanceComponent c = component(1000, 0, SERVICED);
        long now = SERVICED + 30 * DAY;

        Status below = MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED + DAY, 999, "Ride")), now, UTC);
        assertFalse(below.due);
        assertTrue(below.soon);
        assertEquals(99, below.wornPercent());

        Status at = MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED + DAY, 1000, "Ride")), now, UTC);
        assertTrue(at.due);
        assertTrue(at.dueByKm);
        assertFalse(at.dueByTime);
        assertFalse(at.soon);
        assertEquals("Vervangen/servicen nodig", MaintenanceCalculator.stateLabel(at));
    }

    @Test
    public void soonFromNinetyPercent() {
        MaintenanceComponent c = component(1000, 0, SERVICED);
        long now = SERVICED + 30 * DAY;
        assertFalse(MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED + DAY, 899, "Ride")), now, UTC).soon);
        Status s = MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED + DAY, 900, "Ride")), now, UTC);
        assertTrue(s.soon);
        assertEquals("Bijna aan de beurt", MaintenanceCalculator.stateLabel(s));
    }

    @Test
    public void dueByCalendarMonths() {
        MaintenanceComponent c = component(0, 12, SERVICED);
        long dueAt = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, UTC).toEpochSecond();

        Status before = MaintenanceCalculator.evaluate(c, null, dueAt - 1, UTC);
        assertFalse(before.due);
        assertEquals(11, before.monthsSince);
        assertTrue(before.soon);

        Status at = MaintenanceCalculator.evaluate(c, null, dueAt, UTC);
        assertTrue(at.due);
        assertTrue(at.dueByTime);
        assertFalse(at.dueByKm);
        assertEquals(12, at.monthsSince);
        assertEquals(100, at.wornPercent());
    }

    @Test
    public void eitherCriterionMakesDue() {
        MaintenanceComponent c = component(5000, 12, SERVICED);
        List<StoredRide> rides = Collections.singletonList(ride(SERVICED + DAY, 5200, "Ride"));
        Status s = MaintenanceCalculator.evaluate(c, rides, SERVICED + 60 * DAY, UTC);
        assertTrue(s.due);
        assertTrue(s.dueByKm);
        assertFalse(s.dueByTime);
        assertEquals(104, s.wornPercent());
    }

    @Test
    public void noIntervalsNeverDue() {
        MaintenanceComponent c = component(0, 0, SERVICED);
        Status s = MaintenanceCalculator.evaluate(c,
                Collections.singletonList(ride(SERVICED + DAY, 99999, "Ride")),
                SERVICED + 900 * DAY, UTC);
        assertTrue(s.configured);
        assertFalse(s.due);
        assertFalse(s.soon);
        assertEquals(99999.0, s.kmSince, 1e-6);
    }

    @Test
    public void recordServiceResetsCounterAndKeepsHistory() {
        MaintenanceComponent c = component(1000, 0, 0);
        List<StoredRide> rides = Collections.singletonList(ride(SERVICED + DAY, 1200, "Ride"));

        MaintenanceCalculator.recordService(c, SERVICED);
        assertTrue(MaintenanceCalculator.evaluate(c, rides, SERVICED + 2 * DAY, UTC).due);

        long now = SERVICED + 2 * DAY;
        MaintenanceCalculator.recordService(c, now);
        assertEquals(now, c.lastServicedEpochSec);
        assertEquals(Arrays.asList(SERVICED, now), c.serviceHistory);
        Status after = MaintenanceCalculator.evaluate(c, rides, now + DAY, UTC);
        assertFalse(after.due);
        assertEquals(0.0, after.kmSince, 1e-6);
    }

    @Test
    public void recordServiceKeepsLegacyDateAndCapsHistory() {
        MaintenanceComponent c = component(1000, 0, SERVICED);   // date without history
        MaintenanceCalculator.recordService(c, SERVICED + DAY);
        assertEquals(Arrays.asList(SERVICED, SERVICED + DAY), c.serviceHistory);

        for (int i = 2; i <= 20; i++) MaintenanceCalculator.recordService(c, SERVICED + i * DAY);
        assertEquals(MaintenanceCalculator.MAX_HISTORY, c.serviceHistory.size());
        assertEquals(SERVICED + 20 * DAY, c.lastServicedEpochSec);
        assertEquals(Long.valueOf(SERVICED + 11 * DAY), c.serviceHistory.get(0));
    }

    @Test
    public void correctLastServiceReplacesNewestEntry() {
        MaintenanceComponent c = component(1000, 0, 0);
        MaintenanceCalculator.correctLastService(c, SERVICED);
        assertEquals(SERVICED, c.lastServicedEpochSec);
        assertEquals(Collections.singletonList(SERVICED), c.serviceHistory);

        MaintenanceCalculator.recordService(c, SERVICED + 10 * DAY);
        MaintenanceCalculator.correctLastService(c, SERVICED + 8 * DAY);
        assertEquals(SERVICED + 8 * DAY, c.lastServicedEpochSec);
        assertEquals(Arrays.asList(SERVICED, SERVICED + 8 * DAY), c.serviceHistory);
    }

    @Test
    public void bannerListsDueComponentsOnly() {
        MaintenanceComponent chain = component(1000, 0, SERVICED);
        MaintenanceComponent tires = new MaintenanceComponent("t", "Banden", 1000, 0);
        tires.lastServicedEpochSec = SERVICED;
        MaintenanceComponent pads = new MaintenanceComponent("p", "Remblokken", 5000, 0);
        pads.lastServicedEpochSec = SERVICED;
        List<StoredRide> rides = Collections.singletonList(ride(SERVICED + DAY, 1500, "Ride"));

        List<Status> all = MaintenanceCalculator.evaluateAll(
                Arrays.asList(chain, null, tires, pads), rides, SERVICED + 2 * DAY, UTC);
        assertEquals(3, all.size());
        assertEquals("Onderhoud nodig: Ketting, Banden", MaintenanceCalculator.bannerText(all));

        assertNull(MaintenanceCalculator.bannerText(MaintenanceCalculator.evaluateAll(
                Collections.singletonList(pads), rides, SERVICED + 2 * DAY, UTC)));
        assertNull(MaintenanceCalculator.bannerText(null));
    }
}
