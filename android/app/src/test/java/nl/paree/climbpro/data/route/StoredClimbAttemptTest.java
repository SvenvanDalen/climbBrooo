package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/**
 * Round-trip (de)serialization of {@link StoredClimbAttempt}, including the note/photo fields
 * added for issue #46. {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the class means
 * old stored attempts (written before these fields existed) must still deserialize fine —
 * covered below by decoding JSON that omits them entirely.
 */
public class StoredClimbAttemptTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void roundTrips_withNoteAndPhoto() throws Exception {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "k1";
        a.activityId = 100L;
        a.dateEpochSec = 1_700_000_000L;
        a.elapsedSec = 600;
        a.passIndex = 0;
        a.segSplitSec = new int[]{10, 20, 30};
        a.note = "Vandaag pijn in de benen maar mooi weer";
        a.photoFileName = "abc-123.jpg";

        byte[] json = mapper.writeValueAsBytes(a);
        StoredClimbAttempt back = mapper.readValue(json, StoredClimbAttempt.class);

        assertEquals(a.climbId, back.climbId);
        assertEquals(a.activityId, back.activityId);
        assertEquals(a.dateEpochSec, back.dateEpochSec);
        assertEquals(a.elapsedSec, back.elapsedSec);
        assertEquals(a.passIndex, back.passIndex);
        assertArrayEquals(a.segSplitSec, back.segSplitSec);
        assertEquals(a.note, back.note);
        assertEquals(a.photoFileName, back.photoFileName);
    }

    @Test
    public void note_and_photo_defaultToNull() throws Exception {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "k1";
        a.activityId = 100L;

        StoredClimbAttempt back =
                mapper.readValue(mapper.writeValueAsBytes(a), StoredClimbAttempt.class);

        assertNull(back.note);
        assertNull(back.photoFileName);
    }

    @Test
    public void deserializes_legacyJson_missingNoteAndPhotoFields() throws Exception {
        String legacyJson = "{\"climbId\":\"k1\",\"activityId\":100,"
                + "\"dateEpochSec\":1700000000,\"elapsedSec\":600,\"passIndex\":0}";

        StoredClimbAttempt back = mapper.readValue(legacyJson, StoredClimbAttempt.class);

        assertEquals("k1", back.climbId);
        assertNull(back.note);
        assertNull(back.photoFileName);
    }

    @Test
    public void avgTempC_roundTrips_andLegacyJsonLoadsAsNull() throws Exception {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = "k1";
        a.avgTempC = 31.5;
        StoredClimbAttempt back =
                mapper.readValue(mapper.writeValueAsBytes(a), StoredClimbAttempt.class);
        assertEquals(31.5, back.avgTempC, 1e-9);

        String legacyJson = "{\"climbId\":\"k1\",\"activityId\":100,\"elapsedSec\":600}";
        assertNull(mapper.readValue(legacyJson, StoredClimbAttempt.class).avgTempC);
    }
}
