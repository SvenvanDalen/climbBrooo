package nl.paree.climbpro.domain.units;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

/** Issue #262: display-unit conversion and formatting. */
public class UnitFormatterTest {

    private static final UnitFormatter METRIC =
            new UnitFormatter(UnitPreferences.METRIC, Locale.US);
    private static final UnitFormatter IMPERIAL =
            new UnitFormatter(new UnitPreferences(true, true, true), Locale.US);

    @Test
    public void rawConversions() {
        assertEquals(1.0, UnitFormatter.metersToMiles(1609.344), 1e-9);
        assertEquals(3280.84, UnitFormatter.metersToFeet(1000), 1e-6);
        assertEquals(62.137, UnitFormatter.kmhToMph(100), 1e-3);
        assertEquals(58.015, UnitFormatter.barToPsi(4.0), 1e-3);
        assertEquals(4.0, UnitFormatter.psiToBar(UnitFormatter.barToPsi(4.0)), 1e-9);
        assertEquals(32.0, UnitFormatter.celsiusToFahrenheit(0), 1e-9);
        assertEquals(212.0, UnitFormatter.celsiusToFahrenheit(100), 1e-9);
        assertEquals(-40.0, UnitFormatter.celsiusToFahrenheit(-40), 1e-9);
    }

    @Test
    public void metricFormatting() {
        assertEquals("12.3 km", METRIC.distance(12345));
        assertEquals("450 m", METRIC.elevation(450.4));
        assertEquals("850 m", METRIC.shortDistance(850));
        assertEquals("25 km/u", METRIC.speed(25.2));
        assertEquals("4.5 bar", METRIC.pressure(4.5));
        assertEquals("12 °C", METRIC.temperature(12.4));
    }

    @Test
    public void imperialFormatting() {
        assertEquals("7.7 mi", IMPERIAL.distance(12345));
        assertEquals("1476 ft", IMPERIAL.elevation(450));
        assertEquals("16 mph", IMPERIAL.speed(25));
        assertEquals("65 psi", IMPERIAL.pressure(4.5));
        assertEquals("54 °F", IMPERIAL.temperature(12.4));
    }

    @Test
    public void climbLengthKeepsMetricMetresAndUsesMilesWhenImperial() {
        assertEquals("2400 m", METRIC.climbLength(2400));
        assertEquals("1.5 mi", IMPERIAL.climbLength(2400));
        assertEquals("984 ft", IMPERIAL.climbLength(300));
    }

    @Test
    public void unitLabels() {
        assertEquals("km", METRIC.distanceUnit());
        assertEquals("mi", IMPERIAL.distanceUnit());
        assertEquals("m", METRIC.elevationUnit());
        assertEquals("ft", IMPERIAL.elevationUnit());
        assertEquals("bar", METRIC.pressureUnit());
        assertEquals("psi", IMPERIAL.pressureUnit());
        assertEquals("°C", METRIC.temperatureUnit());
        assertEquals("°F", IMPERIAL.temperatureUnit());
        assertEquals("km/u", METRIC.speedUnit());
        assertEquals("mph", IMPERIAL.speedUnit());
    }

    @Test
    public void axesAreIndependent() {
        UnitFormatter psiOnly = new UnitFormatter(new UnitPreferences(false, true, false), Locale.US);
        assertEquals("12.3 km", psiOnly.distance(12345));
        assertEquals("65 psi", psiOnly.pressure(4.5));
        assertEquals("12 °C", psiOnly.temperature(12));
        UnitFormatter fOnly = new UnitFormatter(new UnitPreferences(false, false, true), Locale.US);
        assertEquals("4.5 bar", fOnly.pressure(4.5));
        assertEquals("50 °F", fOnly.temperature(10));
    }

    @Test
    public void valuesForStringResources() {
        assertEquals(12.345, METRIC.distanceValue(12345), 1e-9);
        assertEquals(7.671, IMPERIAL.distanceValue(12345), 1e-3);
        assertEquals(100.0, METRIC.elevationValue(100), 1e-9);
        assertEquals(328.084, IMPERIAL.elevationValue(100), 1e-3);
    }

    @Test
    public void nullPreferencesMeanMetric() {
        assertEquals("12.3 km", new UnitFormatter(null, Locale.US).distance(12345));
    }

    @Test
    public void wireFlagsRoundTrip() {
        for (int f = 0; f < 8; f++) {
            assertEquals(f, UnitPreferences.fromWireFlags(f).toWireFlags());
        }
        assertEquals(0, UnitPreferences.METRIC.toWireFlags());
        assertEquals(1, new UnitPreferences(true, false, false).toWireFlags());
        assertEquals(2, new UnitPreferences(false, true, false).toWireFlags());
        assertEquals(4, new UnitPreferences(false, false, true).toWireFlags());
        assertEquals(UnitPreferences.METRIC, UnitPreferences.fromWireFlags(8)); // unknown bit
    }

    @Test
    public void signatureDiffersPerChoice() {
        assertNotEquals(UnitPreferences.METRIC.signature(),
                new UnitPreferences(true, false, false).signature());
    }
}
