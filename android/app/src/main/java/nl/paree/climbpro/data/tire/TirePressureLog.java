package nl.paree.climbpro.data.tire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object of {@code tire_pressure_log.json} (issue #155): the manual check entries plus the
 * reminder settings, so the whole feature lives in one small JSON file. Fields missing from an
 * older file keep the defaults below.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TirePressureLog {

    public static final int DEFAULT_REMINDER_DAYS = 7;
    public static final int DEFAULT_REMINDER_KM   = 300;

    /** Entries in insertion order; the UI sorts newest first by timestamp. */
    public List<TirePressureLogEntry> entries = new ArrayList<>();
    /** Remind after this many days since the latest check; 0 = off. */
    public int reminderDays = DEFAULT_REMINDER_DAYS;
    /** Remind after this many outdoor km since the latest check; 0 = off. */
    public int reminderKm   = DEFAULT_REMINDER_KM;
}
