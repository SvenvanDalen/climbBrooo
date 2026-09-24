package nl.paree.climbpro.data.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One wear part or service item in the maintenance tracker (issue #154), e.g. "Ketting" or
 * "Service". Phone-only, never sent to the watch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MaintenanceComponent {
    /** Stable id; the built-in defaults use fixed ids, user-added parts a random UUID. */
    public String  id;
    public String  name;
    /** Service interval in km; 0 = no km criterion. */
    public int     intervalKm;
    /** Service interval in calendar months; 0 = no time criterion. */
    public int     intervalMonths;
    /** Latest service (epoch seconds); 0 = unknown, the component is then never due. */
    public long    lastServicedEpochSec;
    /** Recorded service dates (epoch seconds), oldest first, capped to a small history. */
    public List<Long> serviceHistory = new ArrayList<>();
    /**
     * Whether indoor trainer rides (Strava {@code VirtualRide}) count towards this component.
     * Off by default: trainer km don't wear a road chain or tyres the same way.
     */
    public boolean includeVirtualRides;

    public MaintenanceComponent() {}

    public MaintenanceComponent(String id, String name, int intervalKm, int intervalMonths) {
        this.id             = id;
        this.name           = name;
        this.intervalKm     = intervalKm;
        this.intervalMonths = intervalMonths;
    }
}
