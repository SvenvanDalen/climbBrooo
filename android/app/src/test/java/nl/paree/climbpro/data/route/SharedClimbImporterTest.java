package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.share.ClimbShareCode;
import nl.paree.climbpro.domain.share.ClimbShareExtractor;
import nl.paree.climbpro.domain.share.SharedClimb;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Importing a share code (issue #216) end to end against real repositories. */
@RunWith(RobolectricTestRunner.class)
public class SharedClimbImporterTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    /** 300 m flat, 3 km at 6 %, 300 m flat; a point every 50 m going north. */
    private static SharedClimb climb(String name, double startLat) {
        int n = 73;
        double[] la = new double[n];
        double[] lo = new double[n];
        double[] el = new double[n];
        double ele = 500;
        for (int i = 0; i < n; i++) {
            la[i] = startLat + i * 50 / 111_195.0;
            lo[i] = 6.0;
            if (i > 0 && i * 50 > 300 && i * 50 <= 3300) ele += 3.0;
            el[i] = ele;
        }
        return new SharedClimb(name, la, lo, el);
    }

    private static String code(String collection, SharedClimb... climbs) {
        return ClimbShareCode.encode(new ClimbShareCode.Payload(collection, Arrays.asList(climbs)));
    }

    @Test
    public void singleClimbBecomesARouteWithItsName() throws Exception {
        SharedClimbImporter importer = new SharedClimbImporter(app);
        SharedClimbImporter.Preview preview = importer.preview(code(null, climb("Col du Test", 45)));
        assertEquals(1, preview.count(true));

        SharedClimbImporter.Result r = importer.importPreview(preview);
        assertEquals(1, r.imported);
        assertNull(r.collectionName);
        assertNotNull(r.single);
        assertTrue(r.single.routeId.startsWith(SharedClimbImporter.ROUTE_ID_PREFIX));

        StoredRoute route = new RouteRepository(app).loadRoute(r.single.routeId);
        assertEquals("Col du Test", route.name);
        assertEquals("Col du Test", route.climbs.get(r.single.climbIndex).userDisplayName);
    }

    @Test
    public void knownClimbsAreNotDuplicatedButJoinTheCollection() throws Exception {
        SharedClimbImporter importer = new SharedClimbImporter(app);
        importer.importPreview(importer.preview(code(null, climb("Eerste", 45))));

        SharedClimbImporter.Preview preview = importer.preview(
                code("Alpen", climb("Eerste", 45), climb("Tweede", 46)));
        assertEquals(1, preview.count(true));
        assertEquals(1, preview.count(false));
        SharedClimbImporter.Result r = importer.importPreview(preview);
        assertEquals(1, r.imported);
        assertEquals(1, r.alreadyKnown);
        assertEquals("Alpen", r.collectionName);
        assertEquals(2, new RouteRepository(app).loadCatalog().size());

        RouteCollectionRepository collections = new RouteCollectionRepository(app);
        RouteCollection c = collections.loadAll().get(0);
        assertEquals(2, c.climbs.size());

        // Same code again: nothing new, and the second collection gets its own name.
        SharedClimbImporter.Result again = importer.importPreview(importer.preview(
                code("Alpen", climb("Eerste", 45), climb("Tweede", 46))));
        assertEquals(0, again.imported);
        assertEquals(2, again.alreadyKnown);
        assertEquals("Alpen (2)", again.collectionName);
    }

    @Test
    public void importedClimbCanBeSharedAgain() throws Exception {
        SharedClimbImporter importer = new SharedClimbImporter(app);
        SharedClimbImporter.Result r = importer.importPreview(
                importer.preview(code(null, climb("Doorgeven", 45))));
        StoredRoute route = new RouteRepository(app).loadRoute(r.single.routeId);

        SharedClimb again = ClimbShareExtractor.extract(route, r.single.climbIndex);
        assertNotNull(again);
        assertEquals("Doorgeven", again.name);
        List<SharedClimb> list = Collections.singletonList(again);
        ClimbShareCode.Payload decoded = ClimbShareCode.decode(
                ClimbShareCode.encode(new ClimbShareCode.Payload(null, list)));
        assertEquals(1, decoded.climbs.size());
    }

    @Test
    public void flatGeometryImportsNothing() throws Exception {
        SharedClimb flat = climb("Vlak", 45);
        Arrays.fill(flat.elevations, 10);
        SharedClimbImporter importer = new SharedClimbImporter(app);
        SharedClimbImporter.Preview preview = importer.preview(code("Leeg", flat));
        assertEquals(1, preview.withoutClimb());
        SharedClimbImporter.Result r = importer.importPreview(preview);
        assertEquals(0, r.imported);
        assertNull(r.collectionName);
        assertEquals(0, new RouteRepository(app).loadCatalog().size());
    }
}
