package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void parsesTempStream_integerDegrees() throws Exception {
        String json =
                "{\"latlng\":{\"data\":[[45.0,6.0],[45.001,6.001]]},"
              + "\"time\":{\"data\":[0,60]},"
              + "\"temp\":{\"data\":[31,-2]}}";

        StravaStreamsDto dto = new ObjectMapper().readValue(json, StravaStreamsDto.class);

        assertEquals(31.0, dto.temp.data.get(0), 1e-9);
        assertEquals(-2.0, dto.temp.data.get(1), 1e-9);
    }

    @Test
    public void missingTempStream_isNull() throws Exception {
        String json = "{\"latlng\":{\"data\":[[45.0,6.0]]},\"time\":{\"data\":[0]}}";
        assertNull(new ObjectMapper().readValue(json, StravaStreamsDto.class).temp);
    }
}
