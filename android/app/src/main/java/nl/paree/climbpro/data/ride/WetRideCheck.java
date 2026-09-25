package nl.paree.climbpro.data.ride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Weather verdict for one archived ride (issue #234 cleaning reminder). Its presence is the
 * idempotency record: a ride with a check is never fetched or notified again. Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WetRideCheck {
    /** Strava activity id, same key as StoredRide.activityId. */
    public long    activityId;
    /** When the weather was checked (epoch seconds). */
    public long    checkedAtSec;
    /** Precipitation summed over the ride window, mm. */
    public double  precipitationMm;
    /** True when a cleaning reminder was due (and shown, if notifications were allowed). */
    public boolean wet;
    /** Gravel/MTB ride — judged with the "modder" rule. */
    public boolean offroad;

    public WetRideCheck() {}
}
