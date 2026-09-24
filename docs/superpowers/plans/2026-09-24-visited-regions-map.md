# Visited Countries & Provinces Map (issue #250) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Follow superpowers:test-driven-development for every code step.

**Goal:** A screen showing the countries and provinces where you have climbed: a map with one marker per province plus a list grouped by country with counts.

**Architecture:** "Where you cycled" comes from the stored climb attempts, placed at their climb's start coordinate. `main` has no ride archive yet (#160), so attempts are the ride evidence available. Each coordinate is reverse-geocoded to (country, province) with Android's `Geocoder`, the same approach as `GeocoderClimbNameSuggester`. Results are cached in `getFilesDir()/region_cache.json` on a ~1 km grid, so the screen works offline after the first visit and doesn't geocode the same climb twice. A pure `VisitedRegions` aggregates visits into country and province summaries. `VisitedRegionsActivity` shows an osmdroid map with markers and the grouped list.

**Why markers instead of coloured regions:** colouring country or province polygons needs boundary data in the APK (several MB of GeoJSON) or a tile service. Markers answer "where have I ridden?" now; polygon shading can follow if wanted. Say this in the PR.

**Tech Stack:** Java 17 (Android, minSdk 26, compileSdk 34), osmdroid 6.1.18 (already a dependency), Jackson 2.17, JUnit 4.

**Spec:** GitHub issue #250 ("Toon een kaart die landen, provincies of gemeenten inkleurt waar je gereden hebt. Leuk overzicht op grotere schaal dan de wegenkaart (#194). Telefoon-only, start-/eindpunten of sporen tegen regiogrenzen. Geen wire-format wijziging.")

## Global Constraints

- Phone-only: no changes under `protocol/`, `garmin*/`, or to `ClimbPayloadBuilder`.
- Offline-first: cached regions show without network; geocoding failures are skipped and retried next time (never cached as "unknown").
- Geocoding runs off the main thread only.
- Java, not Kotlin. minSdk 26 without desugaring: do NOT use `List.of`/`Set.of`/`Map.of`.
- Dutch UI text; geocoder locale `new Locale("nl")` so names read "Nederland", "België".
- Build/test (from `android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug -Djavax.net.ssl.trustStoreType=Windows-ROOT --console=plain`
- Branch from `origin/main` as `feat/visited-regions-map`; PR ends with `Closes #250` + Claude Code footer; commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- Geocoder returns a country but no admin area (sea, border, rural) → counted under the country as "(regio onbekend)", not dropped.
- Negative coordinates (west of Greenwich / southern hemisphere) → cache keys stay distinct and stable (`-0.004` and `0.004` must not collide into one "0" cell unless both round to 0).
- Corrupt or missing `region_cache.json` → starts empty, never crashes the screen.
- The same climb attempted 50 times → geocoded once (cache) and counted 50 visits.
- No attempts at all → an empty state message, no map crash.
(Pinned by tests in Tasks 1–2; the empty state is handled in Task 3.)

---

### Task 1: `VisitedRegions` aggregation

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/region/VisitedRegions.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/region/VisitedRegionsTest.java`

**Interfaces:**
- Produces:
  - `public static final class Visit { public Visit(String countryCode, String country, String province, double lat, double lon) }`
  - `public static final class Province { public final String name; public final int visits; public final double lat, lon; }` (lat/lon = mean of its visits)
  - `public static final class Country { public final String code, name; public final int visits; public final List<Province> provinces; }`
  - `public static List<Country> aggregate(List<Visit> visits)`: countries sorted by visits desc then name; provinces likewise; a null or blank province becomes `VisitedRegions.UNKNOWN_PROVINCE` = `"(regio onbekend)"`.

- [ ] **Step 1: Failing tests**

```java
package nl.paree.climbpro.domain.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VisitedRegionsTest {

    private static VisitedRegions.Visit v(String cc, String country, String prov, double lat, double lon) {
        return new VisitedRegions.Visit(cc, country, prov, lat, lon);
    }

    @Test public void groupsByCountryThenProvinceMostVisitedFirst() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("BE", "België", "Luik", 50.5, 5.8),
                v("NL", "Nederland", "Limburg", 50.8, 5.8),
                v("NL", "Nederland", "Limburg", 50.9, 6.0),
                v("NL", "Nederland", "Gelderland", 52.0, 5.9)));

        assertEquals(2, out.size());
        assertEquals("Nederland", out.get(0).name);
        assertEquals(3, out.get(0).visits);
        assertEquals("Limburg", out.get(0).provinces.get(0).name);
        assertEquals(2, out.get(0).provinces.get(0).visits);
        assertEquals(50.85, out.get(0).provinces.get(0).lat, 1e-9);
        assertEquals(5.9, out.get(0).provinces.get(0).lon, 1e-9);
        assertEquals("Gelderland", out.get(0).provinces.get(1).name);
        assertEquals("België", out.get(1).name);
    }

    @Test public void tiesAreAlphabetical() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("FR", "Frankrijk", "Vaucluse", 44.1, 5.2),
                v("BE", "België", "Luik", 50.5, 5.8)));
        assertEquals("België", out.get(0).name);
    }

    @Test public void missingProvinceIsUnknownRegion() {
        List<VisitedRegions.Country> out = VisitedRegions.aggregate(Arrays.asList(
                v("NL", "Nederland", null, 53.0, 4.0), v("NL", "Nederland", " ", 53.1, 4.1)));
        assertEquals(1, out.get(0).provinces.size());
        assertEquals(VisitedRegions.UNKNOWN_PROVINCE, out.get(0).provinces.get(0).name);
        assertEquals(2, out.get(0).provinces.get(0).visits);
    }

    @Test public void emptyInputGivesEmptyOutput() {
        assertTrue(VisitedRegions.aggregate(Collections.emptyList()).isEmpty());
    }
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.domain.region.VisitedRegionsTest" -Djavax.net.ssl.trustStoreType=Windows-ROOT` → compile error.
- [ ] **Step 3: Implement**

