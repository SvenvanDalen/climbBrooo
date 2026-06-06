# Map + All Segments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show all climb segments in the list, add an OSM map to RouteDetailActivity (full route) and ClimbDetailActivity (climb highlighted on route).

**Architecture:** Replace `ScrollView` → `NestedScrollView` to fix RecyclerView measurement. Add OSMDroid for map tiles with polyline overlays. `ClimbDetailViewModel` already loads the full `StoredRoute`; expose it via a new `route()` LiveData so the activity can extract climb-segment points by distance bounds.

**Tech Stack:** OSMDroid 6.1.18, `androidx.core.widget.NestedScrollView`, existing MVVM + LiveData pattern, Java 17.

---

## File map

| File | Change |
|---|---|
| `android/app/build.gradle` | Add OSMDroid dependency |
| `android/app/src/main/AndroidManifest.xml` | Add `android:name=".ClimbProApplication"` |
| `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java` | **Create** — OSMDroid init |
| `android/app/src/main/res/layout/activity_climb_detail.xml` | `ScrollView` → `NestedScrollView`, add `MapView` |
| `android/app/src/main/res/layout/activity_route_detail.xml` | `ScrollView` → `NestedScrollView`, add `MapView` |
| `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java` | Add `route()` LiveData |
| `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` | Observe `route()`, draw climb map |
| `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java` | Draw route map |
| `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModelTest.java` | **Create** — verify `route()` LiveData is populated |

---

## Task 1: Fix segments list — ScrollView → NestedScrollView

**Root cause:** `RecyclerView` inside a plain `ScrollView` with `wrap_content` has a known Android measurement bug — it only renders the items visible in the first layout pass (typically ~5). Switching to `NestedScrollView` fixes this reliably; `nestedScrollingEnabled="false"` is already set on the `RecyclerView` in both layouts.

**Files:**
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml`
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`

- [ ] **Step 1: Replace ScrollView in activity_climb_detail.xml**

In `activity_climb_detail.xml`, replace the opening `<ScrollView` tag and its closing `</ScrollView>` with `androidx.core.widget.NestedScrollView`. Keep all attributes:

```xml
<androidx.core.widget.NestedScrollView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:layout_behavior="@string/appbar_scrolling_view_behavior">

    <!-- existing LinearLayout content unchanged -->

</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 2: Replace ScrollView in activity_route_detail.xml**

Same replacement in `activity_route_detail.xml`:

```xml
<androidx.core.widget.NestedScrollView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:backgroundTint="#02FCCA"
    app:layout_behavior="@string/appbar_scrolling_view_behavior">

    <!-- existing LinearLayout content unchanged -->

</androidx.core.widget.NestedScrollView>
```

- [ ] **Step 3: Build and verify**

```
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Then install and open a climb with 16 segments — all 16 should now be visible when scrolling down.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/layout/activity_climb_detail.xml
git add android/app/src/main/res/layout/activity_route_detail.xml
git commit -m "fix: replace ScrollView with NestedScrollView to show all segments"
```

---

## Task 2: Add OSMDroid dependency and Application class

**Files:**
- Modify: `android/app/build.gradle`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`

Note: `INTERNET` and `ACCESS_NETWORK_STATE` are already declared in the manifest — no new permissions needed.

- [ ] **Step 1: Add OSMDroid to build.gradle**

In `android/app/build.gradle`, inside the `dependencies { }` block, add after the existing RecyclerView line:

```groovy
// Map
implementation 'org.osmdroid:osmdroid-android:6.1.18'
```

- [ ] **Step 2: Create ClimbProApplication.java**

Create `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`:

```java
package nl.paree.climbpro;

import android.app.Application;
import org.osmdroid.config.Configuration;
import java.io.File;

public final class ClimbProApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().setUserAgentValue("ClimbPro/1.0");
        Configuration.getInstance().setOsmdroidTileCache(
                new File(getCacheDir(), "osmdroid"));
    }
}
```

- [ ] **Step 3: Register Application class in AndroidManifest.xml**

In `android/app/src/main/AndroidManifest.xml`, add `android:name=".ClimbProApplication"` to the `<application>` tag:

```xml
<application
    android:name=".ClimbProApplication"
    android:allowBackup="false"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:theme="@style/Theme.ClimbPro">
```

- [ ] **Step 4: Build**

```
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/build.gradle
git add android/app/src/main/AndroidManifest.xml
git add android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "feat: add OSMDroid dependency and Application init"
```

