package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Bucket-list status (issue #158): persistence, catalog mirroring and resync survival. */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryRideStatusTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded() wiping route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    private static StoredRoute routeShell(String routeId) {
        StoredRoute r = new StoredRoute();
        r.routeId = routeId;
        r.name = "Test";
        return r;
    }

    private static List<RoutePoint> points() {
        List<RoutePoint> pts = new ArrayList<>();
        pts.add(new RoutePoint(51.00, 5.0, 100, 0));
        pts.add(new RoutePoint(51.01, 5.0, 130, 500));
        pts.add(new RoutePoint(51.02, 5.0, 160, 1000));
        pts.add(new RoutePoint(51.03, 5.0, 160, 1500));
        return pts;
    }

    private static List<Climb> climbs() {
        List<Segment> segs = new ArrayList<>();
        segs.add(new Segment(500, 30, 0.06, 3));
        segs.add(new Segment(500, 30, 0.06, 3));
        List<Climb> out = new ArrayList<>();
        out.add(Climb.builder()
                .startDistance(0).endDistance(1000)
                .length(1000).elevationGain(60).avgGradient(0.06)
                .startLat(51.0).startLon(5.0)
                .segments(segs)
                .build());
        return out;
    }

    private static RouteCatalogEntry catalogEntry(RouteRepository repo, String routeId) {
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (e.routeId.equals(routeId)) return e;
        }
        return null;
    }

    @Test
    public void newRouteHasNoStatus() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());

        assertNull(repo.loadRoute("r1").rideStatus);
        assertNull(catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void setRideStatusPersistsToRouteAndCatalog() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());

        repo.setRideStatus("r1", RouteRideStatus.WANT_TO_RIDE);

        RouteRepository fresh = new RouteRepository(app);
        assertEquals(RouteRideStatus.WANT_TO_RIDE, fresh.loadRoute("r1").rideStatus);
        assertEquals(RouteRideStatus.WANT_TO_RIDE, catalogEntry(fresh, "r1").rideStatus);

        repo.setRideStatus("r1", RouteRideStatus.RIDDEN);
        assertEquals(RouteRideStatus.RIDDEN, repo.loadRoute("r1").rideStatus);
        assertEquals(RouteRideStatus.RIDDEN, catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void setRideStatusNullOrUnknownClears() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setRideStatus("r1", RouteRideStatus.RIDDEN);

        repo.setRideStatus("r1", null);
        assertNull(repo.loadRoute("r1").rideStatus);
        assertNull(catalogEntry(repo, "r1").rideStatus);

        repo.setRideStatus("r1", RouteRideStatus.RIDDEN);
        repo.setRideStatus("r1", "BOGUS");
        assertNull(repo.loadRoute("r1").rideStatus);
        assertNull(catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void statusSurvivesReimportInRouteAndCatalog() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setRideStatus("r1", RouteRideStatus.WANT_TO_RIDE);

        // Re-import: fresh shell without rideStatus (as a Strava resync / reprocess supplies).
        repo.saveRoute(routeShell("r1"), points(), climbs());

        assertEquals("status must survive re-import",
                RouteRideStatus.WANT_TO_RIDE, repo.loadRoute("r1").rideStatus);
        assertEquals("catalog rebuild on re-import must keep status",
                RouteRideStatus.WANT_TO_RIDE, catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void explicitStatusOnSaveWinsOverPrevious() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setRideStatus("r1", RouteRideStatus.WANT_TO_RIDE);

        StoredRoute shell = routeShell("r1");
        shell.rideStatus = RouteRideStatus.RIDDEN;
        repo.saveRoute(shell, points(), climbs());

        assertEquals(RouteRideStatus.RIDDEN, repo.loadRoute("r1").rideStatus);
        assertEquals(RouteRideStatus.RIDDEN, catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void statusSurvivesOtherCatalogUpdates() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setRideStatus("r1", RouteRideStatus.RIDDEN);

        repo.renameRoute("r1", "Nieuwe naam");
        repo.saveNotes("r1", "notitie");
        repo.setSegmentSurfaceType("r1", 0, 0, SurfaceType.GRAVEL);
        repo.removeClimb("r1", 0);

        assertEquals(RouteRideStatus.RIDDEN, repo.loadRoute("r1").rideStatus);
        assertEquals(RouteRideStatus.RIDDEN, catalogEntry(repo, "r1").rideStatus);
    }

    @Test
    public void catalogStubRebuildCarriesStatus() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        repo.saveRoute(routeShell("r1"), points(), climbs());
        repo.setRideStatus("r1", RouteRideStatus.WANT_TO_RIDE);

        // Lose the catalog entry; a surface edit recreates a stub entry from the route file.
        assertTrue(new File(app.getFilesDir(), "catalog.json").delete());
        repo.setSegmentSurfaceType("r1", 0, 0, SurfaceType.GRAVEL);

        RouteCatalogEntry stub = catalogEntry(repo, "r1");
        assertNotNull(stub);
        assertEquals(RouteRideStatus.WANT_TO_RIDE, stub.rideStatus);
    }

    @Test
    public void oldRouteFileWithoutFieldLoadsAndCanBeUpdated() throws Exception {
        RouteRepository repo = new RouteRepository(app);
        String json = "{\"routeId\":\"old\",\"name\":\"Oud\",\"notes\":\"n\","
                + "\"importedAtMs\":1,\"lastModifiedMs\":1}";
        try (FileOutputStream out = new FileOutputStream(
                new File(new File(app.getFilesDir(), "routes"), "old.json"))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream out = new FileOutputStream(
                new File(app.getFilesDir(), "catalog.json"))) {
            out.write("[{\"routeId\":\"old\",\"name\":\"Oud\",\"climbCount\":0}]"
                    .getBytes(StandardCharsets.UTF_8));
        }

        assertNull(repo.loadRoute("old").rideStatus);
        assertNull(catalogEntry(repo, "old").rideStatus);

        repo.setRideStatus("old", RouteRideStatus.RIDDEN);
        assertEquals(RouteRideStatus.RIDDEN, repo.loadRoute("old").rideStatus);
        assertEquals(RouteRideStatus.RIDDEN, catalogEntry(repo, "old").rideStatus);
        assertEquals("n", repo.loadRoute("old").notes);
    }
}