```java
package nl.paree.climbpro.domain.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Countries and provinces where the rider climbed, with visit counts (issue #250). Pure. */
public final class VisitedRegions {

    public static final String UNKNOWN_PROVINCE = "(regio onbekend)";

    public static final class Visit {
        final String countryCode, country, province;
        final double lat, lon;

        public Visit(String countryCode, String country, String province, double lat, double lon) {
            this.countryCode = countryCode;
            this.country = country;
            this.province = province;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Province {
        public final String name;
        public final int visits;
        public final double lat, lon;

        Province(String name, int visits, double lat, double lon) {
            this.name = name; this.visits = visits; this.lat = lat; this.lon = lon;
        }
    }

    public static final class Country {
        public final String code, name;
        public final int visits;
        public final List<Province> provinces;

        Country(String code, String name, int visits, List<Province> provinces) {
            this.code = code; this.name = name; this.visits = visits; this.provinces = provinces;
        }
    }

    private VisitedRegions() {}

    public static List<Country> aggregate(List<Visit> visits) {
        Map<String, Map<String, List<Visit>>> byCountry = new LinkedHashMap<>();
        Map<String, Visit> firstOfCountry = new LinkedHashMap<>();
        for (Visit v : visits) {
            String prov = v.province == null || v.province.trim().isEmpty()
                    ? UNKNOWN_PROVINCE : v.province.trim();
            byCountry.computeIfAbsent(v.countryCode, k -> new LinkedHashMap<>())
                    .computeIfAbsent(prov, k -> new ArrayList<>()).add(v);
            firstOfCountry.putIfAbsent(v.countryCode, v);
        }
        List<Country> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Visit>>> c : byCountry.entrySet()) {
            List<Province> provinces = new ArrayList<>();
            int total = 0;
            for (Map.Entry<String, List<Visit>> p : c.getValue().entrySet()) {
                double lat = 0, lon = 0;
                for (Visit v : p.getValue()) { lat += v.lat; lon += v.lon; }
                int n = p.getValue().size();
                provinces.add(new Province(p.getKey(), n, lat / n, lon / n));
                total += n;
            }
            provinces.sort(Comparator.comparingInt((Province p) -> -p.visits).thenComparing(p -> p.name));
            out.add(new Country(c.getKey(), firstOfCountry.get(c.getKey()).country, total,
                    Collections.unmodifiableList(provinces)));
        }
        out.sort(Comparator.comparingInt((Country c) -> -c.visits).thenComparing(c -> c.name));
        return out;
    }
}
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit** — `feat(android): visited regions aggregation (#250)`

### Task 2: `RegionCache` (+ Geocoder lookup)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/region/RegionCache.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/data/region/RegionResolver.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/region/RegionCacheTest.java`

**Interfaces:**
- Produces:
  - `RegionCache(File file)`; `static String key(double lat, double lon)` = `Math.round(lat * 100) + ":" + Math.round(lon * 100)`; `Region get(double lat, double lon)` (null when absent); `void put(double lat, double lon, Region r)`; `void save() throws IOException` (atomic temp-file + rename).
  - `public static final class Region { public String countryCode, country, province; }` (public no-arg constructor, Jackson-serialisable, `@JsonIgnoreProperties(ignoreUnknown = true)`).
  - `RegionResolver(Context ctx, RegionCache cache)`; `Region resolve(double lat, double lon)` → cache hit, else `Geocoder(ctx, new Locale("nl")).getFromLocation(lat, lon, 1)`, mapping `getCountryCode()`, `getCountryName()`, `getAdminArea()`. Caches only non-null results with a non-blank country code. Returns null on failure (no network, `!Geocoder.isPresent()`, exception).

- [ ] **Step 1: Failing tests**

```java
package nl.paree.climbpro.data.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class RegionCacheTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static RegionCache.Region region(String cc, String country, String prov) {
        RegionCache.Region r = new RegionCache.Region();
        r.countryCode = cc; r.country = country; r.province = prov;
        return r;
    }

    @Test public void keyIsAboutOneKilometreAndSignAware() {
        assertEquals("5085:569", RegionCache.key(50.8512, 5.6904));
        assertEquals(RegionCache.key(50.8512, 5.6904), RegionCache.key(50.8488, 5.6949));
        assertEquals("-3386:-626", RegionCache.key(-33.861, -6.2649));
        assertNotEquals(RegionCache.key(0.006, 0.006), RegionCache.key(-0.006, -0.006));
    }

    @Test public void roundTripsThroughTheFile() throws Exception {
        File f = new File(tmp.getRoot(), "region_cache.json");
        RegionCache cache = new RegionCache(f);
        cache.put(50.85, 5.69, region("NL", "Nederland", "Limburg"));
        cache.save();

        RegionCache.Region r = new RegionCache(f).get(50.8512, 5.6904);
        assertEquals("NL", r.countryCode);
        assertEquals("Limburg", r.province);
        assertNull(new RegionCache(f).get(52.0, 4.0));
    }

    @Test public void corruptOrMissingFileStartsEmpty() throws Exception {
        File missing = new File(tmp.getRoot(), "nope.json");
        assertNull(new RegionCache(missing).get(50.85, 5.69));
        File corrupt = tmp.newFile("bad.json");
        Files.write(corrupt.toPath(), "{niet json".getBytes(StandardCharsets.UTF_8));
        assertNull(new RegionCache(corrupt).get(50.85, 5.69));
    }
}
```

- [ ] **Step 2: Run** → compile error.
- [ ] **Step 3: Implement `RegionCache`**

```java
package nl.paree.climbpro.data.region;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Reverse-geocoding cache for the visited-regions map (issue #250), stored as
 * {@code getFilesDir()/region_cache.json} on a ~1 km grid (0.01°). Keeps the screen offline-
 * capable and avoids geocoding the same climb again.
 */
public final class RegionCache {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Region {
        public String countryCode;
        public String country;
        public String province;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;
    private final Map<String, Region> entries;

    public RegionCache(File file) {
        this.file = file;
        Map<String, Region> loaded = null;
        if (file.exists()) {
            try {
                loaded = MAPPER.readValue(file, new TypeReference<Map<String, Region>>() {});
            } catch (IOException ignored) {
                // corrupt cache: start over, it is only a cache
            }
        }
        this.entries = loaded != null ? loaded : new HashMap<>();
    }

    public static String key(double lat, double lon) {
        return Math.round(lat * 100) + ":" + Math.round(lon * 100);
    }

    public Region get(double lat, double lon) { return entries.get(key(lat, lon)); }

    public void put(double lat, double lon, Region r) { entries.put(key(lat, lon), r); }

    public void save() throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(MAPPER.writeValueAsBytes(entries));
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
```

Check the key test by hand: `Math.round(-33.861 * 100)` = `Math.round(-3386.1)` = `-3386`; `Math.round(-6.2649 * 100)` = `Math.round(-626.49)` = `-626`; `0.006·100 = 0.6 → 1`, `-0.6 → -1`, so `"1:1"` ≠ `"-1:-1"`. `50.8488·100 = 5084.88 → 5085`, `5.6949·100 = 569.49 → 569`, so both points map to `"5085:569"`.

- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Implement `RegionResolver`** (Android-only glue, no unit test: `Geocoder` has no JVM backend)

```java
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
```

- [ ] **Step 6: Commit** — `feat(android): region cache and geocoder resolver (#250)`

### Task 3: `VisitedRegionsActivity` + menu entry

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/regions/VisitedRegionsActivity.java`
- Create: `android/app/src/main/res/layout/activity_visited_regions.xml`
- Modify: `android/app/src/main/AndroidManifest.xml` (register the activity, `exported="false"`, parent `.ui.routes.RouteListActivity`, next to the other activities)
- Modify: `android/app/src/main/res/menu/route_list_menu.xml` (item `@+id/action_visited_regions`, title `Landen & provincies`, `app:showAsAction="never"`, placed after `action_wrapped`)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java` (`onOptionsItemSelected`: start the activity)

**Interfaces:**
- Consumes: `VisitedRegions.aggregate`, `RegionCache`, `RegionResolver`, `ClimbAttemptRepository.loadAll()`, `RouteRepository.loadCatalog()/loadRoute()`, `ClimbIdentity.of(StoredClimb)`.

- [ ] **Step 1: Layout** `activity_visited_regions.xml`: vertical `LinearLayout` (background `@color/color_bg`) with a `Toolbar` `@+id/toolbar` (title "Landen & provincies"); a `TextView` `@+id/regions_summary` (padding 16dp, `@color/color_text_primary`, 16sp bold); an `org.osmdroid.views.MapView` `@+id/regions_map` (match_parent × 280dp); a `ScrollView` (height 0dp, weight 1) containing a vertical `LinearLayout` `@+id/regions_list` (padding 16dp).

- [ ] **Step 2: Activity**

```java
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
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        map = findViewById(R.id.regions_map);
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
                if (r.climbs == null) continue;
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
        int unresolved = 0;
        for (StoredClimbAttempt a : new ClimbAttemptRepository(this).loadAll()) {
            double[] p = startById.get(a.climbId);
            if (p == null) continue;
            RegionCache.Region r = resolver.resolve(p[0], p[1]);
            if (r == null) { unresolved++; continue; }
            visits.add(new VisitedRegions.Visit(r.countryCode, r.country, r.province, p[0], p[1]));
        }
        try { cache.save(); } catch (Exception ignored) { /* cache only */ }
        List<VisitedRegions.Country> countries = VisitedRegions.aggregate(visits);
        int missing = unresolved;
        runOnUiThread(() -> render(countries, missing));
    }

    private void render(List<VisitedRegions.Country> countries, int unresolved) {
        TextView summary = findViewById(R.id.regions_summary);
        LinearLayout list = findViewById(R.id.regions_list);
        list.removeAllViews();
        if (countries.isEmpty()) {
            summary.setText(unresolved > 0
                    ? "Regio's konden niet worden opgezocht. Controleer je verbinding en probeer het opnieuw."
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
```

- [ ] **Step 3:** Register the activity in the manifest, add the menu item, and in `RouteListActivity.onOptionsItemSelected` add before the `action_settings` branch:

```java
} else if (id == R.id.action_visited_regions) {
    startActivity(nl.paree.climbpro.ui.regions.VisitedRegionsActivity.intentFor(this));
    return true;
```

- [ ] **Step 4:** Run the full build/test command → green (the activity has no unit test; its logic lives in the tested classes).
- [ ] **Step 5: Commit + PR** — `feat(android): map of countries and provinces where you climbed (#250)`; PR explains "markers instead of coloured polygons" and "attempts instead of rides until the archive lands"; device checks: online first open, second open in airplane mode (from cache), no attempts (empty state).
