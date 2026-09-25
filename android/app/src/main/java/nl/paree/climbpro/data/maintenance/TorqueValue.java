package nl.paree.climbpro.data.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rider-entered torque value (issue #237 "Aanhaalmomenten"), e.g. "Racefiets · Stuurpen
 * stuurklem: 5 Nm". The app has no bike entity, so {@link #bike} is a free-text label.
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TorqueValue {
    /** Random UUID; stable handle for edits and deletes. */
    public String id;
    /** Optional free-text bike label; null when absent. */
    public String bike;
    public String part;
    /** Torque in newton-metre, rounded to 0.1; always in (0, 200]. */
    public double nm;
    /** Optional free-text note (e.g. "met carbonpasta", "fabrikant: 4–5 Nm"); null when absent. */
    public String note;

    public TorqueValue() {}

    public TorqueValue(String id, String bike, String part, double nm, String note) {
        this.id   = id;
        this.bike = bike;
        this.part = part;
        this.nm   = nm;
        this.note = note;
    }
}
