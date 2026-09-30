package nl.paree.climbpro.data.tire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One manual tire-pressure check (issue #155 "Bandenspanning-log"). Pressures are stored in
 * bar with one decimal, exactly as the rider typed them; the UI shows the psi equivalent next
 * to it so it lines up with the psi ranges of the tire-pressure advice (issue #90).
 * Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TirePressureLogEntry {
    /** Random UUID; stable handle for deletes. */
    public String id;
    /** Moment of the check (epoch seconds). */
    public long   timestampEpochSec;
    public double frontBar;
    public double rearBar;
    /** Optional free-text note; null or empty when absent. */
    public String note;
}
