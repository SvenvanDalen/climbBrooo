package nl.paree.climbpro.domain.border;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;

public class CountryPolygonsTest {

    private static final String TWO_SQUARES =
            "# comment line\n"
            + "\n"
            + "AA 0,0 10,0 10,10 0,10\n"
            + "BB 10,0 20,0 20,10 10,10\n"
            // a hole in AA, filled by the small enclave CC
            + "AA 4,4 6,4 6,6 4,6\n"
            + "CC 4,4 6,4 6,6 4,6\n";

    @Test
    public void pointInPolygonPerCountry() throws Exception {
        CountryPolygons p = CountryPolygons.parse(new StringReader(TWO_SQUARES));
        assertEquals("AA", p.countryAt(2, 2));   // lat, lon
        assertEquals("BB", p.countryAt(5, 15));
        assertNull(p.countryAt(50, 50));
        assertEquals(3, p.countryCount());
    }

    @Test
    public void holeIsExcludedAndEnclaveWins() throws Exception {
        CountryPolygons p = CountryPolygons.parse(new StringReader(TWO_SQUARES));
        assertEquals("CC", p.countryAt(5, 5));
    }

    @Test
    public void malformedLinesAreSkipped() throws Exception {
        CountryPolygons p = CountryPolygons.parse(new StringReader(
                "AA 0,0 10,0 10,10 0,10\nBB 1,1 garbage\nCC 1,1 2,2\n"));
        assertEquals(1, p.countryCount());
        assertEquals("AA", p.countryAt(5, 5));
    }

    @Test
    public void bundledAssetResolvesKnownPlaces() throws Exception {
        CountryPolygons p;
        try (InputStream in = new FileInputStream(asset())) {
            p = CountryPolygons.parse(in);
        }
        assertTrue(p.countryCount() >= 40);
        assertEquals("NL", p.countryAt(52.37, 4.90));   // Amsterdam
        assertEquals("NL", p.countryAt(50.85, 5.69));   // Maastricht
        assertEquals("BE", p.countryAt(50.85, 4.35));   // Brussel
        assertEquals("BE", p.countryAt(51.22, 4.40));   // Antwerpen
        assertEquals("DE", p.countryAt(50.78, 6.08));   // Aken
        assertEquals("LU", p.countryAt(49.61, 6.13));   // Luxemburg
        assertEquals("FR", p.countryAt(48.86, 2.35));   // Parijs
        assertEquals("CH", p.countryAt(46.95, 7.45));   // Bern
        assertEquals("AT", p.countryAt(47.27, 11.39));  // Innsbruck
        assertEquals("IT", p.countryAt(45.46, 9.19));   // Milaan
        assertEquals("ES", p.countryAt(40.42, -3.70));  // Madrid
        assertEquals("PT", p.countryAt(38.57, -7.91));  // Evora
        assertEquals("GB", p.countryAt(51.51, -0.13));  // Londen
        assertEquals("IE", p.countryAt(53.35, -6.26));  // Dublin
        assertEquals("AD", p.countryAt(42.51, 1.52));   // Andorra la Vella
        assertEquals("LI", p.countryAt(47.14, 9.52));   // Vaduz
        assertNull(p.countryAt(54.0, 3.0));            // Noordzee
    }

    static File asset() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            File f = new File(dir, "android/app/src/main/assets/" + CountryPolygons.ASSET_PATH);
            if (f.exists()) return f;
            f = new File(dir, "src/main/assets/" + CountryPolygons.ASSET_PATH);
            if (f.exists()) return f;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("border asset not found from "
                + new File("").getAbsolutePath());
    }
}
