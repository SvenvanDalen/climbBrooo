package nl.paree.climbpro.domain.border;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class CountryInfoTest {

    @Test
    public void knownCountries() {
        CountryInfo be = CountryInfo.of("BE");
        assertEquals("België", be.nameNl);
        assertEquals("Nederlands/Frans/Duits", be.languages);
        assertEquals("112", be.emergency);
        assertEquals("Verenigd Koninkrijk", CountryInfo.of("GB").nameNl);
        assertTrue(CountryInfo.of("GB").emergency.contains("999"));
        assertEquals("Zwitserland", CountryInfo.of("ch").nameNl);
    }

    @Test
    public void unknownCodeFallsBackTo112() {
        CountryInfo x = CountryInfo.of("ZZ");
        assertEquals("ZZ", x.nameNl);
        assertEquals("112", x.emergency);
        assertEquals("", x.languages);
        assertEquals("?", CountryInfo.of(null).nameNl);
    }

    @Test
    public void everyCountryInTheAssetHasATableEntry() throws Exception {
        Set<String> codes = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(CountryPolygonsTest.asset()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                codes.add(line.substring(0, line.indexOf(' ')));
            }
        }
        for (String code : codes) {
            assertTrue("missing table entry for " + code, CountryInfo.isKnown(code));
        }
    }

    @Test
    public void formatsCrossingLineInDutch() {
        BorderCrossing c = new BorderCrossing(84_321, 50.7, 5.6, "NL", "BE");
        assertEquals("km 84,3 → België · Nederlands/Frans/Duits · 112",
                CountryInfo.formatCrossing(c));
        String start = CountryInfo.formatStart("NL");
        assertNotNull(start);
        assertEquals("Start in Nederland · Nederlands · 112", start);
    }
}
