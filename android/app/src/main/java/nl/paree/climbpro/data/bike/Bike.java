package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One bike in the cost overview (issue #233). Strava rides in the archive carry no gear id, so
 * a bike's km are: archived rides started in {@code [sinceEpochSec, retiredEpochSec)} (when
 * {@link #countArchiveRides}) plus manually entered {@link #extraKm}. Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Bike {
    public String  id;
    public String  name;
    /** Archive rides count from this moment (local start of day); 0 = not set, none count. */
    public long    sinceEpochSec;
    /** Out of use since (epoch seconds); 0 = still in use. Later rides don't count. */
    public long    retiredEpochSec;
    /** Off for riders with several bikes in use at once: the archive can't tell them apart. */
    public boolean countArchiveRides = true;
    /** Whether indoor trainer rides (Strava {@code VirtualRide}) count. */
    public boolean includeVirtualRides;
    /** Km the archive doesn't know about, entered by the user. */
    public int     extraKm;
    public List<BikeCostEntry> costs = new ArrayList<>();
}
