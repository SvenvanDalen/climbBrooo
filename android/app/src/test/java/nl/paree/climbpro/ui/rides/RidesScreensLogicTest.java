package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;

import androidx.lifecycle.LiveData;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.recovery.RecoveryCheck;
import nl.paree.climbpro.data.recovery.RecoveryCheckRepository;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.ride.RideCategory;
import nl.paree.climbpro.domain.ride.RideComparison;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.Construct;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Ride archive / compare / story ViewModels and the archive and compare adapters. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RidesScreensLogicTest {

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
        Object[] box = {null};
        data.observeForever(v -> {
            if (v != null && p.test(v)) box[0] = v;
        });
        UiTestEnv.waitFor(() -> box[0] != null);
        return (T) box[0];
    }

    /** Turns one Ardennen attempt into a group summit photo with a real image file. */
    private String addGroupPhoto() throws Exception {
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);
        StoredClimbAttempt a = null;
        for (StoredClimbAttempt x : repo.loadAll()) {
            if (x.activityId == UiTestData.RIDE_OUTDOOR) a = x;
        }
        assertNotNull(a);
        File dir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        dir.mkdirs();
        String name = "group.png";
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        a.photoFileName = name;
        a.companions = "Piet, Klaas";
        assertTrue(repo.update(a));
        return name;
    }

    // --- RideArchiveViewModel ---

    @Test
    public void archive_loadsNewestFirstWithCounts() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.load();
        List<RideArchiveViewModel.Row> rows = await(vm.rows());
        assertEquals(16, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).ride.startEpochSec >= rows.get(i).ride.startEpochSec);
        }
        Map<RideCategory, Integer> counts = await(vm.counts());
        int total = 0;
        for (int n : counts.values()) total += n;
        assertEquals(16, total);
        vm.onCleared();
    }

    @Test
    public void archive_filterShowsOneCategory() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.load();
        List<RideArchiveViewModel.Row> all = await(vm.rows());
        RideCategory some = all.get(0).category;

        vm.setFilter(some);
        List<RideArchiveViewModel.Row> filtered = awaitMatching(vm.rows(), l -> l != all);
        assertFalse(filtered.isEmpty());
        for (RideArchiveViewModel.Row r : filtered) assertEquals(some, r.category);

        vm.setFilter(null);
        assertNotNull(awaitMatching(vm.rows(), l -> l.size() == all.size()));
        vm.onCleared();
    }

    @Test
    public void archive_recoverySaveAndDelete() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.saveRecovery(UiTestData.RIDE_OUTDOOR, 7, 4, 7.5f, "zware benen");
        assertEquals(app.getString(R.string.recovery_check_saved), await(vm.message()));
        List<RideArchiveViewModel.Row> rows = awaitMatching(vm.rows(), l -> recovery(l) != null);
        RecoveryCheck c = recovery(rows);
        assertEquals(7, c.rpe);

        vm.deleteRecovery(UiTestData.RIDE_OUTDOOR);
        assertNotNull(awaitMatching(vm.message(),
                m -> m.equals(app.getString(R.string.recovery_check_deleted))));
        assertNull(new RecoveryCheckRepository(app).byRide().get(UiTestData.RIDE_OUTDOOR));
        vm.onCleared();
    }

    private static RecoveryCheck recovery(List<RideArchiveViewModel.Row> rows) {
        for (RideArchiveViewModel.Row r : rows) {
            if (r.ride.activityId == UiTestData.RIDE_OUTDOOR) return r.recovery;
        }
        return null;
    }

    @Test
    public void archive_refreshWithoutStrava_asksToConnect() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.refreshFromStrava();
        assertEquals("Verbind eerst Strava om ritten op te halen", await(vm.message()));
        vm.onCleared();
    }

    @Test
    public void archive_groupPhotosAttachToTheirRide() throws Exception {
        addGroupPhoto();
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.load();
        List<RideArchiveViewModel.Row> rows = await(vm.rows());
        for (RideArchiveViewModel.Row r : rows) {
            assertEquals(r.ride.activityId == UiTestData.RIDE_OUTDOOR, !r.groupPhotos.isEmpty());
        }
        vm.onCleared();
    }

    @Test
    public void archive_sameRouteCandidates_matchRidesFromSameStart() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.load();
        List<RideArchiveViewModel.Row> rows = await(vm.rows());
        StoredRide base = null;
        for (RideArchiveViewModel.Row r : rows) {
            if (r.ride.activityId == UiTestData.RIDE_OUTDOOR) base = r.ride;
        }
        List<StoredRide> same = vm.sameRouteCandidates(base);
        boolean hasSecond = false;
        for (StoredRide r : same) {
            assertTrue(r.activityId != UiTestData.RIDE_OUTDOOR);
            if (r.activityId == UiTestData.RIDE_OUTDOOR_2) hasSecond = true;
        }
        assertTrue(hasSecond);
        vm.onCleared();
    }

    // --- RideArchiveAdapter ---

    private RideArchiveAdapter.RowVH bind(RideArchiveAdapter adapter, int pos) {
        FrameLayout parent = new FrameLayout(app);
        RideArchiveAdapter.RowVH h = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(h, pos);
        return h;
    }

    @Test
    public void archiveAdapter_bindsRideRecoveryAndClicks() {
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.saveRecovery(UiTestData.RIDE_OUTDOOR, 6, 3, null, null);
        vm.saveRecovery(UiTestData.RIDE_OUTDOOR_2, 8, 2, 6.5f, null);
        List<RideArchiveViewModel.Row> rows = awaitMatching(vm.rows(), l -> {
            int n = 0;
            for (RideArchiveViewModel.Row r : l) if (r.recovery != null) n++;
            return n == 2;
        });
        RideArchiveViewModel.Row[] clicked = {null};
        RideArchiveAdapter adapter = new RideArchiveAdapter(r -> clicked[0] = r);
        adapter.submit(rows);
        assertEquals(rows.size(), adapter.getItemCount());

        for (int i = 0; i < rows.size(); i++) {
            RideArchiveAdapter.RowVH h = bind(adapter, i);
            RideArchiveViewModel.Row r = rows.get(i);
            String recovery = h.recovery.getText().toString();
            if (r.ride.activityId == UiTestData.RIDE_OUTDOOR) {
                assertEquals(app.getString(R.string.recovery_row_logged, 6, 3), recovery);
            } else if (r.ride.activityId == UiTestData.RIDE_OUTDOOR_2) {
                assertEquals(app.getString(R.string.recovery_row_logged_hours, 8, 2, 6.5f), recovery);
            } else {
                assertEquals(app.getString(R.string.recovery_row_empty), recovery);
            }
            assertTrue(h.stats.getText().toString().contains("42.0 km"));
            assertEquals(View.GONE, h.groupRow.getVisibility());
        }
        bind(adapter, 0).itemView.performClick();
        assertEquals(rows.get(0), clicked[0]);
        adapter.submit(null);
        assertEquals(0, adapter.getItemCount());
        adapter.shutdown();
        vm.onCleared();
    }

    @Test
    public void archiveAdapter_unnamedRideWithoutDate() {
        StoredRide r = new StoredRide();
        r.activityId = 9;
        r.name = "";
        RideArchiveViewModel.Row row = Construct.of(RideArchiveViewModel.Row.class, r,
                RideCategory.values()[0], null, null);
        RideArchiveAdapter adapter = new RideArchiveAdapter(null);
        adapter.submit(Arrays.asList(row));
        RideArchiveAdapter.RowVH h = bind(adapter, 0);
        assertEquals("Rit", h.name.getText().toString());
        assertTrue(h.stats.getText().toString().startsWith("onbekende datum"));
        h.itemView.performClick(); // no listener: nothing happens
        adapter.shutdown();
    }

    @Test
    public void archiveAdapter_groupPhotoRowShowsCaptionAndThumbnail() throws Exception {
        addGroupPhoto();
        RideArchiveViewModel vm = new RideArchiveViewModel(app);
        vm.load();
        List<RideArchiveViewModel.Row> rows = await(vm.rows());
        int pos = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).groupPhotos.isEmpty()) pos = i;
        }
        RideArchiveAdapter adapter = new RideArchiveAdapter(null);
        adapter.submit(rows);
        // Attached to a window, so the thumbnail's post() runs on the main looper.
        nl.paree.climbpro.ui.HostActivity host = org.robolectric.Robolectric
                .buildActivity(nl.paree.climbpro.ui.HostActivity.class).setup().get();
        FrameLayout parent = new FrameLayout(host);
        host.setContentView(parent);
        RideArchiveAdapter.RowVH h = adapter.onCreateViewHolder(parent, 0);
        parent.addView(h.itemView);
        adapter.onBindViewHolder(h, pos);

        assertEquals(View.VISIBLE, h.groupRow.getVisibility());
        assertTrue(h.groupCaption.getText().toString().startsWith("👥 "));
        assertEquals("group.png", h.groupPhoto.getTag());
        UiTestEnv.waitFor(() -> h.groupPhoto.getDrawable() != null);
        assertNotNull(h.groupPhoto.getDrawable());

        // Missing file: the thumbnail is hidden.
        new File(new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR), "group.png").delete();
        RideArchiveAdapter.RowVH h2 = bind(adapter, pos);
        assertEquals(View.GONE, h2.groupPhoto.getVisibility());
        adapter.shutdown();
        vm.onCleared();
    }

    // --- RideCompareAdapter ---

    @Test
    public void compareAdapter_bindsKilometreRows() {
        RideComparison.Km full = Construct.of(RideComparison.Km.class, 1, 1000.0, 180, 170,
                140.0, Double.NaN, -10);
        RideComparison.Km partial = Construct.of(RideComparison.Km.class, 2, 400.0, 80, 85,
                150.0, 151.0, 0);
        RideComparison.Km behind = Construct.of(RideComparison.Km.class, 3, 1000.0, 180, 200,
                150.0, 151.0, 25);
        RideCompareAdapter adapter = new RideCompareAdapter();
        adapter.submit(Arrays.asList(full, partial, behind));
        assertEquals(3, adapter.getItemCount());
        FrameLayout parent = new FrameLayout(app);

        RideCompareAdapter.KmVH h = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(h, 0);
        assertEquals("1", h.km.getText().toString());
        assertEquals("3:00 / 2:50", h.time.getText().toString());
        assertEquals("140 / –", h.hr.getText().toString());
        assertEquals("-0:10", h.delta.getText().toString());
        assertEquals(nl.paree.climbpro.ui.climbs.SegmentColorPalette.statusOk(),
                h.delta.getCurrentTextColor());

        adapter.onBindViewHolder(h, 1);
        assertEquals("2 (0.4)", h.km.getText().toString());
        assertEquals("0:00", h.delta.getText().toString());

        adapter.onBindViewHolder(h, 2);
        assertEquals(nl.paree.climbpro.ui.climbs.SegmentColorPalette.statusBad(),
                h.delta.getCurrentTextColor());
        adapter.submit(null);
        assertEquals(0, adapter.getItemCount());
    }

    // --- RideCompareViewModel / RideStoryViewModel ---

    @Test
    public void compare_unknownRide_reportsNotFound() {
        RideCompareViewModel vm = new RideCompareViewModel(app);
        vm.load(UiTestData.RIDE_OUTDOOR, 424242L);
        assertEquals("Rit niet gevonden in het archief", await(vm.error()));
        vm.load(UiTestData.RIDE_OUTDOOR, UiTestData.RIDE_OUTDOOR_2); // only loads once
        vm.onCleared();
    }

    @Test
    public void compare_withoutStrava_asksToConnect() {
        RideCompareViewModel vm = new RideCompareViewModel(app);
        vm.load(UiTestData.RIDE_OUTDOOR, UiTestData.RIDE_OUTDOOR_2);
        assertEquals("Verbind eerst Strava om ritten te vergelijken", await(vm.error()));
        assertNull(vm.result().getValue());
        vm.onCleared();
    }

    @Test
    public void story_unknownRide_reportsNotFound() {
        RideStoryViewModel vm = new RideStoryViewModel(app);
        vm.load(424242L);
        assertEquals("Rit niet gevonden in het archief", await(vm.error()));
        vm.onCleared();
    }

    @Test
    public void story_buildsImageOfflineIncludingGroupPhoto() throws Exception {
        addGroupPhoto();
        RideStoryViewModel vm = new RideStoryViewModel(app);
        vm.load(UiTestData.RIDE_OUTDOOR);
        Bitmap b = await(vm.image());
        assertTrue(b.getWidth() > 0 && b.getHeight() > 0);
        vm.load(UiTestData.RIDE_OUTDOOR);
        vm.onCleared();
    }

    @Test
    public void story_rideWithoutPhoto_stillBuilds() {
        RideStoryViewModel vm = new RideStoryViewModel(app);
        vm.load(UiTestData.RIDE_MYWHOOSH);
        assertNotNull(await(vm.image()));
        vm.onCleared();
    }

    @Test
    public void rideRepositoryHasSeededRides() {
        assertEquals(16, new RideRepository(app).loadAll().size());
    }
}