---

## Task 3: Route map in RouteDetailActivity

Shows the full route as a blue polyline on an OSM map at the top of the route detail screen.

**Files:**
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`

- [ ] **Step 1: Add MapView to activity_route_detail.xml**

Inside the `<LinearLayout>` (the direct child of `NestedScrollView`), add a `MapView` as the **first** child, before the "Notes" `TextView`:

```xml
<org.osmdroid.views.MapView
    android:id="@+id/map_view"
    android:layout_width="match_parent"
    android:layout_height="250dp"
    android:layout_marginBottom="12dp"/>
```

- [ ] **Step 2: Configure MapView and draw route in RouteDetailActivity.java**

Replace the entire `RouteDetailActivity.java` content with the version below. The only additions are: (a) map setup in `onCreate`, (b) `drawRoute()` called from the existing `route()` observer, (c) `onResume`/`onPause` lifecycle forwarding.

```java
package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.databinding.ActivityRouteDetailBinding;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;
import nl.paree.climbpro.ui.climbs.ClimbListAdapter;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";

    private ActivityRouteDetailBinding binding;
    private RouteDetailViewModel       viewModel;
    private ClimbListAdapter           adapter;
    private String                     routeId;

    public static Intent intentFor(Context ctx, String routeId) {
        Intent i = new Intent(ctx, RouteDetailActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding   = ActivityRouteDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);
        binding.mapView.setMultiTouchControls(true);
        binding.mapView.getController().setZoom(13.0);

        routeId   = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        viewModel = new ViewModelProvider(this).get(RouteDetailViewModel.class);
        adapter   = new ClimbListAdapter();

        binding.climbsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.climbsRecycler.setAdapter(adapter);

        adapter.setListener((climb, index) ->
                startActivity(ClimbDetailActivity.intentFor(this, routeId, index)));

        viewModel.route().observe(this, route -> {
            if (route == null) return;
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            binding.toolbar.setTitle(name != null ? name : route.routeId);
            binding.notesEdit.setText(route.notes != null ? route.notes : "");
            adapter.setItems(route.climbs);
            drawRoute(route);
        });

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });

        binding.btnRename.setOnClickListener(v -> showRenameDialog());
        binding.btnSaveNotes.setOnClickListener(v ->
                viewModel.saveNotes(routeId, binding.notesEdit.getText().toString()));
        binding.btnSelectRoute.setOnClickListener(v -> {
            viewModel.setActiveRoute(routeId);
            Toast.makeText(this, "Route selected for watch", Toast.LENGTH_SHORT).show();
        });
        binding.btnShareToGarmin.setOnClickListener(v -> shareToGarminConnect());

        viewModel.loadRoute(routeId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.mapView.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void drawRoute(StoredRoute route) {
        if (route.lats == null || route.lats.length == 0) return;

        List<GeoPoint> points = new ArrayList<>(route.lats.length);
        for (int i = 0; i < route.lats.length; i++) {
            points.add(new GeoPoint(route.lats[i], route.lons[i]));
        }

        Polyline polyline = new Polyline();
        polyline.setColor(Color.BLUE);
        polyline.setWidth(5f);
        polyline.setPoints(points);

        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(polyline);

        BoundingBox box = BoundingBox.fromGeoPoints(points);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 50));
        binding.mapView.invalidate();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint("New route name");
        input.setText(binding.toolbar.getTitle());
        new AlertDialog.Builder(this)
                .setTitle("Rename route")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        viewModel.renameRoute(routeId, input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareToGarminConnect() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/gpx+xml");
        share.putExtra(Intent.EXTRA_SUBJECT, "ClimbPro route");
        share.setPackage("com.garmin.android.apps.connectmobile");
        if (getPackageManager().resolveActivity(share, 0) == null) {
            share.setPackage(null);
        }
        startActivity(Intent.createChooser(share, "Open in Garmin Connect"));
    }
}
```

- [ ] **Step 3: Build**

```
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/layout/activity_route_detail.xml
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java
git commit -m "feat: add OSM route map to RouteDetailActivity"
```

---

## Task 4: Expose route in ClimbDetailViewModel (test-driven)

`ClimbDetailViewModel.loadClimb()` already loads `StoredRoute` but only posts `StoredClimb`. Add a `route()` LiveData so `ClimbDetailActivity` can get the full route coordinates for the map.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModelTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModelTest.java`:

