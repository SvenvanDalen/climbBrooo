package nl.paree.climbpro.ui.planning;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

/**
 * Freshest cached fix from any enabled provider, for planning screens. No active GPS request
 * is made. Caller must hold a location permission; without it (or without any cached fix)
 * this returns null. Call off the main thread.
 */
final class LastKnownLocation {

    private LastKnownLocation() {}

    @SuppressLint("MissingPermission")
    static Location freshest(Context context) {
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
