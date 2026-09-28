package nl.paree.climbpro.domain.bike;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EuroAmountTest {

    @Test
    public void parsesDutchAndDotDecimalInput() {
        assertEquals(1200, EuroAmount.parseCents("12"));
        assertEquals(1250, EuroAmount.parseCents("12,5"));
        assertEquals(1250, EuroAmount.parseCents("12,50"));
        assertEquals(705, EuroAmount.parseCents(" 7,05 "));
        assertEquals(50, EuroAmount.parseCents(",50"));
        assertEquals(123456, EuroAmount.parseCents("1.234,56"));
        assertEquals(1250, EuroAmount.parseCents("12.50"));
        assertEquals(1250, EuroAmount.parseCents("12.5"));
        assertEquals(4500, EuroAmount.parseCents("€ 45"));
        assertEquals(4500, EuroAmount.parseCents("€ 45"));
    }

    @Test
    public void stripsNoBreakSpaces() {
        assertEquals(4500, EuroAmount.parseCents("€ 45"));
        assertEquals(1250, EuroAmount.parseCents("12,50 "));
    }

    @Test
    public void dotWithThreeDigitsIsAThousandsSeparator() {
        assertEquals(123400, EuroAmount.parseCents("1.234"));
        assertEquals(99_999_900L, EuroAmount.parseCents("999.999"));
    }

    @Test
    public void acceptsTheMaximumAndRejectsAboveIt() {
        assertEquals(EuroAmount.MAX_CENTS, EuroAmount.parseCents("1000000"));
        assertEquals(EuroAmount.MAX_CENTS, EuroAmount.parseCents("1.000.000,00"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("1000000,01"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("1.234.567"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("99999999999999999999"));
    }

    @Test
    public void rejectsInvalidInput() {
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents(null));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents(""));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("   "));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("abc"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("-5"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("12,345"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("1,2,3"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("12.34.5"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("12.50,3"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("."));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("0"));
        assertEquals(EuroAmount.INVALID, EuroAmount.parseCents("0,00"));
    }

    @Test
    public void formatsDutchStyle() {
        assertEquals("€ 0,00", EuroAmount.format(0));
        assertEquals("€ 0,05", EuroAmount.format(5));
        assertEquals("€ 12,00", EuroAmount.format(1200));
        assertEquals("€ 1.234,56", EuroAmount.format(123456));
        assertEquals("€ 1.000.000,00", EuroAmount.format(EuroAmount.MAX_CENTS));
        assertEquals("-€ 3,10", EuroAmount.format(-310));
    }

    @Test
    public void groupsThousands() {
        assertEquals("0", EuroAmount.groupThousands(0));
        assertEquals("999", EuroAmount.groupThousands(999));
        assertEquals("1.000", EuroAmount.groupThousands(1000));
        assertEquals("1.234.567", EuroAmount.groupThousands(1_234_567));
    }
}
