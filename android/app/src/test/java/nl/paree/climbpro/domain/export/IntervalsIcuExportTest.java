package nl.paree.climbpro.domain.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

public class IntervalsIcuExportTest {

    @Test
    public void athleteId_blankMeansOwnAthlete() {
        assertEquals("0", IntervalsIcuExport.normalizeAthleteId(null));
        assertEquals("0", IntervalsIcuExport.normalizeAthleteId("   "));
        assertEquals("0", IntervalsIcuExport.normalizeAthleteId("0"));
    }

    @Test
    public void athleteId_acceptsPrefixedAndBareDigits() {
        assertEquals("i12345", IntervalsIcuExport.normalizeAthleteId(" i12345 "));
        assertEquals("i12345", IntervalsIcuExport.normalizeAthleteId("I12345"));
        assertEquals("i12345", IntervalsIcuExport.normalizeAthleteId("12345"));
    }

    @Test
    public void athleteId_rejectsGarbage() {
        assertNull(IntervalsIcuExport.normalizeAthleteId("abc"));
        assertNull(IntervalsIcuExport.normalizeAthleteId("i12/../x"));
        assertNull(IntervalsIcuExport.normalizeAthleteId("i"));
    }

    @Test
    public void authHeader_isBasicWithApiKeyUser() {
        String header = IntervalsIcuExport.basicAuthHeader(" secret123 ");
        assertTrue(header.startsWith("Basic "));
        String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                StandardCharsets.UTF_8);
        assertEquals("API_KEY:secret123", decoded);
    }

    @Test
    public void isValidApiKey_requiresNonBlankWithoutWhitespaceInside() {
        assertFalse(IntervalsIcuExport.isValidApiKey(null));
        assertFalse(IntervalsIcuExport.isValidApiKey("  "));
        assertFalse(IntervalsIcuExport.isValidApiKey("ab cd"));
        assertTrue(IntervalsIcuExport.isValidApiKey(" abc123 "));
    }

    @Test
    public void startDateLocal_isMidnightIso() {
        assertEquals("2026-11-03T00:00:00",
                IntervalsIcuExport.startDateLocal(LocalDate.of(2026, 11, 3)));
    }

    @Test
    public void activityType_indoorIsVirtualRide() {
        assertEquals("VirtualRide", IntervalsIcuExport.activityType(true));
        assertEquals("Ride", IntervalsIcuExport.activityType(false));
    }

    @Test
    public void description_withHistoryIncludesPrAndAttempts() {
        String d = IntervalsIcuExport.description("Col du Test", 5, 754, 3);
        assertTrue(d, d.contains("Col du Test"));
        assertTrue(d, d.contains("PR: 12:34"));
        assertTrue(d, d.contains("3 pogingen"));
        assertTrue(d, d.contains("5×"));
    }

    @Test
    public void description_singleAttemptIsSingular() {
        String d = IntervalsIcuExport.description("X", 1, 3725, 1);
        assertTrue(d, d.contains("PR: 1:02:05"));
        assertTrue(d, d.contains("1 poging"));
        assertFalse(d, d.contains("pogingen"));
        assertFalse(d, d.contains("×"));
    }

    @Test
    public void description_withoutHistorySaysSo() {
        String d = IntervalsIcuExport.description("X", 1, 0, 0);
        assertTrue(d, d.contains("Nog geen pogingen"));
        assertFalse(d, d.contains("PR:"));
    }

    @Test
    public void eventName_marksRepeats() {
        assertEquals("Col du Test", IntervalsIcuExport.eventName("Col du Test", 1));
        assertEquals("5× Col du Test", IntervalsIcuExport.eventName("Col du Test", 5));
    }

    @Test
    public void errorMessage_mapsAuthFailures() {
        assertTrue(IntervalsIcuExport.errorMessage(401).contains("API-sleutel"));
        assertTrue(IntervalsIcuExport.errorMessage(403).contains("API-sleutel"));
        assertTrue(IntervalsIcuExport.errorMessage(404).contains("Atleet-id"));
        assertTrue(IntervalsIcuExport.errorMessage(500).contains("500"));
    }
}
