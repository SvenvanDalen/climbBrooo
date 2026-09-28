package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

/**
 * Regression coverage for issue #234's off-road detection: Strava reports gravel/MTB rides as
 * legacy {@code type: "Ride"} — the specific classification only appears in {@code sport_type}.
 * Mirrors {@link StravaStreamsDtoTest}'s raw-JSON parse pattern.
 */
public class StravaActivityDtoTest {

    @Test
    public void parsesSportTypeDistinctFromLegacyType_andItLandsOnStoredRide() throws Exception {
        // Real-shaped Strava activity list item: legacy "type" says "Ride", the true
        // classification is only in "sport_type".
        String json = "{"
                + "\"id\":555,"
                + "\"name\":\"Modderrit\","
                + "\"type\":\"Ride\","
                + "\"sport_type\":\"GravelRide\","
                + "\"start_date\":\"2026-09-20T08:00:00Z\","
                + "\"distance\":30000.0,"
                + "\"moving_time\":3600,"
                + "\"elapsed_time\":3900,"
                + "\"total_elevation_gain\":250.0,"
                + "\"average_speed\":8.3,"
                + "\"max_speed\":15.0,"
                + "\"commute\":false,"
                + "\"start_latlng\":[50.85,5.69],"
                + "\"end_latlng\":[50.86,5.70]"
                + "}";

        StravaActivityDto dto = new ObjectMapper().readValue(json, StravaActivityDto.class);
        assertEquals("Ride", dto.type);
        assertEquals("GravelRide", dto.sportType);

        StoredRide stored = StravaActivitiesRepository.toStoredRide(dto);
        assertEquals("Ride", stored.type);
        assertEquals("GravelRide", stored.sportType);
    }

    @Test
    public void missingSportType_isNull_oldRidesJsonStaysReadable() throws Exception {
        String json = "{\"id\":1,\"type\":\"Ride\",\"start_date\":\"2026-09-20T08:00:00Z\"}";

        StravaActivityDto dto = new ObjectMapper().readValue(json, StravaActivityDto.class);
        assertNull(dto.sportType);

        StoredRide stored = StravaActivitiesRepository.toStoredRide(dto);
        assertNull(stored.sportType);
    }
}
