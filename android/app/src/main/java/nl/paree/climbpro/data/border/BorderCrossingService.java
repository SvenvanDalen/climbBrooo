package nl.paree.climbpro.data.border;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.border.BorderCrossing;
import nl.paree.climbpro.domain.border.BorderCrossingFinder;
import nl.paree.climbpro.domain.border.CountryInfo;
import nl.paree.climbpro.domain.border.CountryPolygons;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Border crossings along a stored route (issue #209), fully offline: the country lookup uses the
 * bundled coarse boundary polygons ({@link CountryPolygons#ASSET_PATH}), parsed once per process.
 * Computing is cheap (a few hundred point-in-polygon tests per 100 km), so nothing is persisted.
 */
public final class BorderCrossingService {

    private static final String TAG = "BorderCrossingService";
    private static volatile CountryPolygons polygons;

    private final Context appContext;

    public BorderCrossingService(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /**
     * Display lines for the route detail screen: the start country followed by one line per
     * crossing. Empty when the route never leaves its start country (or no country is known).
     * Blocking on first use (asset parse); call off the main thread.
     */
    public List<String> describe(StoredRoute route) {
        if (route == null) return Collections.emptyList();
        CountryPolygons p = polygons();
        if (p == null) return Collections.emptyList();
        BorderCrossingFinder.Result result = BorderCrossingFinder.find(
                route.lats, route.lons, route.distances, p::countryAt);
        if (result.crossings.isEmpty()) return Collections.emptyList();
        List<String> lines = new ArrayList<>(result.crossings.size() + 1);
        lines.add(CountryInfo.formatStart(result.startCountry));
        for (BorderCrossing c : result.crossings) lines.add(CountryInfo.formatCrossing(c));
        return lines;
    }

    private CountryPolygons polygons() {
        CountryPolygons p = polygons;
        if (p != null) return p;
        synchronized (BorderCrossingService.class) {
            if (polygons == null) {
                try (InputStream in = appContext.getAssets().open(CountryPolygons.ASSET_PATH)) {
                    polygons = CountryPolygons.parse(in);
                } catch (Exception e) {
                    Log.w(TAG, "Could not load border polygons", e);
                    return null;
                }
            }
            return polygons;
        }
    }
}
