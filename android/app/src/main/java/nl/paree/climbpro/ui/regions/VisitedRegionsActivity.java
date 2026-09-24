package nl.paree.climbpro.ui.regions;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.region.RegionCache;
import nl.paree.climbpro.data.region.RegionResolver;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.region.VisitedRegions;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Countries and provinces where you climbed (issue #250): one marker per province on an
 * osmdroid map plus a list grouped by country. Visits are climb attempts, placed at their
 * climb's start; regions come from the geocoder via a file cache. Phone-only.
 */
public final class VisitedRegionsActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MapView map;

    public static Intent intentFor(Context ctx) {
        return new Intent(ctx, VisitedRegionsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_visited_regions);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Landen & provincies");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        map = findViewById(R.id.regions_map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(5.0);
        map.getController().setCenter(new GeoPoint(50.8, 5.7));

        TextView summary = findViewById(R.id.regions_summary);
        summary.setText("Regio's opzoeken…");
        executor.execute(this::load);
    }

    private void load() {
        Map<String, double[]> startById = new HashMap<>();
        RouteRepository routes = new RouteRepository(this);
        for (RouteCatalogEntry e : routes.loadCatalog()) {
            try {
                StoredRoute r = routes.loadRoute(e.routeId);
                if (r == null || r.climbs == null) continue;
                for (StoredClimb c : r.climbs) {
                    startById.putIfAbsent(ClimbIdentity.of(c), new double[]{c.startLat, c.startLon});
                }
            } catch (Exception ignored) {
                // an unreadable route just contributes no coordinates
            }
        }
        RegionCache cache = new RegionCache(new File(getFilesDir(), "region_cache.json"));
        RegionResolver resolver = new RegionResolver(this, cache);
        List<VisitedRegions.Visit> visits = new ArrayList<>();
        // Resolve each climb once per load: failures are not cached on disk, so without this
        // an offline open would hit the geocoder (and its timeout) for every single attempt.
        Map<String, RegionCache.Region> byClimb = new HashMap<>();
        int unresolved = 0;
        int attempts = 0;
        for (StoredClimbAttempt a : new ClimbAttemptRepository(this).loadAll()) {
            attempts++;
            double[] p = startById.get(a.climbId);
            if (p == null || (p[0] == 0 && p[1] == 0)) continue; // no usable start coordinate
            RegionCache.Region r;
            if (byClimb.containsKey(a.climbId)) {
                r = byClimb.get(a.climbId);
            } else {
                r = resolver.resolve(p[0], p[1]);
                byClimb.put(a.climbId, r);
            }
            if (r == null) { unresolved++; continue; }
            visits.add(new VisitedRegions.Visit(r.countryCode, r.country, r.province, p[0], p[1]));
        }
        boolean hadAttempts = attempts > 0;
        try { cache.save(); } catch (Exception ignored) { /* cache only */ }
        List<VisitedRegions.Country> countries = VisitedRegions.aggregate(visits);
        int missing = unresolved;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            render(countries, missing, hadAttempts);
        });
    }

    private void render(List<VisitedRegions.Country> countries, int unresolved, boolean hadAttempts) {
        TextView summary = findViewById(R.id.regions_summary);
        LinearLayout list = findViewById(R.id.regions_list);
        list.removeAllViews();
        if (countries.isEmpty()) {
            summary.setText(unresolved > 0
                    ? "Regio's konden niet worden opgezocht. Controleer je verbinding en probeer het opnieuw."
                    : hadAttempts
                            ? "Geen van je klimpogingen hoort bij een klim met een bekende locatie."
                            : "Nog geen klimpogingen. Haal je ritten op in het logboek.");
            return;
        }
        int provinceCount = 0;
        List<GeoPoint> points = new ArrayList<>();
        for (VisitedRegions.Country c : countries) {
            list.addView(row(c.name + " · " + c.visits, true));
            for (VisitedRegions.Province p : c.provinces) {
                provinceCount++;
                list.addView(row("   " + p.name + " · " + p.visits, false));
                GeoPoint gp = new GeoPoint(p.lat, p.lon);
                points.add(gp);
                Marker m = new Marker(map);
                m.setPosition(gp);
                m.setTitle(p.name + ", " + c.name);
                m.setSnippet(p.visits + " klimpoging(en)");
                map.getOverlays().add(m);
            }
        }
        summary.setText(countries.size() + (countries.size() == 1 ? " land" : " landen") + " · "
                + provinceCount + (provinceCount == 1 ? " provincie" : " provincies")
                + (unresolved > 0 ? "\n" + unresolved + " poging(en) nog niet opgezocht (offline?)" : ""));
        if (points.size() == 1) {
            map.getController().setZoom(9.0);
            map.getController().setCenter(points.get(0));
        } else {
            BoundingBox box = BoundingBox.fromGeoPoints(points);
            map.post(() -> map.zoomToBoundingBox(box.increaseByScale(1.3f), false));
        }
        map.invalidate();
    }

    private TextView row(String text, boolean country) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(country ? 17 : 15);
        t.setTextColor(ContextCompat.getColor(this, R.color.color_text_primary));
        if (country) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        }
        return t;
    }

    @Override protected void onResume() { super.onResume(); map.onResume(); }

    @Override protected void onPause() { super.onPause(); map.onPause(); }

    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
