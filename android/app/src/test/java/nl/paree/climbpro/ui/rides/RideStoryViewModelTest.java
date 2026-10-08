package nl.paree.climbpro.ui.rides;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Offline paths of {@link RideStoryViewModel}: the story is built without the Strava track
 * (Strava auth can't be made authorised under Robolectric), with every photo edge case.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RideStoryViewModelTest {

    private static final long RIDE = 77L;

    private Application app;
    private RideStoryViewModel vm;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        vm = new RideStoryViewModel(app);
    }

    @After
    public void tearDown() {
        vm.onCleared();
    }

    private void seedRide(String name) throws Exception {
        StoredRide r = new StoredRide();
        r.activityId = RIDE;
        r.name = name;
        r.type = "Ride";
        r.startEpochSec = 1_700_000_000L;
        r.distanceM = 55_000;
        r.movingTimeSec = 7_200;
        r.elevationGainM = 800;
        new RideRepository(app).upsertAll(Collections.singletonList(r));
    }

    private void seedAttemptWithPhoto(String photo) throws Exception {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "climb-x";
        a.activityId = RIDE;
        a.dateEpochSec = 1_700_000_000L;
        a.elapsedSec = 720;
        a.photoFileName = photo;
        a.avgTempC = 18.0;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));
    }

    private File photoFile(String name) {
        File dir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        dir.mkdirs();
        return new File(dir, name);
    }

    private Bitmap awaitImage() {
        Bitmap b = UiTestEnv.awaitValue(vm.image(), x -> true);
        assertNotNull("story image not built", b);
        assertTrue(b.getWidth() > 0 && b.getHeight() > 0);
        assertNull(vm.error().getValue());
        return b;
    }

    @Test
    public void initialState_isEmpty() {
        assertNull(vm.image().getValue());
        assertNull(vm.error().getValue());
    }

    @Test
    public void emptyArchive_reportsNotFound() {
        vm.load(RIDE);
        assertEquals("Rit niet gevonden in het archief",
                UiTestEnv.awaitValue(vm.error(), e -> true));
        assertNull(vm.image().getValue());
    }

    @Test
    public void rideWithoutAttempts_buildsOffline() throws Exception {
        seedRide("Zondagrit");
        vm.load(RIDE);
        awaitImage();
    }

    @Test
    public void unnamedRide_stillBuilds() throws Exception {
        seedRide("   ");
        vm.load(RIDE);
        awaitImage();
    }

    @Test
    public void photoReferencedButFileMissing_buildsWithoutPhoto() throws Exception {
        seedRide("Rit met verdwenen foto");
        seedAttemptWithPhoto("missing.jpg");
        vm.load(RIDE);
        awaitImage();
    }

    @Test
    public void corruptPhotoFile_buildsWithoutPhoto() throws Exception {
        seedRide("Rit met kapotte foto");
        try (FileOutputStream out = new FileOutputStream(photoFile("broken.jpg"))) {
            out.write("not an image".getBytes(StandardCharsets.UTF_8));
        }
        seedAttemptWithPhoto("broken.jpg");
        vm.load(RIDE);
        awaitImage();
    }

    @Test
    public void largePhoto_isDownsampledAndBuilds() throws Exception {
        seedRide("Rit met grote foto");
        try (FileOutputStream out = new FileOutputStream(photoFile("big.png"))) {
            Bitmap.createBitmap(2600, 1400, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        seedAttemptWithPhoto("big.png");
        vm.load(RIDE);
        awaitImage();
    }

    @Test
    public void secondLoad_isIgnored() throws Exception {
        vm.load(RIDE); // archive empty -> not found
        assertNotNull(UiTestEnv.awaitValue(vm.error(), e -> true));
        seedRide("Later toegevoegd");
        vm.load(RIDE); // ignored: builds once per screen
        UiTestEnv.waitFor(() -> vm.image().getValue() != null, 500);
        assertNull(vm.image().getValue());
    }
}
