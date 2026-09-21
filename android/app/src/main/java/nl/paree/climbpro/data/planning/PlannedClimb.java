package nl.paree.climbpro.data.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Serialised form of a single planned climb/route reminder (Kalenderintegratie, issue #70).
 * Layout: getFilesDir()/planned_climbs.json — a flat array of {@link PlannedClimb}.
 *
 * A planned entry always references a route ({@link #routeId}); {@link #climbIndex} is
 * {@code -1} when the whole route is planned (not a specific climb within it).
 * {@link #displayName} is denormalised at creation time so the planning screen still shows
 * something sensible if the underlying route/climb is later renamed, resegmented or deleted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PlannedClimb {

    public static final int WHOLE_ROUTE = -1;

    public String id;                 // UUID, stable identity for WorkManager tags + removal
    public String routeId;
    public int    climbIndex = WHOLE_ROUTE;
    public String displayName;

    /** Planned date/time, epoch seconds, in the user's local timezone at creation time. */
    public long plannedAtEpochSec;

    public long createdAtMs;

    /** Set once the local reminder notification has fired, so it never re-fires. */
    public boolean reminderSent;

    /** Best-effort device calendar event id, if the user opted in; -1 if none was created. */
    public long calendarEventId = -1L;

    public PlannedClimb() {}

    public PlannedClimb(String id, String routeId, int climbIndex, String displayName,
                         long plannedAtEpochSec, long createdAtMs) {
        this.id = id;
        this.routeId = routeId;
        this.climbIndex = climbIndex;
        this.displayName = displayName;
        this.plannedAtEpochSec = plannedAtEpochSec;
        this.createdAtMs = createdAtMs;
    }
}
