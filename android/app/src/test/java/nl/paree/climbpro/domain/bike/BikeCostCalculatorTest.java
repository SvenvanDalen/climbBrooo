package nl.paree.climbpro.domain.bike;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostEntry;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BikeCostCalculatorTest {

    private static StoredRide ride(long start, float meters, String type) {
        StoredRide r = new StoredRide();
        r.startEpochSec = start;
        r.distanceM = meters;
        r.type = type;
        return r;
    }

    private static BikeCostEntry cost(String kind, long cents) {
        BikeCostEntry c = new BikeCostEntry();
        c.kind = kind;
        c.amountCents = cents;
        return c;
    }

    private static Bike bike(long since, long retired, boolean archive, int extraKm) {
        Bike b = new Bike();
        b.id = "b";
        b.name = "Racefiets";
        b.sinceEpochSec = since;
        b.retiredEpochSec = retired;
        b.countArchiveRides = archive;
        b.extraKm = extraKm;
        return b;
    }

    @Test
    public void archiveWindowIncludesSinceAndExcludesRetired() {
        List<StoredRide> rides = Arrays.asList(
                ride(999, 1000, "Ride"),     // before since
                ride(1000, 2000, "Ride"),    // exactly at since: counts
                ride(4999, 3000, "Ride"),    // counts
                ride(5000, 4000, "Ride"));   // exactly at retired: not
        assertEquals(5000, BikeCostCalculator.archiveMeters(rides, 1000, 5000, false));
        assertEquals(9000, BikeCostCalculator.archiveMeters(rides, 1000, 0, false));
    }

    @Test
    public void archiveSkipsUnknownStartAndVirtualUnlessOptedIn() {
        List<StoredRide> rides = Arrays.asList(
                ride(0, 5000, "Ride"),
                ride(2000, 1000, "VirtualRide"),
                ride(2000, 1500, "GravelRide"),
                ride(2000, -20, "Ride"),
                null);
        assertEquals(1500, BikeCostCalculator.archiveMeters(rides, 1000, 0, false));
        assertEquals(2500, BikeCostCalculator.archiveMeters(rides, 1000, 0, true));
        assertEquals(0, BikeCostCalculator.archiveMeters(null, 1000, 0, true));
        assertEquals(0, BikeCostCalculator.archiveMeters(rides, 0, 0, true));
    }

    @Test
    public void evaluateCombinesArchiveExtraKmAndCosts() {
        Bike b = bike(1000, 0, true, 90);
        b.costs.add(cost(BikeCostEntry.KIND_PURCHASE, 199_900));
        b.costs.add(cost(BikeCostEntry.KIND_PART, 2_450));
        b.costs.add(cost(BikeCostEntry.KIND_PART, 5_000));
        List<StoredRide> rides = Arrays.asList(ride(2000, 10_000, "Ride"));

        BikeCostCalculator.Summary s = BikeCostCalculator.evaluate(b, rides);

        assertEquals(10_000, s.archiveMeters);
        assertEquals(100_000, s.totalMeters);
        assertEquals(199_900, s.purchaseCents);
        assertEquals(7_450, s.partsCents);
        assertEquals(207_350, s.totalCents);
        // 207350 cents / 100 km = 2073,5 -> half up = 2074
        assertEquals(2074, s.costPerKmCents);
        assertTrue(s.hasRate());
        assertEquals("€ 20,74 per km", BikeCostCalculator.rateText(s));
        assertEquals("Aankoop € 1.999,00  •  Onderdelen € 74,50",
                BikeCostCalculator.breakdownText(s));
        assertEquals("100 km", BikeCostCalculator.kmText(s.totalMeters));
    }

    @Test
    public void zeroKmHasNoRate() {
        Bike b = bike(0, 0, true, 0);
        b.costs.add(cost(BikeCostEntry.KIND_PURCHASE, 250_000));
        BikeCostCalculator.Summary s = BikeCostCalculator.evaluate(b, new ArrayList<>());
        assertEquals(0, s.totalMeters);
        assertFalse(s.hasRate());
        assertEquals(BikeCostCalculator.NO_RATE, s.costPerKmCents);
        assertEquals("Nog geen km: kosten per km onbekend", BikeCostCalculator.rateText(s));
    }

    @Test
    public void belowOneKmHasNoRate() {
        assertEquals(BikeCostCalculator.NO_RATE, BikeCostCalculator.costPerKmCents(250_000, 999));
        assertEquals(250_000, BikeCostCalculator.costPerKmCents(250_000, 1000));
        assertEquals(BikeCostCalculator.NO_RATE, BikeCostCalculator.costPerKmCents(100, 0));
    }

    @Test
    public void kmButNoCostsSaysSo() {
        BikeCostCalculator.Summary s =
                BikeCostCalculator.evaluate(bike(0, 0, false, 500), null);
        assertTrue(s.hasRate());
        assertEquals(0, s.costPerKmCents);
        assertEquals("Nog geen kosten ingevoerd", BikeCostCalculator.rateText(s));
    }

    @Test
    public void archiveOffUsesOnlyExtraKm() {
        Bike b = bike(1000, 0, false, 42);
        BikeCostCalculator.Summary s =
                BikeCostCalculator.evaluate(b, Arrays.asList(ride(2000, 80_000, "Ride")));
        assertEquals(0, s.archiveMeters);
        assertEquals(42_000, s.totalMeters);
    }

    @Test
    public void negativeExtraKmAndInvalidCostsAreIgnored() {
        Bike b = bike(0, 0, true, -50);
        b.costs.add(cost(BikeCostEntry.KIND_PART, -500));
        b.costs.add(null);
        b.costs.add(cost("SOMETHING_ELSE", 300));
        BikeCostCalculator.Summary s = BikeCostCalculator.evaluate(b, null);
        assertEquals(0, s.totalMeters);
        assertEquals(300, s.partsCents);
        assertEquals(300, s.totalCents);
    }

    @Test
    public void roundsHalfUpAndHandlesLargeTotals() {
        assertEquals(33, BikeCostCalculator.costPerKmCents(100, 3000));   // 33,33
        assertEquals(67, BikeCostCalculator.costPerKmCents(200, 3000));   // 66,67
        assertEquals(5_000_000_000L,
                BikeCostCalculator.costPerKmCents(EuroAmount.MAX_CENTS * 50, 1000));
    }

    @Test
    public void kmTextTruncatesAndGroups() {
        assertEquals("0 km", BikeCostCalculator.kmText(0));
        assertEquals("0 km", BikeCostCalculator.kmText(500));
        assertEquals("0 km", BikeCostCalculator.kmText(999));
        assertEquals("1 km", BikeCostCalculator.kmText(1000));
        assertEquals("1.234 km", BikeCostCalculator.kmText(1_234_567));
    }

    @Test
    public void rateTextShowsBelowOneCentInsteadOfZero() {
        // 1 cent over 2000 m rounds to 0 cents/km (1 * 1000 / 2000 = 0,5 -> half up = 1... use
        // a total that truly rounds to 0): 1 cent / 3000 m -> (1000 + 1500) / 3000 = 0.
        Bike b = bike(0, 0, false, 3);
        b.costs.add(cost(BikeCostEntry.KIND_PART, 1));
        BikeCostCalculator.Summary s = BikeCostCalculator.evaluate(b, null);
        assertEquals(0, s.costPerKmCents);
        assertTrue(s.hasRate());
        assertEquals("< € 0,01 per km", BikeCostCalculator.rateText(s));
    }

    @Test
    public void evaluateAllSkipsNullBikes() {
        List<Bike> bikes = new ArrayList<>();
        bikes.add(bike(0, 0, false, 1));
        bikes.add(null);
        assertEquals(1, BikeCostCalculator.evaluateAll(bikes, null).size());
        assertEquals(0, BikeCostCalculator.evaluateAll(null, null).size());
    }
}
