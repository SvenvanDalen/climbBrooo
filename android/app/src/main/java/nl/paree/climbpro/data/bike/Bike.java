package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One bike in the bike garage (issue #187), which grew out of the cost overview (issue #233)
 * and shares its file. Cost km are archived rides assigned to this bike (Strava gear id, else
 * the fallback rule in {@code BikeGarage}) that started in {@code [sinceEpochSec,
 * retiredEpochSec)} (when {@link #countArchiveRides}) plus manually entered {@link #extraKm}.
 * Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Bike {
    public static final String TYPE_ROAD    = "road";
    public static final String TYPE_GRAVEL  = "gravel";
    public static final String TYPE_MTB     = "mtb";
    public static final String TYPE_TRAINER = "trainer";

    public String  id;
    public String  name;
    /** Archive rides count from this moment (local start of day); 0 = not set, none count. */
    public long    sinceEpochSec;
    /** Out of use since (epoch seconds); 0 = still in use. Later rides don't count. */
    public long    retiredEpochSec;
    /** Whether archived rides count towards the cost km at all. */
    public boolean countArchiveRides = true;
    /** Whether indoor trainer rides (Strava {@code VirtualRide}) count. */
    public boolean includeVirtualRides;
    /** Km the archive doesn't know about, entered by the user. */
    public int     extraKm;
    public List<BikeCostEntry> costs = new ArrayList<>();

    // Garage fields (issue #187); absent in files from before the garage.
    /** One of the {@code TYPE_*} constants; unknown values are repaired to {@link #TYPE_ROAD}. */
    public String  type = TYPE_ROAD;
    /** Bike weight in kg; 0 = unknown. The active bike's weight feeds the rider profile. */
    public double  weightKg;
    /** Tyre width in mm; 0 = unknown. */
    public int     tyreWidthMm;
    /** Chainrings as the gear calculator takes them, e.g. "50/34"; null = unknown. */
    public String  chainrings;
    /** Cassette as the gear calculator takes it, e.g. "11-30"; null = unknown. */
    public String  cassette;
    /** Strava gear id (e.g. "b1234567") of rides on this bike; null = not linked. */
    public String  stravaGearId;
}
