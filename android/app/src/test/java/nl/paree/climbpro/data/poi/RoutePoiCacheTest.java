package nl.paree.climbpro.data.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.domain.poi.PoiType;
import nl.paree.climbpro.domain.poi.RoutePoi;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RoutePoiCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static List<RoutePoi> sample() {
        List<RoutePoi> pois = new ArrayList<>();
        pois.add(new RoutePoi("way/10", "Kasteel Neercanne", PoiType.CASTLE,
                50.81, 5.6995, 1111.9, 35.1));
        pois.add(new RoutePoi("node/2", null, PoiType.VIEWPOINT, 50.84, 5.7005, 4447.8, 35.1));
        return pois;
    }

    @Test
    public void roundTrip() throws IOException {
        RoutePoiCache cache = new RoutePoiCache(tmp.getRoot());
        cache.save("r1", "fp", 1234L, sample());
        RoutePoiCache.Entry e = cache.load("r1", "fp");
        assertEquals(1234L, e.fetchedAtMs);
        assertEquals(2, e.pois.size());
        RoutePoi castle = e.pois.get(0);
        assertEquals("way/10", castle.osmRef);
        assertEquals("Kasteel Neercanne", castle.name);
        assertEquals(PoiType.CASTLE, castle.type);
        assertEquals(50.81, castle.lat, 1e-9);
        assertEquals(1112, castle.distanceAlongM, 1e-9);
        assertEquals(35, castle.offsetM, 1e-9);
        assertNull(e.pois.get(1).name);
        assertEquals(0, new File(tmp.getRoot(), "route_pois")
                .listFiles((d, n) -> n.endsWith(".tmp")).length);
    }

    @Test
    public void changedGeometry_isStale() throws IOException {
        double[] lats = {50.80, 50.81};
        double[] lons = {5.70, 5.70};
        String fp = RoutePoiCache.fingerprint(lats, lons);
        RoutePoiCache cache = new RoutePoiCache(tmp.getRoot());
        cache.save("r1", fp, 1L, sample());
        lats[1] = 50.82;
        String changed = RoutePoiCache.fingerprint(lats, lons);
        assertNotEquals(fp, changed);
        assertNull(cache.load("r1", changed));
    }

    @Test
    public void missingOrCorrupt_isNull() throws IOException {
        RoutePoiCache cache = new RoutePoiCache(tmp.getRoot());
        assertNull(cache.load("nope", "fp"));
        File f = RoutePoiCache.file(tmp.getRoot(), "bad");
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("{not json".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(cache.load("bad", "fp"));
    }

    @Test
    public void delete_removesFile() throws IOException {
        RoutePoiCache cache = new RoutePoiCache(tmp.getRoot());
        cache.save("r1", "fp", 1L, sample());
        RoutePoiCache.delete(tmp.getRoot(), "r1");
        assertFalse(RoutePoiCache.file(tmp.getRoot(), "r1").exists());
        assertNull(cache.load("r1", "fp"));
    }
}