```java
package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
public class ClimbDetailViewModelTest {

    @Test
    public void loadClimb_postsRoute() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{51.0, 51.001};
        route.lons = new double[]{5.0, 5.001};
        route.distances = new double[]{0, 100};
        route.elevations = new double[]{100, 110};
        StoredClimb climb = new StoredClimb();
        climb.startDistance = 0;
        climb.endDistance = 100;
        climb.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(climb);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        CountDownLatch latch = new CountDownLatch(1);
        final StoredRoute[] received = {null};
        vm.route().observeForever(r -> {
            received[0] = r;
            latch.countDown();
        });

        vm.loadClimb("r1", 0);
        latch.await(2, TimeUnit.SECONDS);

        assertNotNull(received[0]);
        assertEquals("r1", received[0].routeId);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbDetailViewModelTest -i
```

Expected: FAILED — `ClimbDetailViewModel` has no `route()` method.

- [ ] **Step 3: Add route() LiveData to ClimbDetailViewModel**

Replace `ClimbDetailViewModel.java` with:

```java
package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredClimb> climb = new MutableLiveData<>();
    private final MutableLiveData<StoredRoute> route = new MutableLiveData<>();
    private final MutableLiveData<String>      error = new MutableLiveData<>();
    private final MutableLiveData<Boolean>     saved = new MutableLiveData<>(false);

    public ClimbDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredClimb> climb() { return climb; }
    public LiveData<StoredRoute> route() { return route; }
    public LiveData<String>      error() { return error; }
    public LiveData<Boolean>     saved() { return saved; }

    public void loadClimb(String routeId, int climbIndex) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                if (r.climbs != null && climbIndex < r.climbs.size()) {
                    climb.postValue(r.climbs.get(climbIndex));
                } else {
                    error.postValue("Climb not found");
                }
            } catch (Exception e) {
                error.postValue("Load failed: " + e.getMessage());
            }
        });
    }

    public void renameClimb(String routeId, int climbIndex, String newName) {
        executor.execute(() -> {
            try {
                routeRepo.renameClimb(routeId, climbIndex, newName);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Rename failed: " + e.getMessage());
            }
        });
    }

    public void reSegment(String routeId, int climbIndex, int newSegmentCount) {
        executor.execute(() -> {
            try {
                routeRepo.reSegmentClimb(routeId, climbIndex, newSegmentCount);
                loadClimb(routeId, climbIndex);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Herberekening mislukt: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbDetailViewModelTest -i
```

Expected: PASSED.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java
git add android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModelTest.java
git commit -m "feat: expose route() LiveData in ClimbDetailViewModel"
```

---

## Task 5: Climb map in ClimbDetailActivity

Shows the full route as a gray polyline with the climb segment highlighted in orange on top. Both `route()` and `climb()` LiveData must arrive before the map is drawn; a simple null-guard pattern handles the asynchronous ordering.

**Files:**
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`

- [ ] **Step 1: Add MapView to activity_climb_detail.xml**

Inside the `<LinearLayout>`, add a `MapView` directly after `<nl.paree.climbpro.ui.climbs.ClimbProfileView .../>` and before `<Button android:id="@+id/btn_rename_climb"`:

```xml
<org.osmdroid.views.MapView
    android:id="@+id/map_view"
    android:layout_width="match_parent"
    android:layout_height="250dp"
    android:layout_marginBottom="16dp"/>
```

- [ ] **Step 2: Update ClimbDetailActivity.java**

Replace `ClimbDetailActivity.java` content with:

