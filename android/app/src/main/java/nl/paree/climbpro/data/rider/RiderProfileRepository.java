package nl.paree.climbpro.data.rider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.power.RiderProfile;

/** Persists the rider's FTP, body weight and bike weight in default SharedPreferences. */
public final class RiderProfileRepository {

    public static final String PREF_FTP_WATTS        = "rider_ftp_watts";
    public static final String PREF_RIDER_WEIGHT_KG  = "rider_weight_kg";
    public static final String PREF_BIKE_WEIGHT_KG   = "bike_weight_kg";
    public static final String PREF_RIDE_INTENSITY_PCT = "rider_ride_intensity_pct";
    /** Max heart rate in bpm for heart-rate zones (issue #218); absent or 0 = not set. */
    public static final String PREF_MAX_HEART_RATE = "rider_max_heart_rate";

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
}
