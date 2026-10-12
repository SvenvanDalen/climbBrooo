package nl.paree.climbpro.domain.weather;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertArrayEquals;

/** Issue #12: which radius-mode climb starts are dry now. */
public class DryClimbFilterTest {

    private static PrecipitationGrid grid(String... precipitationArrays) throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < precipitationArrays.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"hourly\":{\"time\":[\"2026-10-09T10:00\",\"2026-10-09T11:00\","
                    + "\"2026-10-09T12:00\",\"2026-10-09T13:00\"],\"precipitation\":")
                    .append(precipitationArrays[i]).append("}}");
        }
        return PrecipitationGrid.parse(json.append(']').toString(), precipitationArrays.length);
    }

    private static final Instant NOW = Instant.parse("2026-10-09T10:20:00Z");

    @Test
    public void wetNowOrSoonIsLeftOut() throws Exception {
        PrecipitationGrid g = grid("[0,0,0,5]", "[0.4,0,0,0]", "[0,0,0.3,0]", "[0.2,0.1,0.2,0]");
        // Rain at 13:00 is beyond now + 2 h; 0.2 mm/h is drizzle.
        assertArrayEquals(new boolean[]{true, false, false, true}, DryClimbFilter.dryFlags(g, NOW));
    }

    @Test
    public void unknownCountsAsDry() throws Exception {
        PrecipitationGrid g = grid("[null,null,null,null]");
        assertArrayEquals(new boolean[]{true}, DryClimbFilter.dryFlags(g, NOW));
        assertArrayEquals(new boolean[]{true},
                DryClimbFilter.dryFlags(grid("[9,9,9,9]"), Instant.parse("2026-10-10T10:00:00Z")));
    }
}
