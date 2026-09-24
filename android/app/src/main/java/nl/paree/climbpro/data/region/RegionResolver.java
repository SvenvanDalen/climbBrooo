package nl.paree.climbpro.data.region;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/** Country + province for a coordinate via Android's Geocoder, through {@link RegionCache} (issue #250). */
public final class RegionResolver {

    private static final String TAG = "RegionResolver";

    private final Geocoder geocoder;
    private final RegionCache cache;

    public RegionResolver(Context ctx, RegionCache cache) {
        this.geocoder = new Geocoder(ctx.getApplicationContext(), new Locale("nl"));
        this.cache = cache;
    }

    /** Blocking; call off the main thread. Null when unknown and not resolvable right now. */
    public RegionCache.Region resolve(double lat, double lon) {
        RegionCache.Region cached = cache.get(lat, lon);
        if (cached != null) return cached;
        if (!Geocoder.isPresent()) return null;
        try {
            @SuppressWarnings("deprecation") // sync overload; caller is off the main thread
            List<Address> results = geocoder.getFromLocation(lat, lon, 1);
            if (results == null || results.isEmpty()) return null;
            Address a = results.get(0);
            if (a.getCountryCode() == null || a.getCountryCode().trim().isEmpty()) return null;
            RegionCache.Region r = new RegionCache.Region();
            r.countryCode = a.getCountryCode();
            r.country = a.getCountryName() != null ? a.getCountryName() : a.getCountryCode();
            r.province = a.getAdminArea();
            cache.put(lat, lon, r);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "Reverse geocoding failed", e);
            return null;
        }
    }
}
