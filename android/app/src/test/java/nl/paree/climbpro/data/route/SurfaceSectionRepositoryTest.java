package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SurfaceSectionRepositoryTest {

    private Application app;

    /** Writes a route with a 0..5000 m distance axis so routeLength == 5000. */
    private void seedRoute(String routeId) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = routeId;
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01, 51.02};
        route.lons       = new double[]{5.0, 5.01, 5.02};
        route.elevations = new double[]{100, 120, 140};
        route.distances  = new double[]{0, 2500, 5000};

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void addSurfaceSection_persists() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals(1, reloaded.surfaceSections.size());
        StoredSurfaceSection s = reloaded.surfaceSections.get(0);
        assertEquals(1000, s.startDistance);
        assertEquals(2000, s.endDistance);
        assertEquals(SurfaceType.GRAVEL, s.surfaceType);
    }

    @Test
    public void addSurfaceSection_keepsListSortedByStart() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 3000, 4000, SurfaceType.DIRT);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);

        List<StoredSurfaceSection> sections = repo.loadRoute("r1").surfaceSections;
        assertEquals(2, sections.size());
        assertEquals(500, sections.get(0).startDistance);
        assertEquals(3000, sections.get(1).startDistance);
    }

    @Test
    public void addSurfaceSection_rejectsStartNotBeforeEnd() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 2000, 2000, SurfaceType.GRAVEL));
    }

    @Test
    public void addSurfaceSection_rejectsEndBeyondRouteLength() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 1000, 6000, SurfaceType.GRAVEL));
    }

    @Test
    public void addSurfaceSection_clampsIllegalSurfaceToUnknown() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, 99); // out of 0..5 range

        assertEquals(SurfaceType.UNKNOWN,
                repo.loadRoute("r1").surfaceSections.get(0).surfaceType);
    }

    @Test
    public void addSurfaceSection_updatesCatalogSurfaceTypes() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        boolean gravelInCatalog = false;
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (e.routeId.equals("r1") && e.surfaceTypes != null) {
                for (int t : e.surfaceTypes) if (t == SurfaceType.GRAVEL) gravelInCatalog = true;
            }
        }
        assertTrue("GRAVEL must appear in the route's catalog surfaceTypes", gravelInCatalog);
    }

    @Test
    public void deleteSurfaceSection_removesByIndex() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);
        repo.addSurfaceSection("r1", 3000, 4000, SurfaceType.DIRT);

        repo.deleteSurfaceSection("r1", 0); // removes the 500..1000 ASPHALT section

        List<StoredSurfaceSection> sections = repo.loadRoute("r1").surfaceSections;
        assertEquals(1, sections.size());
        assertEquals(3000, sections.get(0).startDistance);
    }

    @Test
    public void deleteSurfaceSection_ignoresOutOfRangeIndex() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);

        repo.deleteSurfaceSection("r1", 7); // no-op, must not throw

        assertEquals(1, repo.loadRoute("r1").surfaceSections.size());
    }

    @Test
    public void saveRoute_preservesSurfaceSectionsAcrossReimport() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        // Simulate a re-import: a brand-new StoredRoute with the same id, no surfaceSections set.
        StoredRoute fresh = new StoredRoute();
        fresh.routeId = "r1";
        fresh.name    = "Test";
        java.util.List<nl.paree.climbpro.domain.route.RoutePoint> points = new java.util.ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.0, 5.0, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.02, 5.02, 140, 5000));

        repo.saveRoute(fresh, points, java.util.Collections.emptyList());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("surface sections must survive re-import",
                1, reloaded.surfaceSections.size());
        assertEquals(SurfaceType.GRAVEL, reloaded.surfaceSections.get(0).surfaceType);
    }
}
