package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

public class HourlyPrecipitationTest {

    private static final String JSON = "{\"hourly\":{"
            + "\"time\":[\"2026-09-20T09:00\",\"2026-09-20T10:00\",\"2026-09-20T11:00\",\"2026-09-20T12:00\"],"
            + "\"precipitation\":[0.4,null,1.5,2.0]}}";

    private static long sec(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    @Test public void parsesTimesAsUtcHourEndsAndNullsAsNaN() throws IOException {
        HourlyPrecipitation p = HourlyPrecipitation.parse(JSON);
        assertEquals(4, p.hourEnds.length);
        assertEquals(Instant.parse("2026-09-20T09:00:00Z"), p.hourEnds[0]);
        assertEquals(0.4, p.mm[0], 1e-9);
        assertTrue(Double.isNaN(p.mm[1]));
    }

    @Test public void hourValueCoversThePrecedingHour() throws IOException {
        HourlyPrecipitation p = HourlyPrecipitation.parse(JSON);
        // 10:00–11:00 is covered by the value stamped 11:00 (1.5), not the one at 10:00.
        assertEquals(1.5, p.sumBetween(sec("2026-09-20T10:00:00Z"), sec("2026-09-20T11:00:00Z")), 1e-9);
        // 08:30–09:30 overlaps 08:00–09:00 (0.4) and 09:00–10:00 (null → skipped).
        assertEquals(0.4, p.sumBetween(sec("2026-09-20T08:30:00Z"), sec("2026-09-20T09:30:00Z")), 1e-9);
        // 10:15–11:45 overlaps the hours stamped 11:00 and 12:00.
        assertEquals(3.5, p.sumBetween(sec("2026-09-20T10:15:00Z"), sec("2026-09-20T11:45:00Z")), 1e-9);
    }

    @Test public void allNullHoursMeansUnknown() throws IOException {
        HourlyPrecipitation p = HourlyPrecipitation.parse(JSON);
        assertTrue(Double.isNaN(p.sumBetween(sec("2026-09-20T09:10:00Z"), sec("2026-09-20T09:50:00Z"))));
    }

    @Test public void rangeOutsideDataIsUnknown() throws IOException {
        HourlyPrecipitation p = HourlyPrecipitation.parse(JSON);
        assertTrue(Double.isNaN(p.sumBetween(sec("2026-09-21T09:00:00Z"), sec("2026-09-21T10:00:00Z"))));
    }

    @Test(expected = IOException.class)
    public void missingPrecipitationIsAnError() throws IOException {
        HourlyPrecipitation.parse("{\"hourly\":{\"time\":[\"2026-09-20T09:00\"]}}");
    }

    @Test(expected = IOException.class)
    public void missingHourlyIsAnError() throws IOException {
        HourlyPrecipitation.parse("{\"error\":true,\"reason\":\"x\"}");
    }
}
