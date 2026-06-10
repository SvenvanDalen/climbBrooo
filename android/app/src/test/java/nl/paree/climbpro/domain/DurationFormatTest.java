package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.DurationFormat;
import org.junit.Test;

import static org.junit.Assert.*;

public class DurationFormatTest {

    @Test
    public void zeroSeconds() {
        assertEquals("0:00", DurationFormat.format(0));
    }

    @Test
    public void underOneMinute() {
        assertEquals("0:05", DurationFormat.format(5));
    }

    @Test
    public void minutesAndSeconds() {
        assertEquals("1:05", DurationFormat.format(65));
        assertEquals("12:34", DurationFormat.format(754));
    }

    @Test
    public void hoursMinutesSeconds() {
        assertEquals("1:01:01", DurationFormat.format(3661));
    }

    @Test
    public void negativeIsClampedToZero() {
        assertEquals("0:00", DurationFormat.format(-10));
    }
}
