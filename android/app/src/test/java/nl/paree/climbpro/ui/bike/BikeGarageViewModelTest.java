package nl.paree.climbpro.ui.bike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostLog;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.bike.BikeGarage;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bike garage screen ViewModel (issue #187): the parts {@code BikeViewModelsTest} leaves out —
 * empty state, field cleaning, edits, gear/indoor routing, gear ordering and failure messages.
 */
@RunWith(RobolectricTestRunner.class)
public class BikeGarageViewModelTest {

    private Application app;
    private BikeGarageViewModel vm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        vm = new BikeGarageViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private static <T> T await(LiveData<T> data, Predicate<T> p) {
        T v = UiTestEnv.awaitValue(data, p);
        assertNotNull("LiveData never reached the expected value", v);
        return v;
    }

    private void breakWrites() {
        assertTrue(new File(app.getFilesDir(), "bike_costs.json.tmp").mkdirs());
    }

    private static Bike named(BikeCostLog log, String name) {
        for (Bike b : log.bikes) if (name.equals(b.name)) return b;
        return null;
    }

    private static StoredRide ride(long id, String type, float meters, String gear) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = "Rit " + id;
        r.type = type;
        r.sportType = type;
        r.startEpochSec = 1_700_000_000L + id * 3600;
        r.distanceM = meters;
        r.gearId = gear;
        return r;
    }

    @Test
    public void load_emptyGarageAndArchive_givesEmptyState() {
        assertNull(vm.state().getValue());
        assertNull(vm.message().getValue());
        vm.load();
        BikeGarageViewModel.State s = await(vm.state(), x -> true);
        assertTrue(s.garage.bikes.isEmpty());
        assertNull(s.garage.activeBikeId);
        assertTrue(s.totals.isEmpty());
        assertTrue(s.gears.isEmpty());
    }

    @Test
    public void saveBike_cleansFieldsAndPersists() {
        vm.saveBike(null, "  ", "unicycle", 80, 200, "  ", " 11-34 ", "  ", false, false);
        BikeGarageViewModel.State s = await(vm.state(), x -> x.garage.bikes.size() == 1);
        Bike b = s.garage.bikes.get(0);
        assertEquals("Fiets", b.name);
        assertEquals(Bike.TYPE_ROAD, b.type);
        assertEquals(0, b.weightKg, 0.0);      // above MAX_WEIGHT_KG counts as unknown
        assertEquals(0, b.tyreWidthMm);         // above MAX_TYRE_WIDTH_MM counts as unknown
        assertNull(b.chainrings);
        assertEquals("11-34", b.cassette);
        assertNull(b.stravaGearId);
        // The first bike becomes active even when "active" was not ticked.
        assertEquals(b.id, s.garage.activeBikeId);
        assertNull(s.garage.indoorBikeId);

        Bike stored = new BikeCostRepository(app).load().bikes.get(0);
        assertEquals(b.id, stored.id);
        assertEquals("11-34", stored.cassette);
    }

    @Test
    public void saveBike_editExisting_updatesInPlaceAndTogglesIndoor() {
        vm.saveBike(null, "Trainer", Bike.TYPE_TRAINER, 12, 0, null, null, null, true, true);
        BikeGarageViewModel.State s = await(vm.state(), x -> x.garage.bikes.size() == 1);
        String id = s.garage.bikes.get(0).id;
        assertEquals(id, s.garage.indoorBikeId);

        vm.saveBike(id, "Smart trainer", Bike.TYPE_TRAINER, 12.5, 0, null, null, null, true,
                false);
        BikeGarageViewModel.State edited = await(vm.state(),
                x -> named(x.garage, "Smart trainer") != null);
        assertEquals(1, edited.garage.bikes.size());
        assertEquals(id, edited.garage.bikes.get(0).id);
        assertEquals(12.5, edited.garage.bikes.get(0).weightKg, 0.0);
        assertNull(edited.garage.indoorBikeId);
    }

    @Test
    public void saveBike_activeFlagMovesActiveBike_gearIdIsUniquePerBike() {
        vm.saveBike(null, "Racefiets", Bike.TYPE_ROAD, 7.5, 28, null, null, "b1", true, false);
        await(vm.state(), x -> x.garage.bikes.size() == 1);
        vm.saveBike(null, "Gravel", Bike.TYPE_GRAVEL, 9.4, 40, null, null, "b1", false, false);
        BikeGarageViewModel.State s = await(vm.state(), x -> x.garage.bikes.size() == 2);
        Bike road = named(s.garage, "Racefiets");
        Bike gravel = named(s.garage, "Gravel");
        assertEquals(road.id, s.garage.activeBikeId); // not ticked: active stays
        assertNull(road.stravaGearId);                // gear id moved to the gravel bike
        assertEquals("b1", gravel.stravaGearId);

        vm.saveBike(gravel.id, "Gravel", Bike.TYPE_GRAVEL, 9.4, 40, null, null, "b1", true,
                false);
        BikeGarageViewModel.State s2 = await(vm.state(),
                x -> gravel.id.equals(x.garage.activeBikeId));
        assertEquals(2, s2.garage.bikes.size());
    }

    @Test
    public void totalsAndGears_followGearIdIndoorBikeAndActiveBike() throws Exception {
        List<StoredRide> rides = new ArrayList<>();
        rides.add(ride(1, "Ride", 40_000, "b-road"));
        rides.add(ride(2, "Ride", 60_000, "b-road"));
        rides.add(ride(3, "GravelRide", 50_000, "b-gravel"));
        rides.add(ride(4, "VirtualRide", 30_000, null));
        rides.add(ride(5, "Ride", 20_000, null));
        rides.add(ride(6, "Ride", 10_000, "b-road"));
        new RideRepository(app).upsertAll(rides);

        vm.saveBike(null, "Racefiets", Bike.TYPE_ROAD, 7.5, 28, null, null, "b-road", true,
                false);
        await(vm.state(), x -> x.garage.bikes.size() == 1);
        vm.saveBike(null, "Gravel", Bike.TYPE_GRAVEL, 9.4, 40, null, null, "b-gravel", false,
                false);
        await(vm.state(), x -> x.garage.bikes.size() == 2);
        vm.saveBike(null, "Trainer", Bike.TYPE_TRAINER, 12, 0, null, null, null, false, true);
        BikeGarageViewModel.State s = await(vm.state(), x -> x.garage.bikes.size() == 3);

        String road = named(s.garage, "Racefiets").id;
        String gravel = named(s.garage, "Gravel").id;
        String trainer = named(s.garage, "Trainer").id;
        // Road: 3 gear-matched rides + the gear-less outdoor ride (active bike).
        assertEquals(4, s.totals.get(road)[0]);
        assertEquals(130_000, s.totals.get(road)[1]);
        assertEquals(1, s.totals.get(gravel)[0]);
        assertEquals(50_000, s.totals.get(gravel)[1]);
        // The indoor ride goes to the indoor bike.
        assertEquals(1, s.totals.get(trainer)[0]);
        assertEquals(30_000, s.totals.get(trainer)[1]);

        // Gear ids in the archive, most-ridden first.
        assertEquals(2, s.gears.size());
        BikeGarage.GearUsage first = s.gears.get(0);
        assertEquals("b-road", first.gearId);
        assertEquals(3, first.rides);
        assertEquals(110_000, first.meters);
        assertEquals("b-gravel", s.gears.get(1).gearId);
        assertEquals(1, s.gears.get(1).rides);
    }

    @Test
    public void deleteBike_unknownOrNullId_isNoOp() {
        vm.saveBike(null, "Racefiets", Bike.TYPE_ROAD, 7.5, 28, null, null, null, true, false);
        await(vm.state(), x -> x.garage.bikes.size() == 1);
        vm.deleteBike("unknown");
        vm.deleteBike(null);
        vm.load();
        assertEquals(1, await(vm.state(), x -> x.garage.bikes.size() == 1).garage.bikes.size());
        assertNull(vm.message().getValue());
    }

    @Test
    public void saveBike_writeFailure_reportsSaveFailure() {
        breakWrites();
        vm.saveBike(null, "Racefiets", Bike.TYPE_ROAD, 7.5, 28, null, null, null, true, false);
        await(vm.message(), m -> m.startsWith("Opslaan mislukt: "));
        assertTrue(await(vm.state(), x -> true).garage.bikes.isEmpty());
    }

    @Test
    public void deleteBike_writeFailure_reportsAndKeepsBike() throws Exception {
        String id = new BikeCostRepository(app).saveGarageBike(null, "Racefiets",
                Bike.TYPE_ROAD, 7.5, 28, null, null, null, true, false);
        breakWrites();
        vm.deleteBike(id);
        await(vm.message(), m -> m.startsWith("Verwijderen mislukt: "));
        assertEquals(id, await(vm.state(), x -> x.garage.bikes.size() == 1).garage.bikes.get(0).id);
    }
}
