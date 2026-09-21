package nl.paree.climbpro.data.route;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import java.util.List;
import java.util.Locale;

import nl.paree.climbpro.domain.climb.ClimbNameSuggester;

/**
 * Suggests a climb name from its start coordinate via Android's built-in reverse geocoder
 * (backlog #109). Runs synchronously — callers must already be off the main thread (this is
 * only ever invoked from the route import/sync pipeline, never during an active ride).
 *
 * Offline-first: any failure (no geocoder backend present, no network, no match) yields
 * {@code null} rather than throwing, so the caller falls back to the climb's generic name.
 */
final class GeocoderClimbNameSuggester implements ClimbNameSuggester {

    private static final String TAG = "GeocoderClimbNamer";

    private final Geocoder geocoder;

    GeocoderClimbNameSuggester(Context context) {
        this.geocoder = new Geocoder(context.getApplicationContext(), Locale.getDefault());
    }

    @Override
    public String suggestName(double lat, double lon) {
        if (!Geocoder.isPresent()) return null;
        try {
            @SuppressWarnings("deprecation") // sync overload; caller is already off the main thread
            List<Address> results = geocoder.getFromLocation(lat, lon, 1);
            if (results == null || results.isEmpty()) return null;

            Address address = results.get(0);
            String place = firstNonBlank(
                    address.getLocality(), address.getSubAdminArea(), address.getAdminArea());
            return place != null ? "Klim bij " + place : null;
        } catch (Exception e) {
            // IOException (no network/backend), or any geocoder implementation quirk.
            Log.w(TAG, "Reverse geocoding failed, falling back to generic climb name", e);
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
