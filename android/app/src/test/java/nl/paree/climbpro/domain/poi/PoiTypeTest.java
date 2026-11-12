package nl.paree.climbpro.domain.poi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PoiTypeTest {

    @Test
    public void drinkingWaterIsRecognisedFromAmenityTag() {
        Map<String, String> tags = new HashMap<>();
        tags.put("amenity", "drinking_water");
        assertEquals(PoiType.DRINKING_WATER, PoiType.fromTags(tags));
        assertEquals(PoiType.DRINKING_WATER, PoiType.fromName("DRINKING_WATER"));
    }

    @Test
    public void unrelatedAmenityIsIgnored() {
        Map<String, String> tags = new HashMap<>();
        tags.put("amenity", "bench");
        assertNull(PoiType.fromTags(tags));
    }

    @Test
    public void everyTypeEndsUpInTheOverpassQuery() {
        double[] lats = {50.0, 50.01, 50.02};
        double[] lons = {5.0, 5.01, 5.02};
        String q = OverpassQueryBuilder.build(lats, lons, 200);
        for (PoiType t : PoiType.values()) {
            assertTrue(t.name(), q.contains(t.osmKey) && q.contains(t.osmValue));
        }
    }
}
