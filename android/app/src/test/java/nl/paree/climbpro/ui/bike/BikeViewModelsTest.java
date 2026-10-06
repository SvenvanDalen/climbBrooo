package nl.paree.climbpro.ui.bike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostRepository;
import nl.paree.climbpro.data.bike.BikePassport;
import nl.paree.climbpro.data.bike.BikePassportPhotoStore;
import nl.paree.climbpro.domain.bike.BikeCostCalculator;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Predicate;

/** Bike passport, bike cost and bike garage ViewModels. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BikeViewModelsTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    private static <T> T await(LiveData<T> data) {
        return awaitMatching(data, v -> true);
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitMatching(LiveData<T> data, Predicate<T> p) {
        return UiTestEnv.awaitValue(data, p);
    }

    private Uri pickedFile(String name) throws Exception {
        File f = new File(app.getCacheDir(), name);
        Files.write(f.toPath(), "beeld".getBytes(StandardCharsets.UTF_8));
        return Uri.fromFile(f);
    }

    private static BikePassport only(List<BikePassport> l) {
        assertEquals(1, l.size());
        return l.get(0);
    }

    // --- BikePassportViewModel ---

    @Test
    public void passport_saveAddPhotosReceiptClearAndDelete() throws Exception {
        BikePassportViewModel vm = new BikePassportViewModel(app);
        BikePassport p = new BikePassport();
        p.name = "  Canyon  ";
        p.frameNumber = "WTU123";
        vm.save(p);
        BikePassport saved = only(awaitMatching(vm.passports(), l -> l.size() == 1));
        assertEquals("Canyon", saved.name);
        String id = saved.id;

        vm.addPhoto(id, pickedFile("a.jpg"), false);
        vm.addPhoto(id, pickedFile("b.jpg"), false);
        vm.addPhoto(id, pickedFile("bon.jpg"), true);
        BikePassport withPhotos = only(awaitMatching(vm.passports(),
                l -> l.size() == 1 && l.get(0).photoFileNames.size() == 2
                        && l.get(0).receiptFileName != null));
        String receipt = withPhotos.receiptFileName;
        for (String f : withPhotos.allFileNames()) {
            assertTrue(f, BikePassportPhotoStore.fileFor(app, f).exists());
        }

        // A new receipt replaces (and deletes) the old one.
        vm.addPhoto(id, pickedFile("bon2.jpg"), true);
        BikePassport newReceipt = only(awaitMatching(vm.passports(),
                l -> l.size() == 1 && !receipt.equals(l.get(0).receiptFileName)));
        assertFalse(BikePassportPhotoStore.fileFor(app, receipt).exists());

        String photo = newReceipt.photoFileNames.get(0);
        vm.clearPhotos(id);
        BikePassport cleared = only(awaitMatching(vm.passports(),
                l -> l.size() == 1 && l.get(0).photoFileNames.isEmpty()));
        assertNotNull(cleared.receiptFileName);
        assertFalse(BikePassportPhotoStore.fileFor(app, photo).exists());

        String lastReceipt = cleared.receiptFileName;
        vm.delete(id);
        assertNotNull(awaitMatching(vm.passports(), List::isEmpty));
        assertFalse(BikePassportPhotoStore.fileFor(app, lastReceipt).exists());
        vm.onCleared();
    }

    @Test
    public void passport_withoutName_reportsSaveFailure() {
        BikePassportViewModel vm = new BikePassportViewModel(app);
        vm.save(new BikePassport());
        assertEquals("Opslaan mislukt", await(vm.messages()));
        vm.onCleared();
    }

    @Test
    public void passport_photoForUnknownOrUnreadable_isHandled() throws Exception {
        BikePassportViewModel vm = new BikePassportViewModel(app);
        vm.addPhoto("nope", pickedFile("x.jpg"), false);
        vm.clearPhotos("nope");
        BikePassport p = new BikePassport();
        p.name = "Gravel";
        vm.save(p);
        String id = only(awaitMatching(vm.passports(), l -> l.size() == 1)).id;
        vm.addPhoto(id, Uri.fromFile(new File(app.getCacheDir(), "missing.jpg")), false);
        assertEquals("Foto toevoegen mislukt", await(vm.messages()));
        vm.load();
        assertTrue(await(vm.passports()).get(0).photoFileNames.isEmpty());
        vm.onCleared();
    }

    @Test
    public void passport_constructorRemovesOrphanPhotos() throws Exception {
        File dir = BikePassportPhotoStore.fileFor(app, "orphan.jpg").getParentFile();
        dir.mkdirs();
        File orphan = new File(dir, "orphan.jpg");
        Files.write(orphan.toPath(), new byte[]{1});
        BikePassportViewModel vm = new BikePassportViewModel(app);
        vm.load();
        await(vm.passports());
        assertFalse(orphan.exists());
        vm.onCleared();
    }

    // --- BikeCostViewModel ---

    private static BikeCostCalculator.Summary named(List<BikeCostCalculator.Summary> l,
                                                    String name) {
        for (BikeCostCalculator.Summary s : l) if (name.equals(s.bike.name)) return s;
        return null;
    }

    @Test
    public void cost_bikeAndCostsAreSummarised() {
        BikeCostViewModel vm = new BikeCostViewModel(app);
        long since = System.currentTimeMillis() / 1000L - 400L * 86_400L;
        vm.saveBike(null, "Racer", since, true, false, 1500, false);
        String bikeId = named(awaitMatching(vm.summaries(), l -> named(l, "Racer") != null),
                "Racer").bike.id;

        vm.addCost(bikeId, "purchase", "Aankoop", 250_000);
        vm.addCost(bikeId, "parts", "Ketting", 4_500);
        BikeCostCalculator.Summary sum = named(awaitMatching(vm.summaries(),
                l -> named(l, "Racer").bike.costs.size() == 2), "Racer");
        assertEquals(254_500, sum.totalCents);
        assertTrue(sum.totalMeters >= 1_500_000);

        String costId = sum.bike.costs.get(1).id;
        vm.deleteCost(bikeId, costId);
        assertNotNull(awaitMatching(vm.summaries(),
                l -> named(l, "Racer").bike.costs.size() == 1));

        vm.saveBike(bikeId, "Racer oud", since, false, true, 0, true);
        Bike retired = named(awaitMatching(vm.summaries(), l -> named(l, "Racer oud") != null),
                "Racer oud").bike;
        assertTrue(retired.retiredEpochSec > 0);

        vm.deleteBike(bikeId);
        assertNotNull(awaitMatching(vm.summaries(), l -> named(l, "Racer oud") == null));
        vm.onCleared();
    }

    @Test
    public void cost_invalidCost_isNotSaved() {
        BikeCostViewModel vm = new BikeCostViewModel(app);
        vm.addCost("unknown", "parts", "x", 100);
        assertEquals("Kosten niet opgeslagen", await(vm.message()));
        vm.load();
        assertNotNull(await(vm.summaries()));
        vm.onCleared();
    }

    // --- BikeGarageViewModel ---

    @Test
    public void garage_saveAndDeleteBike_withArchiveTotals() {
        BikeGarageViewModel vm = new BikeGarageViewModel(app);
        vm.load();
        BikeGarageViewModel.State empty = await(vm.state());
        assertFalse(empty.gears.isEmpty()); // seeded rides carry gear b123

        int before = empty.garage.bikes.size();
        vm.saveBike(null, "Tarmac", Bike.TYPE_ROAD, 7.4, 28, "50/34", "11-30", "b123", true,
                false);
        BikeGarageViewModel.State s = awaitMatching(vm.state(),
                x -> x.garage.bikes.size() == before + 1);
        Bike b = null;
        for (Bike x : s.garage.bikes) if ("Tarmac".equals(x.name)) b = x;
        assertEquals(b.id, s.garage.activeBikeId);
        long[] totals = s.totals.get(b.id);
        assertNotNull(totals);
        assertEquals(16, totals[0]);

        String id = b.id;
        vm.deleteBike(id);
        assertNotNull(awaitMatching(vm.state(), x -> x.garage.bikes.size() == before));
        assertFalse(id.equals(new BikeCostRepository(app).load().activeBikeId));
        vm.onCleared();
    }
}
