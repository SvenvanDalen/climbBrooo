package nl.paree.climbpro.domain.rider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

/** Weight on a date for historical W/kg (issue #408). */
public class WeightHistoryTest {

    private static final WeightHistory HISTORY = new WeightHistory(Arrays.asList(
            new WeightHistory.Point(LocalDate.of(2026, 3, 1), 75.0),
            new WeightHistory.Point(LocalDate.of(2026, 1, 1), 80.0),
            new WeightHistory.Point(LocalDate.of(2026, 2, 1), 500.0)), // implausible: ignored
            70.0, ZoneOffset.UTC);

    @Test
    public void usesLatestMeasurementOnOrBeforeTheDate() {
        assertEquals(80.0, HISTORY.weightOn(LocalDate.of(2026, 2, 15)), 1e-9);
        assertEquals(75.0, HISTORY.weightOn(LocalDate.of(2026, 3, 1)), 1e-9);
        assertEquals(75.0, HISTORY.weightOn(LocalDate.of(2026, 9, 1)), 1e-9);
    }

    @Test
    public void dateBeforeFirstMeasurementTakesTheFirst() {
        assertEquals(80.0, HISTORY.weightOn(LocalDate.of(2025, 6, 1)), 1e-9);
    }

    @Test
    public void withoutMeasurementsTheProfileWeightIsUsed() {
        WeightHistory h = new WeightHistory(Collections.emptyList(), 72.5, ZoneOffset.UTC);
        assertEquals(72.5, h.weightOn(LocalDate.of(2026, 1, 1)), 1e-9);
    }

    @Test
    public void wattsPerKgUsesWeightOfThatDay() {
        long jan15 = LocalDate.of(2026, 1, 15).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        assertEquals(4.0, HISTORY.wattsPerKg(320, jan15), 1e-9);
        assertNull(HISTORY.wattsPerKg(null, jan15));
        assertNull(new WeightHistory(null, 0, ZoneOffset.UTC).wattsPerKg(300, jan15));
    }
}
