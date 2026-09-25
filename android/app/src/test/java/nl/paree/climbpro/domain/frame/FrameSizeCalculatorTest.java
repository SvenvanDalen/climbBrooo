package nl.paree.climbpro.domain.frame;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FrameSizeCalculatorTest {

    private static final double EPS = 1e-9;

    @Test
    public void typicalRider_roadAndMtbSizes() {
        // inseam 84 → road 84 × 0.665 = 55.86 → 56 cm; MTB 84 × 0.57 = 47.88 → 47.9 cm, /2.54 = 18.85 → 18.9"
        FrameSizeResult r = FrameSizeCalculator.calculate("180", "84");

        assertTrue(r.ok);
        assertNull(r.error);
        assertEquals(56, r.roadSeatTubeCm);
        assertEquals("L", r.roadLetterSize);
        assertEquals(47.9, r.mtbSeatTubeCm, EPS);
        assertEquals(18.9, r.mtbSeatTubeInch, EPS);
    }

    @Test
    public void decimalCommaAndWhitespace_areAccepted() {
        // inseam 82.5 → road 54.8625 → 55; MTB 47.025 → 47.0 cm, 18.51 → 18.5"
        FrameSizeResult r = FrameSizeCalculator.calculate(" 176,5 ", "82,5");

        assertTrue(r.ok);
        assertEquals(55, r.roadSeatTubeCm);
        assertEquals("M", r.roadLetterSize);
        assertEquals(47.0, r.mtbSeatTubeCm, EPS);
        assertEquals(18.5, r.mtbSeatTubeInch, EPS);
    }

    @Test
    public void decimalPoint_isAccepted() {
        assertTrue(FrameSizeCalculator.calculate("176.5", "82.5").ok);
    }

    @Test
    public void letterSize_tableBoundariesAreLowerInclusive() {
        assertEquals("XS", FrameSizeCalculator.roadLetterSize(140));
        assertEquals("XS", FrameSizeCalculator.roadLetterSize(164.9));
        assertEquals("S", FrameSizeCalculator.roadLetterSize(165));
        assertEquals("S", FrameSizeCalculator.roadLetterSize(171.9));
        assertEquals("M", FrameSizeCalculator.roadLetterSize(172));
        assertEquals("M", FrameSizeCalculator.roadLetterSize(178.9));
        assertEquals("L", FrameSizeCalculator.roadLetterSize(179));
        assertEquals("L", FrameSizeCalculator.roadLetterSize(185.9));
        assertEquals("XL", FrameSizeCalculator.roadLetterSize(186));
        assertEquals("XL", FrameSizeCalculator.roadLetterSize(210));
    }

    @Test
    public void rangeEdges_areInclusive() {
        assertTrue(FrameSizeCalculator.calculate("140", "60").ok);
        assertTrue(FrameSizeCalculator.calculate("210", "110").ok);
    }

    @Test
    public void heightOutOfRange_isError() {
        FrameSizeResult low = FrameSizeCalculator.calculate("139,9", "70");
        FrameSizeResult high = FrameSizeCalculator.calculate("210,1", "90");

        assertFalse(low.ok);
        assertTrue(low.error.contains("Lengte"));
        assertFalse(high.ok);
        assertTrue(high.error.contains("Lengte"));
    }

    @Test
    public void inseamOutOfRange_isError() {
        FrameSizeResult low = FrameSizeCalculator.calculate("170", "59,9");
        FrameSizeResult high = FrameSizeCalculator.calculate("200", "110,1");

        assertFalse(low.ok);
        assertTrue(low.error.contains("Binnenbeenlengte"));
        assertFalse(high.ok);
        assertTrue(high.error.contains("Binnenbeenlengte"));
    }

    @Test
    public void inseamNotBelowHeight_isError() {
        // Fields swapped by the user: inseam 180 ≥ height 84.
        FrameSizeResult swapped = FrameSizeCalculator.calculate("84", "180");

        assertFalse(swapped.ok);
        assertNotNull(swapped.error);
        assertTrue(swapped.error.contains("omgedraaid"));
    }

    @Test
    public void numericOverload_checksInseamBelowHeightFirst() {
        // 150/150 is also out of the inseam range; the "< height" rule is checked first.
        FrameSizeResult r = FrameSizeCalculator.calculate(150.0, 150.0);

        assertFalse(r.ok);
        assertTrue(r.error.contains("kleiner"));
    }

    @Test
    public void emptyOrGarbage_isFieldSpecificError() {
        FrameSizeResult emptyHeight = FrameSizeCalculator.calculate("", "84");
        FrameSizeResult garbageInseam = FrameSizeCalculator.calculate("180", "abc");
        FrameSizeResult withUnit = FrameSizeCalculator.calculate("180", "82,5 cm");
        FrameSizeResult nulls = FrameSizeCalculator.calculate(null, null);

        assertFalse(emptyHeight.ok);
        assertTrue(emptyHeight.error.contains("Lengte"));
        assertFalse(garbageInseam.ok);
        assertTrue(garbageInseam.error.contains("Binnenbeenlengte"));
        assertFalse(withUnit.ok);
        assertFalse(nulls.ok);
    }

    @Test
    public void nanAndInfinity_areRejected() {
        assertNull(FrameSizeCalculator.parseCm("NaN"));
        assertNull(FrameSizeCalculator.parseCm("Infinity"));
        assertNull(FrameSizeCalculator.parseCm("-Infinity"));
        assertFalse(FrameSizeCalculator.calculate("NaN", "84").ok);
        assertFalse(FrameSizeCalculator.calculate("180", "Infinity").ok);
    }

    @Test
    public void parseCm_handlesCommaAndWhitespace() {
        assertEquals(82.5, FrameSizeCalculator.parseCm(" 82,5 "), EPS);
        assertEquals(82.5, FrameSizeCalculator.parseCm("82.5"), EPS);
        assertNull(FrameSizeCalculator.parseCm("   "));
        assertNull(FrameSizeCalculator.parseCm(null));
        assertNull(FrameSizeCalculator.parseCm("8,2,5"));
    }
}
