package nl.paree.climbpro.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;

/**
 * Centre of the radius mode (issue #310): the freshest cached fix, remembered in the
 * {@link RouteSyncWorker#PREF_LAST_LAT}/{@link RouteSyncWorker#PREF_LAST_LON} prefs so a
 * background sync without a fix of its own still has the last known position. Without any
 * known position this returns null, never 0,0. No active GPS request is made; call off the
 * main thread.
 */
public final class RadiusLocation {

    private RadiusLocation() {}

    /** Freshest cached fix (stored for later), else the stored one, else null. */
    public static double[] current(Context context, SharedPreferences prefs) {
        return resolve(freshest(context), prefs);
    }

    /** Stores {@code fresh} when present and returns it; otherwise the stored position. */
    static double[] resolve(Location fresh, SharedPreferences prefs) {
        if (fresh != null) {
            remember(prefs, fresh.getLatitude(), fresh.getLongitude());
            return new double[]{fresh.getLatitude(), fresh.getLongitude()};
        }
        return stored(prefs);
    }

    static void remember(SharedPreferences prefs, double lat, double lon) {
        prefs.edit()
                .putLong(RouteSyncWorker.PREF_LAST_LAT, Double.doubleToLongBits(lat))
                .putLong(RouteSyncWorker.PREF_LAST_LON, Double.doubleToLongBits(lon))
                .apply();
    }

    static double[] stored(SharedPreferences prefs) {
        if (!prefs.contains(RouteSyncWorker.PREF_LAST_LAT)
                || !prefs.contains(RouteSyncWorker.PREF_LAST_LON)) {
            return null;
        }
        return new double[]{
                Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LAT, 0)),
                Double.longBitsToDouble(prefs.getLong(RouteSyncWorker.PREF_LAST_LON, 0))};
    }

    @SuppressLint("MissingPermission")
    private static Location freshest(Context context) {
        Location best = null;
        try {
            LocationManager lm = (LocationManager) context.getApplicationContext()
                    .getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                for (String provider : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            }
        } catch (SecurityException e) {
            best = null;
        }
        return best;
    }
}
