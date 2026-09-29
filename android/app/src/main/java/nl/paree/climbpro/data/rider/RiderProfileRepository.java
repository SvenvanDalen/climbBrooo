package nl.paree.climbpro.data.rider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.power.GhostTarget;
import nl.paree.climbpro.domain.power.RiderProfile;

/** Persists the rider's FTP, body weight and bike weight in default SharedPreferences. */
public final class RiderProfileRepository {

    public static final String PREF_FTP_WATTS        = "rider_ftp_watts";
    public static final String PREF_RIDER_WEIGHT_KG  = "rider_weight_kg";
    public static final String PREF_BIKE_WEIGHT_KG   = "bike_weight_kg";
    public static final String PREF_RIDE_INTENSITY_PCT = "rider_ride_intensity_pct";
    /** Max heart rate in bpm for heart-rate zones (issue #218); absent or 0 = not set. */
    public static final String PREF_MAX_HEART_RATE = "rider_max_heart_rate";
    /** Virtual-ghost target speed in km/h for climbs without history (issue #31); absent = off. */
    public static final String PREF_GHOST_SPEED_KMH = "rider_ghost_speed_kmh";
    /** Virtual-ghost target VAM in m/h for climbs without history (issue #31); absent = off. */
    public static final String PREF_GHOST_VAM_M_PER_H = "rider_ghost_vam_m_per_h";

    private final SharedPreferences prefs;

    public RiderProfileRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public RiderProfile load() {
        int ftp = prefs.getInt(PREF_FTP_WATTS, 0);
        double rider = prefs.getFloat(PREF_RIDER_WEIGHT_KG, 0f);
        double bike = prefs.getFloat(PREF_BIKE_WEIGHT_KG, 0f);
        int intensity = prefs.getInt(PREF_RIDE_INTENSITY_PCT, RiderProfile.DEFAULT_RIDE_INTENSITY_PCT);
        return new RiderProfile(ftp, rider, bike, intensity);
    }

    public void save(RiderProfile profile) {
        prefs.edit()
                .putInt(PREF_FTP_WATTS, profile.ftpWatts)
                .putFloat(PREF_RIDER_WEIGHT_KG, (float) profile.riderWeightKg)
                .putFloat(PREF_BIKE_WEIGHT_KG, (float) profile.bikeWeightKg)
                .putInt(PREF_RIDE_INTENSITY_PCT, profile.rideIntensityPct)
                .apply();
    }

    /** Max heart rate in bpm, 0 when the rider hasn't set one. */
    public int loadMaxHeartRate() {
        return prefs.getInt(PREF_MAX_HEART_RATE, 0);
    }

    /** Stores the max heart rate; 0 or less clears it. */
    public void saveMaxHeartRate(int bpm) {
        if (bpm > 0) {
            prefs.edit().putInt(PREF_MAX_HEART_RATE, bpm).apply();
        } else {
            prefs.edit().remove(PREF_MAX_HEART_RATE).apply();
        }
    }

    /** The virtual-ghost target (issue #31); {@link GhostTarget#isSet()} false when unset. */
    public GhostTarget loadGhostTarget() {
        return new GhostTarget(prefs.getFloat(PREF_GHOST_SPEED_KMH, 0f),
                prefs.getInt(PREF_GHOST_VAM_M_PER_H, 0));
    }

    /** Stores the virtual-ghost target; an unset speed or VAM removes that key. */
    public void saveGhostTarget(GhostTarget target) {
        SharedPreferences.Editor ed = prefs.edit();
        if (target != null && target.hasSpeed()) {
            ed.putFloat(PREF_GHOST_SPEED_KMH, (float) target.speedKmh);
        } else {
            ed.remove(PREF_GHOST_SPEED_KMH);
        }
        if (target != null && target.hasVam()) {
            ed.putInt(PREF_GHOST_VAM_M_PER_H, target.vamMPerH);
        } else {
            ed.remove(PREF_GHOST_VAM_M_PER_H);
        }
        ed.apply();
    }
}
