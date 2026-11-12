package nl.paree.climbpro.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formats stored epoch-second timestamps in the device time zone. */
public final class EpochDates {

    private EpochDates() {}

    public static String format(DateTimeFormatter formatter, long epochSec) {
        return formatter.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()));
    }
}
