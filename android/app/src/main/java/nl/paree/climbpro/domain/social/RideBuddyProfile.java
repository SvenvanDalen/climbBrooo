package nl.paree.climbpro.domain.social;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A rider profile for the ride-buddy matcher (issue #242): a handful of coarse riding habits,
 * either derived from your own ride archive ({@link RideBuddyProfileBuilder}) or imported from
 * someone else's profile code ({@link RideBuddyCode}). Every field is optional — the sharer
 * picks which ones go into the code — and "not shared" is 0 (or null for the area).
 *
 * <p>Privacy: there are no coordinates here except {@link #areaLat}/{@link #areaLon}, and those
 * are always the centre of a ~5 km grid cell ({@link #snapToCell}), never a real start point.
 * Stored as JSON in {@code ride_buddies.json} (imported profiles only; your own profile is
 * recomputed on demand and never stored).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RideBuddyProfile {

    /** Field selection bits for {@link #restrictTo}. */
    public static final int FIELD_PACE = 1;
    public static final int FIELD_CLIMB = 2;
    public static final int FIELD_DISTANCE = 4;
    public static final int FIELD_TYPE = 8;
    public static final int FIELD_SCHEDULE = 16;
    public static final int FIELD_AREA = 32;
    public static final int ALL_FIELDS = 63;

    /** Ride-type bits. */
    public static final int TYPE_ROAD = 1;
    public static final int TYPE_GRAVEL = 2;
    public static final int TYPE_MTB = 4;

    /** Daypart bits. */
    public static final int PART_MORNING = 1;
    public static final int PART_AFTERNOON = 2;
    public static final int PART_EVENING = 4;

    /** Grid cell edge in degrees latitude (~5 km). */
    public static final double CELL_DEG = 0.045;

    /** Random per-install id for this feature (not the friend-feed id, so codes don't link). */
    public String riderId;
    public String name;
    public long createdEpochSec;
    /** When this profile was imported; 0 for your own. */
    public long importedEpochSec;
    /** Number of rides the profile is based on (always shared; says how reliable it is). */
    public int rideCount;
    /** Typical average speed on flat-ish rides, in 0.1 km/h. */
    public int flatSpeedDkmh;
    /** Typical climbing speed (VAM) in metres per hour. */
    public int vamMph;
    /** Typical (median) ride distance in km. */
    public int typicalDistanceKm;
    /** Bit mask of {@link #TYPE_ROAD}, {@link #TYPE_GRAVEL}, {@link #TYPE_MTB}. */
    public int rideTypes;
    /** Bit 0 = Monday … bit 6 = Sunday. */
    public int weekdays;
    /** Bit mask of {@link #PART_MORNING}, {@link #PART_AFTERNOON}, {@link #PART_EVENING}. */
    public int dayparts;
    /** Centre of the ~5 km home cell; null when not shared. */
    public Double areaLat;
    public Double areaLon;

    public RideBuddyProfile() {}

    @JsonIgnore
    public boolean hasArea() {
        return areaLat != null && areaLon != null;
    }

    /** Which fields carry a value, as {@code FIELD_*} bits. */
    @JsonIgnore
    public int availableFields() {
        int f = 0;
        if (flatSpeedDkmh > 0) f |= FIELD_PACE;
        if (vamMph > 0) f |= FIELD_CLIMB;
        if (typicalDistanceKm > 0) f |= FIELD_DISTANCE;
        if (rideTypes != 0) f |= FIELD_TYPE;
        if (weekdays != 0 || dayparts != 0) f |= FIELD_SCHEDULE;
        if (hasArea()) f |= FIELD_AREA;
        return f;
    }

    /** A copy holding only the {@code fields} the user opted in to. */
    public RideBuddyProfile restrictTo(int fields) {
        RideBuddyProfile p = copy();
        if ((fields & FIELD_PACE) == 0) p.flatSpeedDkmh = 0;
        if ((fields & FIELD_CLIMB) == 0) p.vamMph = 0;
        if ((fields & FIELD_DISTANCE) == 0) p.typicalDistanceKm = 0;
        if ((fields & FIELD_TYPE) == 0) p.rideTypes = 0;
        if ((fields & FIELD_SCHEDULE) == 0) {
            p.weekdays = 0;
            p.dayparts = 0;
        }
        if ((fields & FIELD_AREA) == 0) {
            p.areaLat = null;
            p.areaLon = null;
        }
        return p;
    }

    public RideBuddyProfile copy() {
        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = riderId;
        p.name = name;
        p.createdEpochSec = createdEpochSec;
        p.importedEpochSec = importedEpochSec;
        p.rideCount = rideCount;
        p.flatSpeedDkmh = flatSpeedDkmh;
        p.vamMph = vamMph;
        p.typicalDistanceKm = typicalDistanceKm;
        p.rideTypes = rideTypes;
        p.weekdays = weekdays;
        p.dayparts = dayparts;
        p.areaLat = areaLat;
        p.areaLon = areaLon;
        return p;
    }

    /**
     * The centre of the ~5 km grid cell containing {@code lat,lon}, as {@code {lat, lon}} rounded
     * to 3 decimals. Rows are {@link #CELL_DEG} high; within a row the cell width in degrees
     * longitude is widened by 1/cos(row latitude) so cells stay roughly square. Idempotent:
     * snapping a cell centre returns the same centre, which is what lets the decoder re-snap
     * whatever arrives without moving honest codes.
     */
    public static double[] snapToCell(double lat, double lon) {
        long row = (long) Math.floor(lat / CELL_DEG);
        double centreLat = (row + 0.5) * CELL_DEG;
        double cos = Math.cos(Math.toRadians(centreLat));
        double lonDeg = CELL_DEG / Math.max(0.05, cos);
        long col = (long) Math.floor(lon / lonDeg);
        double centreLon = (col + 0.5) * lonDeg;
        return new double[]{round3(Math.max(-90, Math.min(90, centreLat))), round3(centreLon)};
    }

    /** Stable key of the cell containing {@code lat,lon}. */
    static String cellKey(double lat, double lon) {
        double[] c = snapToCell(lat, lon);
        return c[0] + "," + c[1];
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
