package nl.paree.climbpro.domain.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OverpassResponseParserTest {

    private static PoiCandidate byRef(List<PoiCandidate> list, String ref) {
        for (PoiCandidate c : list) if (c.osmRef.equals(ref)) return c;
        return null;
    }

    @Test
    public void parsesFixture_keepsNamedAndUnnamedViewpoints_skipsNoise() throws IOException {
        List<PoiCandidate> list = OverpassResponseParser.parse(PoiFixtures.overpassResponse());
        // node/3 unnamed memorial, node/8 bench, way/9 without coordinates are dropped.
        assertEquals(7, list.size());
        assertNull(byRef(list, "node/3"));
        assertNull(byRef(list, "node/8"));
        assertNull(byRef(list, "way/9"));

        PoiCandidate unnamedView = byRef(list, "node/2");
        assertEquals(PoiType.VIEWPOINT, unnamedView.type);
        assertNull(unnamedView.name);

        PoiCandidate view = byRef(list, "node/1");
        assertEquals("Uitzicht Sint-Pietersberg", view.name);
        assertEquals(50.82, view.lat, 1e-9);
        assertEquals(5.701, view.lon, 1e-9);
    }

    @Test
    public void waysAndRelationsUseCenter_andHistoricBeatsGenericAttraction() throws IOException {
        List<PoiCandidate> list = OverpassResponseParser.parse(PoiFixtures.overpassResponse());
        PoiCandidate castle = byRef(list, "way/10");
        assertEquals(PoiType.CASTLE, castle.type);
        assertEquals(50.81, castle.lat, 1e-9);
        assertEquals(5.6995, castle.lon, 1e-9);

        PoiCandidate ruins = byRef(list, "relation/7");
        assertEquals(PoiType.RUINS, ruins.type);
        assertEquals("Ruïne Lichtenberg", ruins.name);
    }

    @Test
    public void emptyOrMissingElements_givesEmptyList() throws IOException {
        assertTrue(OverpassResponseParser.parse("{\"elements\":[]}").isEmpty());
        assertTrue(OverpassResponseParser.parse("{\"remark\":\"runtime error\"}").isEmpty());
    }

    @Test(expected = IOException.class)
    public void malformedJson_throws() throws IOException {
        OverpassResponseParser.parse("<html>Too many requests</html>");
    }

    @Test
    public void fallsBackToLocalisedName() throws IOException {
        List<PoiCandidate> list = OverpassResponseParser.parse("{\"elements\":[{\"type\":\"node\","
                + "\"id\":1,\"lat\":1,\"lon\":2,\"tags\":{\"historic\":\"monument\","
                + "\"name:nl\":\"Gedenknaald\"}}]}");
        assertEquals("Gedenknaald", list.get(0).name);
    }

    @Test
    public void poiType_fromTags() {
        Map<String, String> tags = new HashMap<>();
        assertNull(PoiType.fromTags(tags));
        tags.put("tourism", "attraction");
        assertEquals(PoiType.ATTRACTION, PoiType.fromTags(tags));
        tags.put("historic", "memorial");
        assertEquals(PoiType.MEMORIAL, PoiType.fromTags(tags));
        assertNull(PoiType.fromName("NOPE"));
        assertEquals(PoiType.CASTLE, PoiType.fromName("CASTLE"));
    }
}
