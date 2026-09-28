package nl.paree.climbpro.data.battery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rechargeable (or coin-cell) device on the bike (issue #238 "Accustatus"): e-shifting,
 * lights, power meter, … with the moment it was last charged and a recharge interval in days.
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatteryDevice {
    /** Random UUID; stable handle for edits and deletes. */
    public String id;
    public String name;
    /** {@code BatteryKind} name; unknown or missing values read as OTHER. */
    public String kind;
    /** Moment of the last charge / battery swap (epoch seconds); 0 = never logged. */
    public long   lastChargedEpochSec;
    /** Remind this many days after the last charge; 0 = no reminder. */
    public int    intervalDays;
    /**
     * {@link #lastChargedEpochSec} for which the "opladen" notification was already shown, so
     * the daily worker notifies once per charge cycle. Logging a new charge re-arms it.
     */
    public long   reminderSentForChargeEpochSec;
}
