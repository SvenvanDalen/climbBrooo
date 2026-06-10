package nl.paree.climbpro.data.rider;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.domain.power.RiderProfile;

/** Persists the rider's FTP, body weight and bike weight in default SharedPreferences. */
public final class RiderProfileRepository {

    public static final String PREF_FTP_WATTS      = "rider_ftp_watts";
    public static final String PREF_RIDER_WEIGHT_KG = "rider_weight_kg";
    public static final String PREF_BIKE_WEIGHT_KG  = "bike_weight_kg";

    private final SharedPreferences prefs;

    public RiderProfileRepository(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public RiderProfile load() {
        int ftp = prefs.getInt(PREF_FTP_WATTS, 0);
        double rider = prefs.getFloat(PREF_RIDER_WEIGHT_KG, 0f);
        double bike = prefs.getFloat(PREF_BIKE_WEIGHT_KG, 0f);
        return new RiderProfile(ftp, rider, bike);
    }

    public void save(RiderProfile profile) {
        prefs.edit()
                .putInt(PREF_FTP_WATTS, profile.ftpWatts)
                .putFloat(PREF_RIDER_WEIGHT_KG, (float) profile.riderWeightKg)
                .putFloat(PREF_BIKE_WEIGHT_KG, (float) profile.bikeWeightKg)
                .apply();
    }
}
