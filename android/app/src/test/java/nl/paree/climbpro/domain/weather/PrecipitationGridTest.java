package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class PrecipitationGridTest {

    static String location(String mm) {
        return "{\"latitude\":50.0,\"longitude\":5.0,\"hourly\":{"
                + "\"time\":[\"2026-09-24T10:00\",\"2026-09-24T11:00\",\"2026-09-24T12:00\"],"
                + "\"precipitation\":[" + mm + "]}}";
    }

    /** Multi-location Open-Meteo answer (a JSON array), one comma-separated mm list per location. */
    static String json3(String a, String b, String c) {
        return "[" + location(a) + "," + location(b) + "," + location(c) + "]";
    }

    @Test public void parsesArrayOfLocationsWithNulls() throws IOException {
        PrecipitationGrid g = PrecipitationGrid.parse(
                json3("0.0,0.5,null", "0.0,1.2,null", "0.0,0.0,null"), 3);
        assertEquals(3, g.times.length);
        assertEquals(Instant.parse("2026-09-24T11:00:00Z"), g.times[1]);
        assertEquals(3, g.mm.length);
        assertEquals(0.5, g.mm[0][1], 1e-9);
        assertEquals(1.2, g.mm[1][1], 1e-9);
        assertTrue(Double.isNaN(g.mm[2][2]));
    }

    @Test public void singleLocationIsAPlainObject() throws IOException {
        PrecipitationGrid g = PrecipitationGrid.parse(location("0.1,0.2,0.3"), 1);
        assertEquals(1, g.mm.length);
        assertEquals(0.3, g.mm[0][2], 1e-9);
    }

    @Test(expected = IOException.class)
    public void wrongLocationCountIsAnError() throws IOException {
        PrecipitationGrid.parse(json3("0,0,0", "0,0,0", "0,0,0"), 2);
    }

    @Test(expected = IOException.class)
    public void errorAnswerIsAnError() throws IOException {
        PrecipitationGrid.parse("{\"error\":true,\"reason\":\"bad\"}", 1);
    }

    @Test public void indexAtUsesTheHourContainingTheInstant() throws IOException {
        PrecipitationGrid g = PrecipitationGrid.parse(json3("0,0,0", "0,0,0", "0,0,0"), 3);
        assertEquals(0, g.indexAt(Instant.parse("2026-09-24T10:20:00Z")));
        assertEquals(2, g.indexAt(Instant.parse("2026-09-24T12:59:59Z")));
        assertEquals(-1, g.indexAt(Instant.parse("2026-09-24T13:00:00Z")));
        assertEquals(-1, g.indexAt(Instant.parse("2026-09-24T09:59:00Z")));
    }
}
