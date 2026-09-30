package nl.paree.climbpro.domain.bike;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BikeGarageTest {

    private static Bike bike(String id, String type) {
        Bike b = new Bike();
        b.id = id;
        b.name = id;
        b.type = type;
        return b;
    }

    private static StoredRide ride(String type, String gearId, float meters) {
        StoredRide r = new StoredRide();
        r.type = type;
        r.gearId = gearId;
        r.distanceM = meters;
        r.startEpochSec = 1000;
        return r;
    }

    private static BikeCostLog garage(Bike... bikes) {
        BikeCostLog log = new BikeCostLog();
        log.bikes.addAll(Arrays.asList(bikes));
        return log;
    }

    // --- active / indoor bike -------------------------------------------------------------

    @Test
    public void activeBikeIsExplicitChoiceElseFirstInUse() {
        Bike old = bike("old", Bike.TYPE_ROAD);
        old.retiredEpochSec = 5;
        Bike road = bike("road", Bike.TYPE_ROAD);
        BikeCostLog log = garage(old, road);
        assertSame(road, BikeGarage.activeBike(log));
        log.activeBikeId = "old";
        assertSame(old, BikeGarage.activeBike(log));
        log.activeBikeId = "gone";
        assertSame(road, BikeGarage.activeBike(log));
        assertNull(BikeGarage.activeBike(new BikeCostLog()));
        assertNull(BikeGarage.activeBike(null));
    }

    @Test
    public void activeBikeFallsBackToRetiredWhenAllRetired() {
        Bike old = bike("old", Bike.TYPE_ROAD);
        old.retiredEpochSec = 5;
        assertSame(old, BikeGarage.activeBike(garage(old)));
    }

    @Test
    public void indoorBikeIsExplicitChoiceElseFirstTrainerTypeElseNone() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike trainer = bike("trainer", Bike.TYPE_TRAINER);
        BikeCostLog log = garage(road, trainer);
        assertSame(trainer, BikeGarage.indoorBike(log));
        log.indoorBikeId = "road"; // road bike on the trainer
        assertSame(road, BikeGarage.indoorBike(log));
        assertNull(BikeGarage.indoorBike(garage(road)));
    }

    // --- ride assignment ------------------------------------------------------------------

    @Test
    public void gearIdMatchWins() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike gravel = bike("gravel", Bike.TYPE_GRAVEL);
        gravel.stravaGearId = "b2";
        BikeCostLog log = garage(road, gravel);
        assertEquals("gravel", BikeGarage.bikeIdForRide(ride("Ride", "b2", 1), log));
        // A VirtualRide on a gear-tagged bike stays on that bike (road bike on the trainer).
        assertEquals("gravel", BikeGarage.bikeIdForRide(ride("VirtualRide", "b2", 1), log));
    }

    @Test
    public void virtualRideWithoutGearMatchGoesToIndoorBike() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike trainer = bike("trainer", Bike.TYPE_TRAINER);
        BikeCostLog log = garage(road, trainer);
        assertEquals("trainer", BikeGarage.bikeIdForRide(ride("VirtualRide", null, 1), log));
        assertEquals("trainer", BikeGarage.bikeIdForRide(ride("VirtualRide", "b9", 1), log));
    }

    @Test
    public void virtualRideWithoutIndoorBikeGoesToActiveBike() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike gravel = bike("gravel", Bike.TYPE_GRAVEL);
        BikeCostLog log = garage(road, gravel);
        log.activeBikeId = "gravel";
        assertEquals("gravel", BikeGarage.bikeIdForRide(ride("VirtualRide", null, 1), log));
    }

    @Test
    public void outdoorRideWithoutGearMatchGoesToActiveBike() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike gravel = bike("gravel", Bike.TYPE_GRAVEL);
        BikeCostLog log = garage(road, gravel);
        log.activeBikeId = "gravel";
        assertEquals("gravel", BikeGarage.bikeIdForRide(ride("Ride", null, 1), log));
        assertEquals("gravel", BikeGarage.bikeIdForRide(ride("Ride", "unknown", 1), log));
    }

    @Test
    public void outdoorRideSkipsActiveTrainerBikeWhenAnotherBikeExists() {
        Bike trainer = bike("trainer", Bike.TYPE_TRAINER);
        Bike road = bike("road", Bike.TYPE_ROAD);
        BikeCostLog log = garage(trainer, road);
        log.activeBikeId = "trainer"; // winter: the trainer bike is active
        assertEquals("road", BikeGarage.bikeIdForRide(ride("Ride", null, 1), log));
        // Only a trainer bike: the ride still lands somewhere.
        assertEquals("trainer", BikeGarage.bikeIdForRide(ride("Ride", null, 1), garage(trainer)));
    }

    @Test
    public void emptyGarageAssignsNothing() {
        assertNull(BikeGarage.bikeIdForRide(ride("Ride", "b1", 1), new BikeCostLog()));
        assertNull(BikeGarage.bikeIdForRide(ride("Ride", "b1", 1), null));
        assertNull(BikeGarage.bikeIdForRide(null, garage(bike("a", Bike.TYPE_ROAD))));
    }

    @Test
    public void ridesForBikeFiltersByAssignment() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike trainer = bike("trainer", Bike.TYPE_TRAINER);
        BikeCostLog log = garage(road, trainer);
        List<StoredRide> rides = Arrays.asList(ride("Ride", null, 1000),
                ride("VirtualRide", null, 2000), null, ride("Ride", null, 4000));
        assertEquals(2, BikeGarage.ridesForBike(rides, log, "road").size());
        assertEquals(1, BikeGarage.ridesForBike(rides, log, "trainer").size());
        assertTrue(BikeGarage.ridesForBike(null, log, "road").isEmpty());
    }

    @Test
    public void trainerBikeAlwaysCountsVirtualRides() {
        assertTrue(BikeGarage.countsVirtualRides(bike("t", Bike.TYPE_TRAINER), false));
        assertFalse(BikeGarage.countsVirtualRides(bike("r", Bike.TYPE_ROAD), false));
        assertTrue(BikeGarage.countsVirtualRides(bike("r", Bike.TYPE_ROAD), true));
        assertFalse(BikeGarage.countsVirtualRides(null, false));
    }

    @Test
    public void totalsPerBikeCountsRidesAndMeters() {
        Bike road = bike("road", Bike.TYPE_ROAD);
        Bike trainer = bike("trainer", Bike.TYPE_TRAINER);
        BikeCostLog log = garage(road, trainer);
        Map<String, long[]> totals = BikeGarage.totalsPerBike(Arrays.asList(
                ride("Ride", null, 1000.4f), ride("VirtualRide", null, 2000),
                ride("Ride", null, 4000)), log);
        assertEquals(2, totals.get("road")[0]);
        assertEquals(5000, totals.get("road")[1]);
        assertEquals(1, totals.get("trainer")[0]);
        assertEquals(2000, totals.get("trainer")[1]);
    }

    @Test
    public void gearIdsInArchiveAreSortedByRideCount() {
        List<StoredRide> rides = Arrays.asList(ride("Ride", "b1", 1000),
                ride("Ride", "b2", 1000), ride("Ride", "b2", 3000), ride("Ride", null, 1000));
        List<BikeGarage.GearUsage> gears = BikeGarage.gearIdsInArchive(rides);
        assertEquals(2, gears.size());
        assertEquals("b2", gears.get(0).gearId);
        assertEquals(2, gears.get(0).rides);
        assertEquals(4000, gears.get(0).meters);
        assertEquals("b1", gears.get(1).gearId);
        assertTrue(BikeGarage.gearIdsInArchive(null).isEmpty());
    }

    // --- migration ------------------------------------------------------------------------

    @Test
    public void migrationCreatesBikeFromProfileWeightWhenGarageEmpty() {
        BikeCostLog log = new BikeCostLog();
        assertTrue(BikeGarage.migrate(log, 8.2, Collections.emptyList(), "50/34", "11-32"));
        assertEquals(BikeCostLog.GARAGE_VERSION, log.version);
        assertEquals(1, log.bikes.size());
        Bike b = log.bikes.get(0);
        assertEquals("Mijn fiets", b.name);
        assertEquals(8.2, b.weightKg, 1e-9);
        assertEquals("50/34", b.chainrings);
        assertEquals("11-32", b.cassette);
        assertEquals(b.id, log.activeBikeId);
        // New garage bikes don't suddenly count archive km in the cost overview.
        assertEquals(0, b.sinceEpochSec);
    }

    @Test
    public void migrationWithNothingToMigrateOnlyBumpsVersion() {
        BikeCostLog log = new BikeCostLog();
        assertFalse(BikeGarage.migrate(log, 0, Collections.emptyList(), null, null));
        assertEquals(BikeCostLog.GARAGE_VERSION, log.version);
        assertTrue(log.bikes.isEmpty());
        assertNull(log.activeBikeId);
    }

    @Test
    public void migrationKeepsCostBikesAndGivesFirstThePreviousWeight() {
        Bike racer = bike("racer", null);
        racer.name = "Racefiets";
        racer.sinceEpochSec = 123;
        Bike gravel = bike("gravel", null);
        gravel.name = "Gravelfiets";
        BikeCostLog log = garage(racer, gravel);
        assertTrue(BikeGarage.migrate(log, 7.5, Collections.emptyList(), null, null));
        assertEquals(2, log.bikes.size());
        assertEquals("racer", log.activeBikeId);
        assertEquals(7.5, racer.weightKg, 1e-9);
        assertEquals(0, gravel.weightKg, 1e-9);
        assertEquals(123, racer.sinceEpochSec);
        assertEquals(Bike.TYPE_ROAD, racer.type);
    }

    @Test
    public void migrationAddsPassportBikesNotAlreadyInGarage() {
        Bike racer = bike("racer", Bike.TYPE_ROAD);
        racer.name = "Racefiets";
        BikeCostLog log = garage(racer);
        assertTrue(BikeGarage.migrate(log, 0,
                Arrays.asList(" racefiets ", "Mountainbike", "Mountainbike", "", null),
                null, null));
        assertEquals(2, log.bikes.size());
        assertEquals("Mountainbike", log.bikes.get(1).name);
        assertEquals("racer", log.activeBikeId);
    }

    @Test
    public void migrationGuessesTypeFromName() {
        BikeCostLog log = new BikeCostLog();
        BikeGarage.migrate(log, 0, Arrays.asList("Gravelbike", "MTB", "Tacx trainer",
                "Racefiets"), null, null);
        assertEquals(Bike.TYPE_GRAVEL, log.bikes.get(0).type);
        assertEquals(Bike.TYPE_MTB, log.bikes.get(1).type);
        assertEquals(Bike.TYPE_TRAINER, log.bikes.get(2).type);
        assertEquals(Bike.TYPE_ROAD, log.bikes.get(3).type);
        // The trainer never becomes the active (outdoor default) bike on migration.
        assertEquals(log.bikes.get(0).id, log.activeBikeId);
    }

    @Test
    public void migrationIsDeterministicAndRunsOnce() {
        BikeCostLog a = new BikeCostLog();
        BikeCostLog b = new BikeCostLog();
        BikeGarage.migrate(a, 9, Arrays.asList("X", "Z"), null, null);
        BikeGarage.migrate(b, 9, Arrays.asList("X", "Z"), null, null);
        // The passport bikes are the garage; the profile weight goes to the active one.
        assertEquals(2, a.bikes.size());
        assertEquals(9, a.bikes.get(0).weightKg, 1e-9);
        assertEquals(a.bikes.get(0).id, b.bikes.get(0).id);
        assertEquals(a.bikes.get(1).id, b.bikes.get(1).id);
        assertFalse(BikeGarage.migrate(a, 12, Arrays.asList("Y"), null, null));
        assertEquals(2, a.bikes.size());
    }

    @Test
    public void migrationDoesNotOverwriteExistingWeightOrGearing() {
        Bike racer = bike("racer", Bike.TYPE_ROAD);
        racer.weightKg = 6.9;
        racer.chainrings = "52/36";
        BikeCostLog log = garage(racer);
        BikeGarage.migrate(log, 8, new ArrayList<>(), "50/34", "11-30");
        assertEquals(6.9, racer.weightKg, 1e-9);
        assertEquals("52/36", racer.chainrings);
        assertEquals("11-30", racer.cassette);
    }

    // --- labels ---------------------------------------------------------------------------

    @Test
    public void cleanTypeRepairsUnknownValues() {
        assertEquals(Bike.TYPE_ROAD, BikeGarage.cleanType(null));
        assertEquals(Bike.TYPE_ROAD, BikeGarage.cleanType("tandem"));
        assertEquals(Bike.TYPE_MTB, BikeGarage.cleanType("mtb"));
        assertEquals("Trainer", BikeGarage.typeLabel(Bike.TYPE_TRAINER));
    }

    @Test
    public void specLineListsKnownFields() {
        Bike b = bike("r", Bike.TYPE_GRAVEL);
        b.weightKg = 8.75;
        b.tyreWidthMm = 40;
        b.chainrings = "40";
        b.cassette = "10-44";
        assertEquals("Gravel · 8,8 kg · 40 mm banden · 40 × 10-44", BikeGarage.specLine(b));
        assertEquals("Racefiets", BikeGarage.specLine(bike("x", Bike.TYPE_ROAD)));
    }
}
