package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class StravaStreamsDtoTest {

    @Test
    public void parsesLatlngAndTimeStreams() throws Exception {
        String json =
                "{\"latlng\":{\"data\":[[45.0,6.0],[45.001,6.001]]},"
              + "\"time\":{\"data\":[0,60]}}";

        StravaStreamsDto dto = new ObjectMapper().readValue(json, StravaStreamsDto.class);

        assertEquals(2, dto.latlng.data.size());
        assertEquals(45.0, dto.latlng.data.get(0).get(0), 1e-9);
        assertEquals(6.001, dto.latlng.data.get(1).get(1), 1e-9);
        assertEquals(Integer.valueOf(60), dto.time.data.get(1));
    }
}
