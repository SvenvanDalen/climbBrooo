package nl.paree.climbpro.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Converts between stored epoch seconds and the fields of Android's date/time pickers in the
 * device time zone. {@code DatePickerDialog} months are 0-based, {@link LocalDate} months 1-based.
 */
public final class PickerDates {

    private PickerDates() {}

    /** The local day of {@code epochSec}. */
    public static LocalDate dayOf(long epochSec, ZoneId zone) {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
    }

    /** The picked day; {@code month0} as delivered by a DatePickerDialog. */
    public static LocalDate pickedDay(int year, int month0, int day) {
        return LocalDate.of(year, month0 + 1, day);
    }

    /** Start of the local day. */
    public static long startOfDay(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toEpochSecond();
    }

    /** Local noon of the day: safely inside the day whatever DST does around midnight. */
    public static long noon(LocalDate day, ZoneId zone) {
        return at(day, 12, 0, zone);
    }

    /** The local time {@code hour:minute} on {@code day}. */
    public static long at(LocalDate day, int hour, int minute, ZoneId zone) {
        return day.atTime(hour, minute).atZone(zone).toEpochSecond();
    }
}