```java
package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.databinding.ActivityClimbDetailBinding;

import java.util.ArrayList;
import java.util.List;

public final class ClimbDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID   = "route_id";
    private static final String EXTRA_CLIMB_INDEX = "climb_index";

    private ActivityClimbDetailBinding binding;
    private ClimbDetailViewModel viewModel;
    private ClimbSegmentAdapter  adapter;
    private String routeId;
    private int    climbIndex;

    private StoredRoute loadedRoute;
    private StoredClimb loadedClimb;

    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        Intent i = new Intent(ctx, ClimbDetailActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        i.putExtra(EXTRA_CLIMB_INDEX, climbIndex);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityClimbDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);
        binding.mapView.setMultiTouchControls(true);
        binding.mapView.getController().setZoom(14.0);

        routeId    = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        climbIndex = getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0);

        viewModel = new ViewModelProvider(this).get(ClimbDetailViewModel.class);
        adapter   = new ClimbSegmentAdapter();

        binding.segmentsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.segmentsRecycler.setAdapter(adapter);

        viewModel.route().observe(this, route -> {
            loadedRoute = route;
            tryDrawMap();
        });

        viewModel.climb().observe(this, climb -> {
            if (climb == null) return;

            String name = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
            binding.toolbar.setTitle(name != null ? name : "Climb " + (climbIndex + 1));
            binding.climbStats.setText(String.format(
                    "%d m total · %.1f%% avg gradient · %d m elevation gain",
                    climb.length, climb.avgGradient * 100, climb.elevationGain));
            binding.climbProfile.setSegments(climb.segments);
            adapter.setItems(climb.segments);

            loadedClimb = climb;
            tryDrawMap();
        });

        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
        viewModel.saved().observe(this, isSaved -> {
            if (Boolean.TRUE.equals(isSaved))
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
        });

        binding.btnRenameClimb.setOnClickListener(v -> showRenameDialog());
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());

        viewModel.loadClimb(routeId, climbIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.mapView.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void tryDrawMap() {
        if (loadedRoute == null || loadedClimb == null) return;
        if (loadedRoute.lats == null || loadedRoute.lats.length == 0) return;

        // Full route — gray background
        List<GeoPoint> allPoints = new ArrayList<>(loadedRoute.lats.length);
        for (int i = 0; i < loadedRoute.lats.length; i++) {
            allPoints.add(new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]));
        }
        Polyline routeLine = new Polyline();
        routeLine.setColor(Color.GRAY);
        routeLine.setWidth(4f);
        routeLine.setPoints(allPoints);

        // Climb segment — orange highlight
        List<GeoPoint> climbPoints = new ArrayList<>();
        for (int i = 0; i < loadedRoute.distances.length; i++) {
            if (loadedRoute.distances[i] >= loadedClimb.startDistance
                    && loadedRoute.distances[i] <= loadedClimb.endDistance) {
                climbPoints.add(new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]));
            }
        }
        Polyline climbLine = new Polyline();
        climbLine.setColor(Color.parseColor("#FF8C00")); // orange
        climbLine.setWidth(7f);
        climbLine.setPoints(climbPoints);

        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(routeLine);
        binding.mapView.getOverlays().add(climbLine);

        List<GeoPoint> zoomTarget = climbPoints.isEmpty() ? allPoints : climbPoints;
        BoundingBox box = BoundingBox.fromGeoPoints(zoomTarget);
        binding.mapView.post(() -> binding.mapView.zoomToBoundingBox(box, true, 80));
        binding.mapView.invalidate();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint("Climb name");
        new AlertDialog.Builder(this)
                .setTitle("Rename climb")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        viewModel.renameClimb(routeId, climbIndex,
                                input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReSegmentDialog() {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(4);
        picker.setMaxValue(32);
        nl.paree.climbpro.data.route.StoredClimb current = viewModel.climb().getValue();
        int defaultCount = (current != null && current.segmentCount > 0) ? current.segmentCount : 16;
        picker.setValue(defaultCount);
        new AlertDialog.Builder(this)
                .setTitle("Segmenten per klim")
                .setView(picker)
                .setPositiveButton("Herbereken", (dialog, which) ->
                        viewModel.reSegment(routeId, climbIndex, picker.getValue()))
                .setNegativeButton("Annuleer", null)
                .show();
    }
}
```

- [ ] **Step 3: Build**

```
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all tests**

```
./gradlew test
```

Expected: all tests pass including `ClimbDetailViewModelTest`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/layout/activity_climb_detail.xml
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java
git commit -m "feat: add OSM climb map to ClimbDetailActivity (route gray, climb orange)"
```

---

## Self-review

**Spec coverage:**
- ✅ All segments visible — NestedScrollView fix in Task 1
- ✅ Route on map — Task 3 (RouteDetailActivity)
- ✅ Climb on map from GPX data — Task 5 (ClimbDetailActivity, uses stored lats/lons/distances from GPX import)

**Placeholder scan:** None found.

**Type consistency:**
- `StoredRoute.lats[]`, `.lons[]`, `.distances[]` — used in Tasks 3 and 5, consistent with `StoredRoute.java`
- `StoredClimb.startDistance`, `.endDistance` — both `int`, compared with `double` `distances[i]` — safe widening comparison in Java
- `binding.mapView` — matches `android:id="@+id/map_view"` added in Tasks 3 and 5
- `viewModel.route()` — added in Task 4, consumed in Task 5
