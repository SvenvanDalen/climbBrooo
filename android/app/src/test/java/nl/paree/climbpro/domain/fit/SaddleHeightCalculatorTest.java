package nl.paree.climbpro.domain.fit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SaddleHeightCalculatorTest {

    @Test
    public void parseInseamCm_acceptsCommaDotAndSpaces() {
        assertEquals(81.5, SaddleHeightCalculator.parseInseamCm("81,5"), 1e-9);
        assertEquals(81.5, SaddleHeightCalculator.parseInseamCm(" 81.5 "), 1e-9);
        assertEquals(85.0, SaddleHeightCalculator.parseInseamCm("85"), 1e-9);
    }

    @Test
    public void parseInseamCm_acceptsInclusiveBounds() {
        assertEquals(60.0, SaddleHeightCalculator.parseInseamCm("60"), 1e-9);
        assertEquals(110.0, SaddleHeightCalculator.parseInseamCm("110"), 1e-9);
    }

    @Test
    public void parseInseamCm_rejectsJustOutsideBounds() {
        assertNull(SaddleHeightCalculator.parseInseamCm("59,9"));
        assertNull(SaddleHeightCalculator.parseInseamCm("110,1"));
    }

    @Test
    public void parseInseamCm_rejectsMillimetresAndInches() {
        assertNull(SaddleHeightCalculator.parseInseamCm("850")); // mm
        assertNull(SaddleHeightCalculator.parseInseamCm("33"));  // inches
    }

    @Test
    public void parseInseamCm_rejectsGarbage() {
        assertNull(SaddleHeightCalculator.parseInseamCm(null));
        assertNull(SaddleHeightCalculator.parseInseamCm(""));
        assertNull(SaddleHeightCalculator.parseInseamCm("   "));
        assertNull(SaddleHeightCalculator.parseInseamCm("abc"));
        assertNull(SaddleHeightCalculator.parseInseamCm("8,5,1"));
        assertNull(SaddleHeightCalculator.parseInseamCm("NaN"));
        assertNull(SaddleHeightCalculator.parseInseamCm("Infinity"));
        assertNull(SaddleHeightCalculator.parseInseamCm("-85"));
    }

    @Test
    public void isBlank_onlyForNullOrWhitespace() {
        assertTrue(SaddleHeightCalculator.isBlank(null));
        assertTrue(SaddleHeightCalculator.isBlank("  "));
        assertFalse(SaddleHeightCalculator.isBlank("8"));
    }

    @Test
    public void lemond_is883PermilleRoundedToHalfCm() {
        // 85 * 0.883 = 75.055 -> 75.0
        assertEquals(75.0, SaddleHeightCalculator.lemondCm(85.0), 1e-9);
        // 81.5 * 0.883 = 71.9645 -> 72.0
        assertEquals(72.0, SaddleHeightCalculator.lemondCm(81.5), 1e-9);
        // 80 * 0.883 = 70.64 -> 70.5
        assertEquals(70.5, SaddleHeightCalculator.lemondCm(80.0), 1e-9);
    }

    @Test
    public void hamley_is109PercentRoundedToHalfCm() {
        // 85 * 1.09 = 92.65 -> 92.5
        assertEquals(92.5, SaddleHeightCalculator.hamleyCm(85.0), 1e-9);
        // 80 * 1.09 = 87.2 -> 87.0
        assertEquals(87.0, SaddleHeightCalculator.hamleyCm(80.0), 1e-9);
    }

    @Test
    public void formatCm_usesDutchDecimalComma() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals("75,0 cm", SaddleHeightCalculator.formatCm(75.0));
            assertEquals("92,5 cm", SaddleHeightCalculator.formatCm(92.5));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
