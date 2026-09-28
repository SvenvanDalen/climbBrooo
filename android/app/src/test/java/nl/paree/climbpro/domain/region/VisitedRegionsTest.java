package nl.paree.climbpro.domain.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VisitedRegionsTest {

    private static VisitedRegions.Visit v(String cc, String country, String prov, double lat, double lon) {
        return new VisitedRegions.Visit(cc, country, prov, lat, lon);
    }

    @Test public void groupsByCountryThenProvinceMostVisitedFirst() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("BE", "België", "Luik", 50.5, 5.8),
                v("NL", "Nederland", "Limburg", 50.8, 5.8),
                v("NL", "Nederland", "Limburg", 50.9, 6.0),
                v("NL", "Nederland", "Gelderland", 52.0, 5.9)));

        assertEquals(2, out.size());
        assertEquals("Nederland", out.get(0).name);
        assertEquals(3, out.get(0).visits);
        assertEquals("Limburg", out.get(0).provinces.get(0).name);
        assertEquals(2, out.get(0).provinces.get(0).visits);
        assertEquals(50.85, out.get(0).provinces.get(0).lat, 1e-9);
        assertEquals(5.9, out.get(0).provinces.get(0).lon, 1e-9);
        assertEquals("Gelderland", out.get(0).provinces.get(1).name);
        assertEquals("België", out.get(1).name);
    }

    @Test public void tiesAreAlphabetical() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("FR", "Frankrijk", "Vaucluse", 44.1, 5.2),
                v("BE", "België", "Luik", 50.5, 5.8)));
        assertEquals("België", out.get(0).name);
    }

    @Test public void missingProvinceIsUnknownRegion() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("NL", "Nederland", null, 53.0, 4.0), v("NL", "Nederland", " ", 53.1, 4.1)));
        assertEquals(1, out.get(0).provinces.size());
        assertEquals(VisitedRegions.UNKNOWN_PROVINCE, out.get(0).provinces.get(0).name);
        assertEquals(2, out.get(0).provinces.get(0).visits);
    }

    @Test public void emptyInputGivesEmptyOutput() {
        assertTrue(VisitedRegions.aggregate(Collections.emptyList()).isEmpty());
    }
}
