package nl.paree.climbpro.domain.health;

import nl.paree.climbpro.data.strava.StravaActivityDto;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * One ride as written to Android Health Connect (issue #255): an exercise session plus its
 * distance, elevation gain and — when Strava knows the work done — calories. Pure mapping
 * from a Strava activity; the Health Connect record classes are built from this in
 * {@code data.health.HealthConnectGateway}.
 *
 * <p>{@link #clientRecordId} is stable per Strava activity, so writing the same ride again
 * (a re-sync, or a manual "write now" after an automatic one) updates the existing Health
 * Connect records instead of duplicating them.
 */
public final class RideHealthEntry {

    private static final Set<String> RIDE_TYPES = new HashSet<>(Arrays.asList(
            "Ride", "VirtualRide", "EBikeRide", "GravelRide", "MountainBikeRide",
            "EMountainBikeRide", "Velomobile", "Handcycle"));

    public final String clientRecordId;
    public final String title;
    public final Instant start;
    public final Instant end;
    /** Local offset at the start, from Strava's local start time; null when unknown. */
    public final ZoneOffset offset;
    /** Indoor trainer ride (Zwift etc.) — written as stationary biking. */
    public final boolean stationary;
    public final double distanceM;
    public final double elevationM;
    /** Metabolic energy in kcal, or {@code null} when Strava has no work (kJ) figure. */
    public final Double kcal;

    private RideHealthEntry(String clientRecordId, String title, Instant start, Instant end,
                            ZoneOffset offset, boolean stationary, double distanceM,
                            double elevationM, Double kcal) {
        this.clientRecordId = clientRecordId;
        this.title = title;
        this.start = start;
        this.end = end;
        this.offset = offset;
        this.stationary = stationary;
        this.distanceM = distanceM;
        this.elevationM = elevationM;
        this.kcal = kcal;
    }

    /**
     * @return the entry, or null for non-cycling activities and activities without a usable
     *         start time or duration.
     */
    public static RideHealthEntry from(StravaActivityDto a) {
        if (a == null || a.type == null || !RIDE_TYPES.contains(a.type)) return null;
        Instant start;
        try {
            start = Instant.parse(a.startDate);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
        int seconds = a.elapsedTime > 0 ? a.elapsedTime : a.movingTime;
        if (seconds <= 0) return null;

        ZoneOffset offset = null;
        if (a.startDateLocal != null) {
            try {
                // Strava writes the local wall time with a misleading "Z" suffix.
                LocalDateTime local = LocalDateTime.parse(a.startDateLocal.replace("Z", ""));
                long diff = Duration.between(start, local.toInstant(ZoneOffset.UTC)).getSeconds();
                if (Math.abs(diff) <= 18 * 3600) offset = ZoneOffset.ofTotalSeconds((int) diff);
            } catch (DateTimeParseException ignored) {
                // leave the offset unknown; Health Connect accepts null
            }
        }
        // Mechanical work (kJ) to metabolic kcal: ~24% efficiency and 4.184 kJ/kcal cancel
        // out almost exactly, which is why cycling apps report kJ ≈ kcal.
        Double kcal = a.kilojoules != null && a.kilojoules > 0 ? a.kilojoules : null;
        String title = a.name != null && !a.name.isEmpty() ? a.name : "Fietsrit";
        return new RideHealthEntry("climbpro-strava-" + a.id, title, start,
                start.plusSeconds(seconds), offset, "VirtualRide".equals(a.type),
                Math.max(0, a.distance), Math.max(0, a.totalElevationGain), kcal);
    }
}
