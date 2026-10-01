package nl.paree.climbpro.data.offline;

import nl.paree.climbpro.domain.offline.OfflinePackage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #200: offline packages stored per route under offline/. */
public class OfflinePackageStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void savesLoadsAndDeletes() throws Exception {
        File files = tmp.newFolder("files");
        OfflinePackageStore store = new OfflinePackageStore(files);
        OfflinePackage pkg = new OfflinePackage();
        pkg.routeId = "strava_123";
        pkg.createdAtMs = 42;
        pkg.weather.add(new OfflinePackage.WeatherPoint(1000, 51, 5, "{\"hourly\":{}}"));
        OfflinePackage.Poi p = new OfflinePackage.Poi(OfflinePackage.Poi.WATER, "Kraan", 51, 5);
        p.distanceM = 1200;
        pkg.pois.add(p);
        pkg.poiError = null;

        store.save(pkg);
        assertTrue(new File(files, "offline/strava_123.json").exists());

        OfflinePackage back = store.load("strava_123");
        assertEquals(42, back.createdAtMs);
        assertEquals("{\"hourly\":{}}", back.weather.get(0).forecastJson);
        assertEquals("Kraan", back.pois.get(0).name);
        assertEquals(1200, back.pois.get(0).distanceM, 0);

        assertTrue(store.delete("strava_123"));
        assertNull(store.load("strava_123"));
        assertFalse(store.delete("strava_123"));
    }

    @Test
    public void missingOrCorruptReadsAsNone() throws Exception {
        File files = tmp.newFolder("files");
        OfflinePackageStore store = new OfflinePackageStore(files);
        assertNull(store.load("nope"));
        File dir = new File(files, OfflinePackageStore.DIR);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "bad.json"))) {
            out.write("{kapot".getBytes("UTF-8"));
        }
        assertNull(store.load("bad"));
    }

    @Test
    public void unsafeIdsStayInsideTheDirectory() throws Exception {
        File files = tmp.newFolder("files");
        OfflinePackageStore store = new OfflinePackageStore(files);
        OfflinePackage pkg = new OfflinePackage();
        pkg.routeId = "../evil";
        store.save(pkg);
        assertTrue(new File(files, "offline/.._evil.json").exists());
        assertEquals("../evil", store.load("../evil").routeId);
    }
}
