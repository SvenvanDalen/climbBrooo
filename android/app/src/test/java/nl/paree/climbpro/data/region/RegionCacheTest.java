package nl.paree.climbpro.data.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class RegionCacheTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static RegionCache.Region region(String cc, String country, String prov) {
        RegionCache.Region r = new RegionCache.Region();
        r.countryCode = cc; r.country = country; r.province = prov;
        return r;
    }

    @Test public void keyIsAboutOneKilometreAndSignAware() {
        assertEquals("5085:569", RegionCache.key(50.8512, 5.6904));
        assertEquals(RegionCache.key(50.8512, 5.6904), RegionCache.key(50.8488, 5.6949));
        assertEquals("-3386:-626", RegionCache.key(-33.861, -6.2649));
        assertNotEquals(RegionCache.key(0.006, 0.006), RegionCache.key(-0.006, -0.006));
    }

    @Test public void roundTripsThroughTheFile() throws Exception {
        File f = new File(tmp.getRoot(), "region_cache.json");
        RegionCache cache = new RegionCache(f);
        cache.put(50.85, 5.69, region("NL", "Nederland", "Limburg"));
        cache.save();

        RegionCache.Region r = new RegionCache(f).get(50.8512, 5.6904);
        assertEquals("NL", r.countryCode);
        assertEquals("Limburg", r.province);
        assertNull(new RegionCache(f).get(52.0, 4.0));
    }

    @Test public void corruptOrMissingFileStartsEmpty() throws Exception {
        File missing = new File(tmp.getRoot(), "nope.json");
        assertNull(new RegionCache(missing).get(50.85, 5.69));
        File corrupt = tmp.newFile("bad.json");
        Files.write(corrupt.toPath(), "{niet json".getBytes(StandardCharsets.UTF_8));
        assertNull(new RegionCache(corrupt).get(50.85, 5.69));
    }
}
