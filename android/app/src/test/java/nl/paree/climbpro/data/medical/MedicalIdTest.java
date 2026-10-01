package nl.paree.climbpro.data.medical;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/** Issue #230: medical ID watch message, lock-screen text and JSON round trip. */
public class MedicalIdTest {

    private static MedicalId full() {
        MedicalId id = new MedicalId();
        id.name = "Sven";
        id.bloodType = " A+ ";
        id.allergies = "Penicilline,   wespen";
        id.medication = "Geen";
        id.contactName = "Anna";
        id.contactPhone = "+31 6 12345678";
        id.notes = "Donor";
        return id;
    }

    @Test
    public void watchMessageHasTypeAndTrimmedShortKeys() {
        Map<String, Object> m = full().toWatchMessage();
        assertEquals("MEDICAL_ID", m.get("type"));
        assertEquals("Sven", m.get("nm"));
        assertEquals("A+", m.get("bt"));
        assertEquals("Penicilline, wespen", m.get("al"));
        assertEquals("Geen", m.get("md"));
        assertEquals("Anna", m.get("ec"));
        assertEquals("+31 6 12345678", m.get("ep"));
        assertEquals("Donor", m.get("nt"));
    }

    @Test
    public void watchMessageCapsLongFields() {
        MedicalId id = new MedicalId();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        id.allergies = sb.toString();
        id.notes = sb.toString();
        Map<String, Object> m = id.toWatchMessage();
        assertEquals(MedicalId.MAX_TEXT, ((String) m.get("al")).length());
        assertEquals(MedicalId.MAX_NOTES, ((String) m.get("nt")).length());
    }

    @Test
    public void emptyIdSendsOnlyTheType() {
        MedicalId id = new MedicalId();
        id.allergies = "   ";
        assertTrue(id.isEmpty());
        Map<String, Object> m = id.toWatchMessage();
        assertEquals(1, m.size());
        assertEquals("MEDICAL_ID", m.get("type"));
    }

    @Test
    public void lockscreenTextListsFilledFieldsOnly() {
        MedicalId id = new MedicalId();
        id.bloodType = "O-";
        id.contactPhone = "112";
        assertEquals("Bloedgroep: O-\nNoodcontact: 112", id.lockscreenText());
        assertEquals("Bloedgroep O- · ICE 112", id.summary());
    }

    @Test
    public void summaryFallsBackToFirstLine() {
        MedicalId id = new MedicalId();
        id.allergies = "Noten";
        assertEquals("Allergieën: Noten", id.summary());
    }

    @Test
    public void jsonRoundTripKeepsFieldsAndDropsDerivedOnes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MedicalId id = full();
        id.showOnLockscreen = true;
        String json = mapper.writeValueAsString(id);
        assertFalse(json.contains("empty"));
        MedicalId back = mapper.readValue(json, MedicalId.class);
        assertEquals("Anna", back.contactName);
        assertTrue(back.showOnLockscreen);
    }
}
