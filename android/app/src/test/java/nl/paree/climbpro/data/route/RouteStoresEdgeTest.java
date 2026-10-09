package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowGeocoder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Corrupt files, failed writes and small helpers of the route-side stores. */
@RunWith(RobolectricTestRunner.class)
public class RouteStoresEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("route_repo", Context.MODE_PRIVATE).edit()
                .putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @After
    public void tearDown() {
        ShadowGeocoder.reset();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }

    private void corrupt(String name) throws IOException {
        Files.write(file(name).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
    }

    /** Turns {@code name} into a non-empty directory so an atomic replace of it must fail. */
    private void block(String name) throws IOException {
        File dir = file(name);
        dir.mkdirs();
        Files.write(new File(dir, "child").toPath(), new byte[]{1});
    }

    // ---- RouteCollectionRepository ----

    @Test
    public void collections_corruptFile_loadsEmpty() throws Exception {
        corrupt("collections.json");
        assertTrue(new RouteCollectionRepository(app).loadAll().isEmpty());
    }

    @Test
    public void collections_onClimbRemoved_dropsAndShiftsOnlyThatRoute() {
        RouteCollectionRepository repo = new RouteCollectionRepository(app);
        RouteCollection c = repo.create("Favorieten");
        repo.addClimb(c.id, "r1", 0);
        repo.addClimb(c.id, "r1", 1);
        repo.addClimb(c.id, "r1", 2);
        repo.addClimb(c.id, "r2", 1);
        RouteCollection empty = repo.create("Leeg");

        repo.onClimbRemoved("r1", 1);

        List<ClimbMembership> climbs = repo.get(c.id).climbs;
        assertEquals(3, climbs.size());
        assertTrue(climbs.contains(new ClimbMembership("r1", 0)));
        assertTrue(climbs.contains(new ClimbMembership("r1", 1))); // was index 2
        assertTrue(climbs.contains(new ClimbMembership("r2", 1)));
        assertTrue(repo.get(empty.id).climbs.isEmpty());
    }

    @Test
    public void collections_failedSave_isSwallowedAndLeavesNoTmp() throws Exception {
        block("collections.json");
        RouteCollectionRepository repo = new RouteCollectionRepository(app);

        repo.create("x"); // must not throw

        assertFalse(file("collections.json.tmp").exists());
    }

    // ---- RouteGhostRepository ----

    @Test
    public void ghosts_corruptFile_loadsEmpty_andLookupsReturnNull() throws Exception {
        corrupt(RouteGhostRepository.FILE);
        RouteGhostRepository repo = new RouteGhostRepository(app);
        assertTrue(repo.loadAll().isEmpty());
        assertNull(repo.find("r1"));
        assertNull(repo.find(null));
        assertNull(repo.wireFor((StoredRoute) null));
    }

    @Test
    public void ghosts_failedWrite_throwsAndLeavesNoTmp() throws Exception {
        block(RouteGhostRepository.FILE);
        RouteGhostRepository repo = new RouteGhostRepository(app);
        StoredRouteGhost g = new StoredRouteGhost("r1", 1L, 0L, 1000, 100,
                new int[]{0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100});
        try {
            repo.offer(Collections.singletonList(g));
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file(RouteGhostRepository.FILE + ".tmp").exists());
    }

    // ---- attempt repositories ----

    private static StoredClimbAttempt attempt(long activityId, String climbId) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.activityId = activityId;
        a.climbId = climbId;
        a.elapsedSec = 300;
        return a;
    }

    @Test
    public void attempts_corruptFile_loadsEmpty() throws Exception {
        corrupt("climb_attempts.json");
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);
        assertTrue(repo.loadAll().isEmpty());
        assertTrue(repo.knownActivityIds().isEmpty());
    }

    @Test
    public void attempts_overwriteAll_replacesContent() throws Exception {
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);
        repo.append(Arrays.asList(attempt(1, "a"), attempt(2, "b")));

        repo.overwriteAll(Collections.singletonList(attempt(3, "c")));

        List<StoredClimbAttempt> all = repo.loadAll();
        assertEquals(1, all.size());
        assertEquals(3L, all.get(0).activityId);
    }

    @Test
    public void attempts_failedWrite_throwsAndLeavesNoTmp() throws Exception {
        block("climb_attempts.json");
        try {
            new ClimbAttemptRepository(app).overwriteAll(Collections.singletonList(attempt(1, "a")));
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file("climb_attempts.json.tmp").exists());
    }

    @Test
    public void incompleteAttempts_corruptFile_loadsEmpty() throws Exception {
        corrupt("incomplete_climb_attempts.json");
        IncompleteClimbAttemptRepository repo = new IncompleteClimbAttemptRepository(app);
        assertTrue(repo.loadAll().isEmpty());
        assertTrue(repo.knownActivityIds().isEmpty());
    }

    @Test
    public void incompleteAttempts_failedWrite_throwsAndLeavesNoTmp() throws Exception {
        block("incomplete_climb_attempts.json");
        StoredIncompleteClimbAttempt p = new StoredIncompleteClimbAttempt();
        p.activityId = 9L;
        try {
            new IncompleteClimbAttemptRepository(app).append(Collections.singletonList(p));
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file("incomplete_climb_attempts.json.tmp").exists());
    }

    // ---- AttemptPhotoStore ----

    private Context contextWith(ContentResolver resolver) {
        Context ctx = mock(Context.class);
        when(ctx.getContentResolver()).thenReturn(resolver);
        when(ctx.getApplicationContext()).thenReturn(app);
        return ctx;
    }

    @Test
    public void photo_savePngCopiesBytesWithPngExtension() throws Exception {
        Uri uri = Uri.parse("content://photos/1");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.getType(uri)).thenReturn("image/png");
        when(resolver.openInputStream(uri))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        String name = AttemptPhotoStore.savePickedPhoto(contextWith(resolver), uri);

        assertTrue(name.endsWith(".png"));
        assertArrayEquals(new byte[]{1, 2, 3},
                Files.readAllBytes(AttemptPhotoStore.fileFor(app, name).toPath()));
    }

    @Test
    public void photo_unknownTypeDefaultsToJpg() throws Exception {
        Uri uri = Uri.parse("content://photos/2");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(uri)).thenReturn(new ByteArrayInputStream(new byte[]{7}));

        assertTrue(AttemptPhotoStore.savePickedPhoto(contextWith(resolver), uri).endsWith(".jpg"));
    }

    @Test(expected = IOException.class)
    public void photo_unopenableSource_throws() throws Exception {
        Uri uri = Uri.parse("content://photos/3");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openInputStream(uri)).thenReturn(null);
        AttemptPhotoStore.savePickedPhoto(contextWith(resolver), uri);
    }

    @Test
    public void photo_deleteIgnoresNullAndEmpty_andRemovesFile() throws Exception {
        File dir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        dir.mkdirs();
        File f = new File(dir, "a.jpg");
        Files.write(f.toPath(), new byte[]{1});

        AttemptPhotoStore.delete(app, null);
        AttemptPhotoStore.delete(app, "");
        assertTrue(f.exists());
        AttemptPhotoStore.delete(app, "a.jpg");
        assertFalse(f.exists());
    }

    @Test
    public void photo_cleanupOrphans_keepsReferencedFilesOnly() throws Exception {
        AttemptPhotoStore.cleanupOrphans(app, Collections.<StoredClimbAttempt>emptyList()); // no dir yet

        File dir = new File(app.getFilesDir(), AttemptPhotoStore.SUBDIR);
        dir.mkdirs();
        Files.write(new File(dir, "keep.jpg").toPath(), new byte[]{1});
        Files.write(new File(dir, "orphan.jpg").toPath(), new byte[]{1});
        StoredClimbAttempt withPhoto = attempt(1, "a");
        withPhoto.photoFileName = "keep.jpg";

        AttemptPhotoStore.cleanupOrphans(app, Arrays.asList(withPhoto, attempt(2, "b")));

        assertTrue(new File(dir, "keep.jpg").exists());
        assertFalse(new File(dir, "orphan.jpg").exists());
    }

    // ---- MyWhooshRouteStore / KnownClimbCatalog ----

    private static List<RoutePoint> points() {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            pts.add(new RoutePoint(45.0 + i * 0.0009, 6.0, i * 6.0, i * 100.0));
        }
        return pts;
    }

    private static Climb climb() {
        return Climb.builder().startDistance(0).endDistance(1000).length(1000)
                .elevationGain(60).avgGradient(0.06).startLat(45.0).startLon(6.0)
                .segments(Collections.singletonList(new Segment(1000, 60, 0.06, 3)))
                .build();
    }

    @Test
    public void myWhoosh_savesRoutesIntoOneSharedCollection() throws Exception {
        RouteRepository routes = new RouteRepository(app, (lat, lon) -> null);
        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        MyWhooshRouteStore store = new MyWhooshRouteStore(routes, collections);

        store.save("mw1", "Hautacam", "h1", true, points(), Collections.singletonList(climb()));
        store.save("mw2", "Tourmalet", "h2", false, points(), Collections.singletonList(climb()));

        assertEquals(1, collections.loadAll().size());
        RouteCollection c = collections.loadAll().get(0);
        assertEquals(MyWhooshRouteStore.COLLECTION, c.name);
        assertEquals(Arrays.asList("mw1", "mw2"), c.routeIds);
        StoredRoute virtual = routes.loadRoute("mw1");
        assertEquals("MyWhoosh – Hautacam", virtual.name);
        assertTrue(virtual.notes.contains("zonder GPS"));
        assertEquals("Geïmporteerd uit MyWhoosh.", routes.loadRoute("mw2").notes);
    }

    @Test
    public void myWhoosh_publicConstructorWorks() throws Exception {
        new MyWhooshRouteStore(app).save("mw1", "X", "h", true, points(),
                Collections.singletonList(climb()));
        assertEquals(1, new RouteCollectionRepository(app).loadAll().size());
    }

    @Test
    public void knownClimbCatalog_skipsUnreadableRoutesAndDedupes() throws Exception {
        RouteRepository routes = new RouteRepository(app, (lat, lon) -> null);
        for (String id : new String[]{"a", "b", "gone"}) {
            StoredRoute r = new StoredRoute();
            r.routeId = id;
            routes.saveRoute(r, points(), Collections.singletonList(climb()));
        }
        new File(new File(app.getFilesDir(), "routes"), "gone.json").delete();

        assertEquals(1, KnownClimbCatalog.load(routes).size());
    }

    // ---- GeocoderClimbNameSuggester ----

    private static Geocoder geocoderOf(GeocoderClimbNameSuggester s) throws Exception {
        java.lang.reflect.Field f = GeocoderClimbNameSuggester.class.getDeclaredField("geocoder");
        f.setAccessible(true);
        return (Geocoder) f.get(s);
    }

    private static Address address(String locality, String subAdmin, String admin) {
        Address a = new Address(Locale.US);
        a.setLocality(locality);
        a.setSubAdminArea(subAdmin);
        a.setAdminArea(admin);
        return a;
    }

    @Test
    public void geocoder_notPresent_returnsNull() {
        ShadowGeocoder.setIsPresent(false);
        assertNull(new GeocoderClimbNameSuggester(app).suggestName(45, 6));
    }

    @Test
    public void geocoder_usesFirstNonBlankPlace() throws Exception {
        ShadowGeocoder.setIsPresent(true);
        GeocoderClimbNameSuggester s = new GeocoderClimbNameSuggester(app);

        shadowOf(geocoderOf(s)).setFromLocation(
                Collections.singletonList(address(" Bourg d'Oisans ", "Isère", "ARA")));
        assertEquals("Klim bij Bourg d'Oisans", s.suggestName(45, 6));

        shadowOf(geocoderOf(s)).setFromLocation(
                Collections.singletonList(address("  ", null, "Auvergne")));
        assertEquals("Klim bij Auvergne", s.suggestName(45, 6));

        shadowOf(geocoderOf(s)).setFromLocation(
                Collections.singletonList(address(null, null, null)));
        assertNull(s.suggestName(45, 6));

        shadowOf(geocoderOf(s)).setFromLocation(Collections.<Address>emptyList());
        assertNull(s.suggestName(45, 6));
    }

    @Test
    public void geocoder_backendError_returnsNull() throws Exception {
        ShadowGeocoder.setIsPresent(true);
        GeocoderClimbNameSuggester s = new GeocoderClimbNameSuggester(app);
        shadowOf(geocoderOf(s)).setErrorMessage("backend down");
        assertNull(s.suggestName(45, 6));
    }
}
