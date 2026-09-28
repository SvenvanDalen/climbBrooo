package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.domain.climb.AttemptTemperature.Kind;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Issue #80: averaging the temp stream over a pass + heat/cold classification. */
public class AttemptTemperatureTest {

    @Test
    public void average_coversOnlyThePassRange_inclusiveBothEnds() {
        // Samples outside [2..4] are wildly different — they must not leak into the average.
        List<Double> temps = Arrays.asList(0.0, 0.0, 30.0, 32.0, 34.0, 99.0);
        assertEquals(32.0, AttemptTemperature.averageOverPass(temps, 2, 4), 1e-9);
    }

    @Test
    public void average_roundsToOneDecimal() {
        List<Double> temps = Arrays.asList(10.0, 11.0, 11.0);
        assertEquals(10.7, AttemptTemperature.averageOverPass(temps, 0, 2), 1e-9);
    }

    @Test
    public void average_skipsNullSamples() {
        List<Double> temps = Arrays.asList(20.0, null, 30.0);
        assertEquals(25.0, AttemptTemperature.averageOverPass(temps, 0, 2), 1e-9);
    }

    @Test
    public void average_absentOrEmptyStream_isNull() {
        assertNull(AttemptTemperature.averageOverPass(null, 0, 2));
        assertNull(AttemptTemperature.averageOverPass(Collections.<Double>emptyList(), 0, 2));
        assertNull(AttemptTemperature.averageOverPass(Arrays.<Double>asList(null, null), 0, 1));
    }

    @Test
    public void average_shortStream_usesOnlyAvailableSamples() {
        // Temp stream ends mid-pass: average what exists, don't throw.
        List<Double> temps = Arrays.asList(1.0, 2.0, 4.0);
        assertEquals(3.0, AttemptTemperature.averageOverPass(temps, 1, 10), 1e-9);
        // Stream ends before the pass starts: unknown.
        assertNull(AttemptTemperature.averageOverPass(temps, 5, 10));
    }

    @Test
    public void average_invalidRange_isNull() {
        List<Double> temps = Arrays.asList(1.0, 2.0);
        assertNull(AttemptTemperature.averageOverPass(temps, -1, 1));
        assertNull(AttemptTemperature.averageOverPass(temps, 1, 0));
    }

    @Test
    public void classify_boundaries() {
        assertEquals(Kind.NONE, AttemptTemperature.classify(null));
        assertEquals(Kind.HEAT, AttemptTemperature.classify(30.0));   // inclusive
        assertEquals(Kind.NONE, AttemptTemperature.classify(29.9));
        assertEquals(Kind.COLD, AttemptTemperature.classify(5.0));    // inclusive
        assertEquals(Kind.NONE, AttemptTemperature.classify(5.1));
        assertEquals(Kind.COLD, AttemptTemperature.classify(-8.0));
        assertEquals(Kind.NONE, AttemptTemperature.classify(18.0));
    }

    @Test
    public void label_dutchCopy_orNullWhenNotExtreme() {
        assertEquals("🔥 Heet (32 °C)", AttemptTemperature.label(32.4));
        assertEquals("❄️ Koud (3 °C)", AttemptTemperature.label(2.6));
        assertEquals("❄️ Koud (-4 °C)", AttemptTemperature.label(-4.0));
        assertNull(AttemptTemperature.label(18.0));
        assertNull(AttemptTemperature.label(null));
    }
}
