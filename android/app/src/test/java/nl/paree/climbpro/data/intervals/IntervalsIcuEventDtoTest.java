package nl.paree.climbpro.data.intervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.time.LocalDate;

public class IntervalsIcuEventDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void workout_serializesToIntervalsEventShape() throws Exception {
        IntervalsIcuEventDto dto = IntervalsIcuEventDto.workout("Col du Test",
                "PR 12:34", "<workout_file/>", "col_du_test.zwo",
                LocalDate.of(2026, 11, 3), true);

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));

        assertEquals("WORKOUT", json.get("category").asText());
        assertEquals("2026-11-03T00:00:00", json.get("start_date_local").asText());
        assertEquals("VirtualRide", json.get("type").asText());
        assertEquals("Col du Test", json.get("name").asText());
        assertEquals("PR 12:34", json.get("description").asText());
        assertEquals("<workout_file/>", json.get("file_contents").asText());
        assertEquals("col_du_test.zwo", json.get("filename").asText());
        assertEquals(7, json.size());
    }

    @Test
    public void workout_outdoorIsRide() throws Exception {
        IntervalsIcuEventDto dto = IntervalsIcuEventDto.workout("X", null, "<w/>", "x.zwo",
                LocalDate.of(2026, 1, 1), false);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));
        assertEquals("Ride", json.get("type").asText());
        assertFalse("null description is omitted", json.has("description"));
    }

    @Test
    public void athleteResponse_ignoresUnknownFields() throws Exception {
        IntervalsIcuAthleteDto a = MAPPER.readValue(
                "{\"id\":\"i42\",\"name\":\"Sven\",\"sportSettings\":[],\"weight\":70}",
                IntervalsIcuAthleteDto.class);
        assertEquals("i42", a.id);
        assertEquals("Sven", a.name);
    }

    @Test
    public void eventResponse_readsId() throws Exception {
        IntervalsIcuEventDto e = MAPPER.readValue(
                "{\"id\":987,\"category\":\"WORKOUT\",\"icu_training_load\":55}",
                IntervalsIcuEventDto.class);
        assertEquals(Long.valueOf(987), e.id);
    }
}
