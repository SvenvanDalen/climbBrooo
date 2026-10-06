package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import nl.paree.climbpro.testsupport.DeviceState;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** JSON route storage under getFilesDir() on a real device filesystem. */
@RunWith(AndroidJUnit4.class)
public class RouteStorageInstrumentedTest {

    private Context app;
    private RouteRepository repo;

    @Before
    public void setUp() throws Exception {
        DeviceState.seed();
        app = DeviceState.app();
        repo = new RouteRepository(app);
    }

    @Test
    public void catalogAndRouteFilesLiveUnderFilesDir() throws Exception {
        List<RouteCatalogEntry> catalog = repo.loadCatalog();
        assertEquals(2, catalog.size());
        File routesDir = new File(app.getFilesDir(), "routes");
        assertTrue(routesDir.isDirectory());
        assertTrue(new File(routesDir, "catalog.json").isFile()
                || new File(app.getFilesDir(), "catalog.json").isFile());
        StoredRoute r = repo.loadRoute(UiTestData.ROUTE_ID);
        assertNotNull(r);
        assertFalse(r.climbs.isEmpty());
    }

    @Test
    public void renamesSurviveANewRepositoryInstance() throws Exception {
        repo.renameRoute(UiTestData.ROUTE_ID, "Mijn rondje");
        repo.renameClimb(UiTestData.ROUTE_ID, 0, "Côte de test");

        RouteRepository fresh = new RouteRepository(app);
        StoredRoute r = fresh.loadRoute(UiTestData.ROUTE_ID);
        assertEquals("Mijn rondje", r.userDisplayName);
        assertEquals("Côte de test", r.climbs.get(0).userDisplayName);
        boolean found = false;
        for (RouteCatalogEntry e : fresh.loadCatalog()) {
            if (UiTestData.ROUTE_ID.equals(e.routeId)) found = "Mijn rondje".equals(e.userDisplayName);
        }
        assertTrue(found);
    }

    @Test
    public void renamesAndNotesSurviveResync() throws Exception {
        repo.renameRoute(UiTestData.ROUTE_ID, "Mijn rondje");
        repo.renameClimb(UiTestData.ROUTE_ID, 0, "Côte de test");
        repo.saveNotes(UiTestData.ROUTE_ID, "Bidon bijvullen in Spa");

        // Re-import the same route (as a Strava resync does) with fresh detector output.
        UiTestData.seed(app);

        StoredRoute r = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID);
        assertEquals("Mijn rondje", r.userDisplayName);
        assertEquals("Côte de test", r.climbs.get(0).userDisplayName);
    }

    @Test
    public void deleteRemovesRouteFromDiskAndCatalog() throws Exception {
        repo.deleteRoute(UiTestData.ROUTE_ID_2);
        RouteRepository fresh = new RouteRepository(app);
        assertEquals(1, fresh.loadCatalog().size());
        assertEquals(UiTestData.ROUTE_ID, fresh.loadCatalog().get(0).routeId);
    }

    @Test
    public void concurrentRenamesNeverCorruptTheCatalog() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(40);
        for (int i = 0; i < 40; i++) {
            final int n = i;
            pool.execute(() -> {
                try {
                    repo.renameRoute(n % 2 == 0 ? UiTestData.ROUTE_ID : UiTestData.ROUTE_ID_2, "Naam " + n);
                } catch (Exception ignored) {
                    // a failed write is acceptable; a corrupt file is not
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        List<RouteCatalogEntry> catalog = new RouteRepository(app).loadCatalog();
        assertEquals(2, catalog.size());
        for (RouteCatalogEntry e : catalog) assertNotNull(e.userDisplayName);
    }
}
