package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class StravaSegmentExploreDtoTest {

    @Test
    public void parsesExploreResponseAndMapsAvgGrade() throws Exception {
        String json = "{\"segments\":[{\"id\":229781,\"resource_state\":2,"
                + "\"name\":\"Hawk Hill\",\"climb_category\":1,\"climb_category_desc\":\"4\","
                + "\"avg_grade\":5.7,\"start_latlng\":[37.8331,-122.4834],"
                + "\"end_latlng\":[37.8280,-122.4981],\"elev_difference\":152.8,"
                + "\"distance\":2684.8,\"points\":\"abc\",\"starred\":false}]}";

        StravaSegmentExploreDto dto = new ObjectMapper().readValue(json, StravaSegmentExploreDto.class);

        assertEquals(1, dto.segments.size());
        StravaSegmentDto seg = dto.segments.get(0).toSegment();
        assertEquals(229781L, seg.id);
        assertEquals("Hawk Hill", seg.name);
        assertEquals(5.7f, seg.averageGrade, 1e-6);
        assertEquals(2684.8f, seg.distance, 1e-3);
        assertEquals(37.8331, seg.startLatlng[0], 1e-9);
        assertEquals(-122.4981, seg.endLatlng[1], 1e-9);
        assertEquals(1, dto.segments.get(0).climbCategory);
    }
}
