package nl.paree.climbpro.domain.tire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TirePressureUnitsTest {

    @Test
    public void parseBar_acceptsCommaAndDot_roundsToOneDecimal() {
        assertEquals(6.5, TirePressureUnits.parseBar("6,5"), 1e-9);
        assertEquals(6.5, TirePressureUnits.parseBar(" 6.5 "), 1e-9);
        assertEquals(6.3, TirePressureUnits.parseBar("6.26"), 1e-9);
        assertEquals(2.0, TirePressureUnits.parseBar("2"), 1e-9);
    }

    @Test
    public void parseBar_rejectsEmptyGarbageAndOutOfRange() {
        assertNull(TirePressureUnits.parseBar(null));
        assertNull(TirePressureUnits.parseBar(""));
        assertNull(TirePressureUnits.parseBar("abc"));
        assertNull(TirePressureUnits.parseBar("0.2"));
        assertNull(TirePressureUnits.parseBar("90"));   // psi typed into a bar field
        assertNull(TirePressureUnits.parseBar("NaN"));
    }

    @Test
    public void parsePsi_convertsToBarAndRangeChecks() {
        assertEquals(6.5, TirePressureUnits.parsePsi("94"), 1e-9);
        assertEquals(2.0, TirePressureUnits.parsePsi("29,0"), 1e-9);
        assertNull(TirePressureUnits.parsePsi(""));
        assertNull(TirePressureUnits.parsePsi("abc"));
        assertNull(TirePressureUnits.parsePsi("5"));     // 0.3 bar, below range
        assertNull(TirePressureUnits.parsePsi("400"));   // above range
        assertEquals(6.5, TirePressureUnits.parse("94", true), 1e-9);
        assertEquals(6.5, TirePressureUnits.parse("6,5", false), 1e-9);
    }

    @Test
    public void format_preferredUnitFirst() {
        java.util.Locale prev = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.US);
        try {
            assertEquals("94 psi (6.5 bar)", TirePressureUnits.format(6.5, true));
            assertEquals("6.5 bar (94 psi)", TirePressureUnits.format(6.5, false));
        } finally {
            java.util.Locale.setDefault(prev);
        }
    }

    @Test
    public void barToPsi_matchesAdviceScale() {
        assertEquals(94, TirePressureUnits.barToPsi(6.5));
        assertEquals(87, TirePressureUnits.barToPsi(6.0));
    }
}
