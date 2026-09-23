package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * Covers the reorder/rollback fix for issue #46's photo write (bug #1 in PR #127's review):
 * the new photo must be written and the JSON record confirmed BEFORE the old photo is
 * deleted, and a failed record update must roll back the new photo rather than leaving it
 * orphaned or the old (still-referenced) photo deleted.
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbDetailAttemptNoteTest {

    private static final String CLIMB_ID = ClimbIdentity.of(45.0, 6.0, 1000);

    @Test
    public void saveAttemptNote_success_deletesOldPhotoAndKeepsNewOne() throws Exception {
        Application app = setUpRoute();
        File oldPhoto = writePhotoFile(app, "old.jpg");
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        attemptRepo.append(Collections.singletonList(
                mkAttempt(CLIMB_ID, 1, 1000, 700, "old.jpg")));

        File pickedSource = writeSourcePhotoFile(app, "picked-source.jpg");
        Uri pickedUri = Uri.fromFile(pickedSource);

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        boolean[] saved = {false};
        String[] unexpectedError = {null};
        vm.saved().observeForever(s -> { if (Boolean.TRUE.equals(s)) saved[0] = true; });
        vm.error().observeForever(e -> unexpectedError[0] = e);

        vm.loadClimb("r1", 0);
        idleUntil(() -> vm.climb().getValue() != null);

        vm.saveAttemptNote("r1", 0, 1, 0, "great ride", pickedUri);
        idleUntil(() -> saved[0]);

        assertEquals(null, unexpectedError[0]);

        // Old photo is gone, new photo persists on disk.
        assertFalse("old photo should be deleted after a successful update", oldPhoto.exists());
        StoredClimbAttempt persisted = attemptRepo.loadAll().get(0);
        assertNotNull(persisted.photoFileName);
        assertTrue("new photo referenced by the record must exist",
                AttemptPhotoStore.fileFor(app, persisted.photoFileName).exists());
        assertEquals("great ride", persisted.note);
    }

    @Test
    public void saveAttemptNote_updateFailure_rollsBackNewPhotoAndKeepsOldOne() throws Exception {
        Application app = setUpRoute();
        File oldPhoto = writePhotoFile(app, "old.jpg");
        ClimbAttemptRepository attemptRepo = new ClimbAttemptRepository(app);
        attemptRepo.append(Collections.singletonList(
                mkAttempt(CLIMB_ID, 1, 1000, 700, "old.jpg")));

        // Force ClimbAttemptRepository#update's atomic write to fail: make the JSON
        // file's path a directory instead of a regular file, so the rename-into-place
        // throws IOException, exactly like a disk-full/write failure would.
        File attemptsFile = new File(app.getFilesDir(), "climb_attempts.json");
        assertTrue(attemptsFile.delete());
        assertTrue(attemptsFile.mkdirs());

        File pickedSource = writeSourcePhotoFile(app, "picked-source.jpg");
        Uri pickedUri = Uri.fromFile(pickedSource);

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        boolean[] gotError = {false};
        vm.error().observeForever(e -> gotError[0] = true);

        vm.loadClimb("r1", 0);
        idleUntil(() -> vm.climb().getValue() != null);

        vm.saveAttemptNote("r1", 0, 1, 0, "great ride", pickedUri);
        idleUntil(() -> gotError[0]);

        // Old photo must survive (still the only valid reference); the picker's source file
        // is untouched (we never delete the source), and whatever file the store wrote for
        // the new photo must have been rolled back — attempt_photos/ should contain only the
        // old photo.
        assertTrue("old photo must remain since the record update failed", oldPhoto.exists());
        File photoDir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        String[] remaining = photoDir.list();
        assertNotNull(remaining);
        assertEquals("orphaned new photo must be rolled back on update failure",
                1, remaining.length);
        assertEquals(oldPhoto.getName(), remaining[0]);
    }

    private static Application setUpRoute() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        new File(app.getFilesDir(), "climb_attempts.json").delete();
        deleteRecursively(new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR));

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.distances = new double[]{0, 1000};
        route.elevations = new double[]{100, 200};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.0; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);
        return app;
    }

    /** Writes an existing attempt photo, i.e. one already tracked under attempt_photos/. */
    private static File writePhotoFile(Application app, String name) throws Exception {
        File dir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        dir.mkdirs();
        return writeBytes(new File(dir, name));
    }

    /**
     * Writes a "just picked from the gallery" source file, deliberately OUTSIDE
     * attempt_photos/ — mirrors a real content:// picker source, which lives elsewhere on
     * disk and must not be confused with the copy AttemptPhotoStore writes into
     * attempt_photos/ when the save flow runs.
     */
    private static File writeSourcePhotoFile(Application app, String name) throws Exception {
        File dir = new File(app.getCacheDir(), "picker_source");
        dir.mkdirs();
        return writeBytes(new File(dir, name));
    }

    private static File writeBytes(File f) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{1, 2, 3});
        }
        return f;
    }

    private static StoredClimbAttempt mkAttempt(String climbId, long activityId, long date,
                                                  int elapsed, String photoFileName) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = activityId;
        a.dateEpochSec = date;
        a.elapsedSec = elapsed;
        a.photoFileName = photoFileName;
        return a;
    }

    private interface Condition { boolean met(); }

    private static void idleUntil(Condition condition) throws InterruptedException {
        // Generous deadline: CI runners can be slow enough under load that the background
        // save/rollback executor hasn't finished within a tighter window, which previously
        // made this test flaky in CI without ever failing locally.
        long deadline = System.currentTimeMillis() + 10000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
