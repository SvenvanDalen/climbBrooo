package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.ride.RideTrack;

import org.junit.Test;

/** Issue #193: the ride story's track and temperature from the Strava streams. */
public class RideTrackConversionTest {

    private static StravaStreamsDto parse(String json) throws Exception {
        return new ObjectMapper().readValue(json, StravaStreamsDto.class);
    }

    @Test
    public void skipsBrokenSamplesAndAveragesTemperature() throws Exception {
        RideTrack t = StravaActivitiesRepository.toRideTrack(parse("{"
                + "\"latlng\":{\"data\":[[50.1,5.1],null,[50.2],[50.3,5.3]]},"
                + "\"temp\":{\"data\":[10,null,14,null]}}"));
        assertArrayEquals(new double[]{50.1, 50.3}, t.lat, 1e-9);
        assertArrayEquals(new double[]{5.1, 5.3}, t.lon, 1e-9);
        assertEquals(12.0, t.avgTempC, 1e-9);
    }

    @Test
    public void noTrack_orNoTemperature() throws Exception {
        assertNull(StravaActivitiesRepository.toRideTrack(parse("{\"latlng\":{\"data\":[[1,1]]}}")));
        assertNull(StravaActivitiesRepository.toRideTrack(null));
        RideTrack t = StravaActivitiesRepository.toRideTrack(
                parse("{\"latlng\":{\"data\":[[1,1],[2,2]]}}"));
        assertNull(t.avgTempC);
    }
}
