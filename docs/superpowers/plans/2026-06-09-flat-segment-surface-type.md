# Flat Segment Surface Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add surface type annotation (asfalt/gravel/onverhard/kasseien/mixed) to flat segments — the non-climb stretches between climbs on a route — with the same per-item picker already used for climb segments.

**Architecture:** Every gap between detected climbs (and before the first / after the last climb) becomes a `StoredFlatSegment` stored inside `StoredRoute`. `RouteDetailActivity` shows climbs and flat segments interleaved in one RecyclerView. Surface type is set by long-pressing a flat row, exactly like long-pressing a segment in ClimbDetail. No protocol or watch changes.

**Tech Stack:** Java, Android MVVM (AndroidViewModel + LiveData), RecyclerView multi-type adapter, Jackson (via existing `ObjectMapper` in `RouteRepository`), JUnit 4 unit tests.

---

## File Map

| Action | File |
|--------|------|
| Create | `android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegment.java` |
| Create | `android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegmentDetector.java` |
| Create | `android/app/src/main/java/nl/paree/climbpro/data/route/StoredFlatSegment.java` |
| Create | `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java` |
| Create | `android/app/src/main/res/layout/item_flat_segment.xml` |
| Create | `android/app/src/test/java/nl/paree/climbpro/domain/FlatSegmentDetectorTest.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java` |
| Modify | `android/app/src/main/res/layout/activity_route_detail.xml` |

---

## Task 0: Create feature branch

- [ ] **Step 1: Create and check out the branch**

```bash
git checkout Tests
git checkout -b feature/flat-segment-surface-type
```

Expected: branch `feature/flat-segment-surface-type` checked out from `Tests`.

---

## Task 1: FlatSegment domain class

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegment.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/FlatSegmentDetectorTest.java` (placeholder — populated in Task 3)

- [ ] **Step 1: Create FlatSegment.java**

```java
// android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegment.java
package nl.paree.climbpro.domain.segment;

/** Domain model for a non-climb stretch between two climbs (or route start/end). */
public final class FlatSegment {

    public final int startDistance; // metres from route start
    public final int endDistance;
    public final int length;        // endDistance - startDistance

    public FlatSegment(int startDistance, int endDistance, int length) {
        this.startDistance = startDistance;
        this.endDistance   = endDistance;
        this.length        = length;
    }
}
```

- [ ] **Step 2: Create StoredFlatSegment.java**

```java
// android/app/src/main/java/nl/paree/climbpro/data/route/StoredFlatSegment.java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredFlatSegment {
    public int startDistance;
    public int endDistance;
    public int length;
    public int surfaceType = SurfaceType.UNKNOWN;
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegment.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/StoredFlatSegment.java
git commit -m "feat(android): FlatSegment domain + StoredFlatSegment data class"
```

---

## Task 2: FlatSegmentDetector with tests

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegmentDetector.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/FlatSegmentDetectorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// android/app/src/test/java/nl/paree/climbpro/domain/FlatSegmentDetectorTest.java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.segment.FlatSegment;
import nl.paree.climbpro.domain.segment.FlatSegmentDetector;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FlatSegmentDetectorTest {

    private static Climb climb(int startDistance, int endDistance) {
        return Climb.builder()
                .startDistance(startDistance)
                .endDistance(endDistance)
                .length(endDistance - startDistance)
                .elevationGain(100)
                .avgGradient(0.05)
                .segments(Collections.emptyList())
                .calibrationPoints(Collections.emptyList())
                .build();
    }

    @Test
    public void noClimbs_oneFullRouteFlat() {
        List<FlatSegment> result = FlatSegmentDetector.detect(10000, Collections.emptyList());
        assertEquals(1, result.size());
        assertEquals(0, result.get(0).startDistance);
        assertEquals(10000, result.get(0).endDistance);
        assertEquals(10000, result.get(0).length);
    }

    @Test
    public void oneClimbInMiddle_twoFlatSegments() {
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(3000, 5000)));
        assertEquals(2, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(3000,  result.get(0).endDistance);
        assertEquals(3000,  result.get(0).length);
        assertEquals(5000,  result.get(1).startDistance);
        assertEquals(10000, result.get(1).endDistance);
        assertEquals(5000,  result.get(1).length);
    }

    @Test
    public void climbAtStart_oneFlatAfter() {
        List<FlatSegment> result = FlatSegmentDetector.detect(8000,
                List.of(climb(0, 3000)));
        assertEquals(1, result.size());
        assertEquals(3000, result.get(0).startDistance);
        assertEquals(8000, result.get(0).endDistance);
    }

    @Test
    public void climbAtEnd_oneFlatBefore() {
        List<FlatSegment> result = FlatSegmentDetector.detect(8000,
                List.of(climb(6000, 8000)));
        assertEquals(1, result.size());
        assertEquals(0,    result.get(0).startDistance);
        assertEquals(6000, result.get(0).endDistance);
    }

    @Test
    public void adjacentClimbs_noFlatBetween() {
        // climb ends at 4000, next climb starts at 4000 → zero-length gap skipped
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(2000, 4000), climb(4000, 7000)));
        assertEquals(2, result.size()); // one before, one after; nothing between
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(2000,  result.get(0).endDistance);
        assertEquals(7000,  result.get(1).startDistance);
        assertEquals(10000, result.get(1).endDistance);
    }

    @Test
    public void twoClimbs_threeFlatSegments() {
        List<FlatSegment> result = FlatSegmentDetector.detect(15000,
                List.of(climb(2000, 5000), climb(8000, 11000)));
        assertEquals(3, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(2000,  result.get(0).endDistance);
        assertEquals(5000,  result.get(1).startDistance);
        assertEquals(8000,  result.get(1).endDistance);
        assertEquals(11000, result.get(2).startDistance);
        assertEquals(15000, result.get(2).endDistance);
    }

    @Test
    public void unsortedClimbs_sortedOutput() {
        // Climbs provided out of order — detector must sort them
        List<FlatSegment> result = FlatSegmentDetector.detect(10000,
                List.of(climb(6000, 8000), climb(1000, 3000)));
        assertEquals(3, result.size());
        assertEquals(0,     result.get(0).startDistance);
        assertEquals(3000,  result.get(1).startDistance);
        assertEquals(8000,  result.get(2).startDistance);
    }

    @Test
    public void routeLengthZero_emptyResult() {
        List<FlatSegment> result = FlatSegmentDetector.detect(0, Collections.emptyList());
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 2: Run tests — expect compile error (class not found)**

```bash
cd android && ./gradlew :app:test --tests "nl.paree.climbpro.domain.FlatSegmentDetectorTest" 2>&1 | tail -30
```

Expected: BUILD FAILED — `FlatSegmentDetector` does not exist.

- [ ] **Step 3: Implement FlatSegmentDetector**

```java
// android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegmentDetector.java
package nl.paree.climbpro.domain.segment;

import nl.paree.climbpro.domain.climb.Climb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Detects the flat (non-climb) stretches in a route as gaps between detected climbs. */
public final class FlatSegmentDetector {

    private FlatSegmentDetector() {}

    /**
     * Returns a list of flat segments covering every metre of the route not covered by a climb.
     * Zero-length gaps (adjacent climbs) are skipped.
     *
     * @param routeLengthMetres total route length in metres
     * @param climbs            detected climbs (may be in any order)
     */
    public static List<FlatSegment> detect(int routeLengthMetres, List<Climb> climbs) {
        if (routeLengthMetres <= 0) return Collections.emptyList();

        List<Climb> sorted = new ArrayList<>(climbs);
        sorted.sort(Comparator.comparingInt(c -> c.startDistance));

        List<FlatSegment> result = new ArrayList<>();
        int cursor = 0;

        for (Climb c : sorted) {
            if (c.startDistance > cursor) {
                result.add(new FlatSegment(cursor, c.startDistance,
                        c.startDistance - cursor));
            }
            cursor = c.endDistance;
        }

        if (cursor < routeLengthMetres) {
            result.add(new FlatSegment(cursor, routeLengthMetres,
                    routeLengthMetres - cursor));
        }

        return result;
    }
}
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
cd android && ./gradlew :app:test --tests "nl.paree.climbpro.domain.FlatSegmentDetectorTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 9 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/FlatSegmentDetector.java \
        android/app/src/test/java/nl/paree/climbpro/domain/FlatSegmentDetectorTest.java
git commit -m "feat(android): FlatSegmentDetector + unit tests"
```

---

## Task 3: StoredRoute + RouteRepository integration

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`

- [ ] **Step 1: Add flatSegments field to StoredRoute**

Open `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`.

Replace the entire file with:

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredRoute {

    public String routeId;
    public String sourceHash;
    public String name;
    public String userDisplayName;
    public String notes;

    public double[] lats;
    public double[] lons;
    public double[] elevations;
    public double[] distances;

    public List<StoredClimb> climbs;

    /** Flat (non-climb) stretches between climbs, in route order. */
    public List<StoredFlatSegment> flatSegments;

    public long importedAtMs;
    public long lastModifiedMs;
}
```

- [ ] **Step 2: Add helper methods + integrate detection in RouteRepository**

In `RouteRepository.java`, make the following changes:

**2a. Add imports** at the top of the file (after the existing imports):

```java
import nl.paree.climbpro.domain.segment.FlatSegment;
import nl.paree.climbpro.domain.segment.FlatSegmentDetector;
import nl.paree.climbpro.domain.segment.SurfaceType;
import java.io.FileInputStream;
import java.util.Map;
import java.util.HashMap;
```

*(Note: `FileInputStream` is already imported; `SurfaceType` and `Map`/`HashMap` may not be — add only the ones missing.)*

**2b. In `saveRoute()`, add flat segment detection** immediately after the line `route.climbs = toStoredClimbs(climbs);`:

```java
        int routeLength = (points != null && !points.isEmpty())
                ? (int) Math.round(points.get(points.size() - 1).distance)
                : 0;
        List<FlatSegment> flatDomain = FlatSegmentDetector.detect(
                routeLength, climbs != null ? climbs : Collections.emptyList());
        route.flatSegments = toStoredFlatSegments(flatDomain, loadPreviousFlatSegments(route.routeId));
```

**2c. Add `loadPreviousFlatSegments` helper** after the `routeFile()` helper method:

```java
    private List<StoredFlatSegment> loadPreviousFlatSegments(String routeId) {
        File f = routeFile(routeId);
        if (!f.exists()) return Collections.emptyList();
        try (FileInputStream in = new FileInputStream(f)) {
            StoredRoute existing = mapper.readValue(in, StoredRoute.class);
            return existing.flatSegments != null ? existing.flatSegments : Collections.emptyList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
```

**2d. Add `toStoredFlatSegments` helper** after `toStoredClimbs()`:

```java
    private static List<StoredFlatSegment> toStoredFlatSegments(
            List<FlatSegment> flat, List<StoredFlatSegment> previous) {
        Map<Integer, Integer> prevSurface = new HashMap<>();
        for (StoredFlatSegment prev : previous) {
            if (prev.surfaceType != SurfaceType.UNKNOWN) {
                prevSurface.put(prev.startDistance, prev.surfaceType);
            }
        }
        List<StoredFlatSegment> result = new ArrayList<>(flat.size());
        for (FlatSegment fs : flat) {
            StoredFlatSegment sfs = new StoredFlatSegment();
            sfs.startDistance = fs.startDistance;
            sfs.endDistance   = fs.endDistance;
            sfs.length        = fs.length;
            sfs.surfaceType   = prevSurface.getOrDefault(fs.startDistance, SurfaceType.UNKNOWN);
            result.add(sfs);
        }
        return result;
    }
```

**2e. Extend `computeSurfaceTypes()`** — inside the existing method, after the loop over `route.climbs`, add:

```java
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
                    surfaceSet.add(sf.surfaceType);
                }
            }
        }
```

**2f. Add `setFlatSegmentSurfaceType()` method** after `setBulkClimbSurfaceType()`:

```java
    /**
     * Sets the surface type of a flat segment identified by its startDistance,
     * then updates the catalog.
     */
    public void setFlatSegmentSurfaceType(String routeId, int startDistance,
                                           int surfaceType) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.startDistance == startDistance) {
                    sf.surfaceType = surfaceType;
                    break;
                }
            }
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }
```

- [ ] **Step 3: Run the full test suite to confirm nothing is broken**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java
git commit -m "feat(android): detect + store flat segments in RouteRepository, extend surfaceTypes catalog"
```

---

## Task 4: Flat segment row layout

**Files:**
- Create: `android/app/src/main/res/layout/item_flat_segment.xml`
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml` (update section header)

- [ ] **Step 1: Create item_flat_segment.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp"
    android:gravity="center_vertical"
    android:background="?android:attr/selectableItemBackground">

    <TextView
        android:id="@+id/flat_distance"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="14sp"
        android:textColor="?android:attr/textColorSecondary"/>

    <TextView
        android:id="@+id/flat_surface_badge"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:gravity="center"
        android:textSize="11sp"
        android:textStyle="bold"
        android:textColor="@android:color/white"
        android:visibility="invisible"/>

</LinearLayout>
```

- [ ] **Step 2: Update section header in activity_route_detail.xml**

In `android/app/src/main/res/layout/activity_route_detail.xml`, find the `TextView` with `android:text="Climbs"` and change it to:

```xml
android:text="Klimmen &amp; vlakke stukken"
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/layout/item_flat_segment.xml \
        android/app/src/main/res/layout/activity_route_detail.xml
git commit -m "feat(android): item_flat_segment layout + update route detail header"
```

---

## Task 5: RouteDetailAdapter (multi-type RecyclerView)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java`

- [ ] **Step 1: Create RouteDetailAdapter.java**

```java
package nl.paree.climbpro.ui.routes;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FLAT  = 0;
    private static final int VIEW_TYPE_CLIMB = 1;

    // Surface badge background colors — same mapping as ClimbSegmentAdapter
    private static final int[] SURFACE_BG = {
        0xFF404040, // ASPHALT
        0xFFC8A050, // GRAVEL
        0xFF8B4513, // DIRT
        0xFF909090, // COBBLESTONE
        0xFF9060C0, // MIXED
    };

    public interface OnClimbClickListener {
        void onClimbClick(StoredClimb climb, int climbIndex);
    }

    public interface OnFlatLongClickListener {
        void onFlatLongClick(StoredFlatSegment flat);
    }

    private List<Object> items = new ArrayList<>();
    private OnClimbClickListener  climbClickListener;
    private OnFlatLongClickListener flatLongClickListener;

    public void setItems(List<Object> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnClimbClickListener(OnClimbClickListener l)   { climbClickListener = l; }
    public void setOnFlatLongClickListener(OnFlatLongClickListener l) { flatLongClickListener = l; }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof StoredFlatSegment ? VIEW_TYPE_FLAT : VIEW_TYPE_CLIMB;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_FLAT) {
            return new FlatViewHolder(inflater.inflate(R.layout.item_flat_segment, parent, false));
        }
        return new ClimbViewHolder(inflater.inflate(R.layout.item_climb, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FlatViewHolder) {
            bindFlat((FlatViewHolder) holder, (StoredFlatSegment) items.get(position));
        } else {
            bindClimb((ClimbViewHolder) holder, position);
        }
    }

    private void bindFlat(FlatViewHolder h, StoredFlatSegment flat) {
        h.distanceView.setText(String.format("%.1f km vlak", flat.length / 1000.0));

        String label = SurfaceType.label(flat.surfaceType);
        if (label != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(label);
            int st = SurfaceType.fromInt(flat.surfaceType);
            if (st < SURFACE_BG.length) h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        h.itemView.setOnLongClickListener(v -> {
            if (flatLongClickListener != null) flatLongClickListener.onFlatLongClick(flat);
            return true;
        });
    }

    private void bindClimb(ClimbViewHolder h, int position) {
        int climbIndex = 0;
        for (int i = 0; i < position; i++) {
            if (items.get(i) instanceof StoredClimb) climbIndex++;
        }
        StoredClimb c = (StoredClimb) items.get(position);
        String name = c.userDisplayName != null ? c.userDisplayName : c.name;
        h.nameView.setText(name != null ? name : "Klim " + (climbIndex + 1));
        h.statsView.setText(String.format("%d m · %.1f%% gem. · %d m hoogte",
                c.length, c.avgGradient * 100, c.elevationGain));
        final int ci = climbIndex;
        h.itemView.setOnClickListener(v -> {
            if (climbClickListener != null) climbClickListener.onClimbClick(c, ci);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class FlatViewHolder extends RecyclerView.ViewHolder {
        TextView distanceView;
        TextView surfaceBadge;
        FlatViewHolder(View v) {
            super(v);
            distanceView = v.findViewById(R.id.flat_distance);
            surfaceBadge = v.findViewById(R.id.flat_surface_badge);
        }
    }

    static final class ClimbViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView statsView;
        ClimbViewHolder(View v) {
            super(v);
            nameView  = v.findViewById(R.id.climb_name);
            statsView = v.findViewById(R.id.climb_stats);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java
git commit -m "feat(android): RouteDetailAdapter — multi-type RecyclerView for climbs + flat segments"
```

---

## Task 6: RouteDetailViewModel — expose interleaved list + flat segment surface setter

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`

- [ ] **Step 1: Replace RouteDetailViewModel.java with the updated version**

```java
package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.RouteSyncWorker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteDetailViewModel extends AndroidViewModel {

    private final RouteRepository routeRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<StoredRoute> route      = new MutableLiveData<>();
    private final MutableLiveData<List<Object>> routeItems = new MutableLiveData<>();
    private final MutableLiveData<String>       error      = new MutableLiveData<>();
    private final MutableLiveData<Boolean>      saved      = new MutableLiveData<>(false);

    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
    }

    public LiveData<StoredRoute>  route()      { return route; }
    public LiveData<List<Object>> routeItems() { return routeItems; }
    public LiveData<String>       error()      { return error; }
    public LiveData<Boolean>      saved()      { return saved; }

    public void loadRoute(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                routeItems.postValue(buildRouteItems(r));
            } catch (Exception e) {
                error.postValue("Could not load route: " + e.getMessage());
            }
        });
    }

    public void renameRoute(String routeId, String newName) {
        executor.execute(() -> {
            try {
                routeRepo.renameRoute(routeId, newName);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Rename failed: " + e.getMessage());
            }
        });
    }

    public void saveNotes(String routeId, String notes) {
        executor.execute(() -> {
            try {
                routeRepo.saveNotes(routeId, notes);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Save failed: " + e.getMessage());
            }
        });
    }

    public void setActiveRoute(String routeId) {
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit()
                .putString(RouteSyncWorker.PREF_ROUTE_ID, routeId)
                .putString(RouteSyncWorker.PREF_MODE, RouteSyncWorker.MODE_ROUTE)
                .apply();
    }

    /** Persists the surface type for a flat segment identified by its startDistance. */
    public void setFlatSegmentSurface(String routeId, int startDistance, int surfaceType) {
        executor.execute(() -> {
            try {
                routeRepo.setFlatSegmentSurfaceType(routeId, startDistance, surfaceType);
                loadRoute(routeId);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    private static List<Object> buildRouteItems(StoredRoute r) {
        List<StoredFlatSegment> flats  = r.flatSegments != null ? r.flatSegments : Collections.emptyList();
        List<StoredClimb>       climbs = r.climbs       != null ? r.climbs       : Collections.emptyList();

        List<Object> result = new ArrayList<>(flats.size() + climbs.size());
        int fi = 0, ci = 0;
        while (fi < flats.size() || ci < climbs.size()) {
            StoredFlatSegment flat  = fi < flats.size()  ? flats.get(fi)  : null;
            StoredClimb       climb = ci < climbs.size() ? climbs.get(ci) : null;

            if (flat != null && (climb == null || flat.startDistance <= climb.startDistance)) {
                result.add(flat);
                fi++;
            } else {
                result.add(climb);
                ci++;
            }
        }
        return result;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
```

- [ ] **Step 2: Run the full test suite**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java
git commit -m "feat(android): RouteDetailViewModel exposes interleaved route items + flat segment surface setter"
```

---

## Task 7: RouteDetailActivity — wire up the new adapter and surface picker

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`

- [ ] **Step 1: Replace RouteDetailActivity.java with the updated version**

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

import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.databinding.ActivityRouteDetailBinding;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ROUTE_ID = "route_id";

    private ActivityRouteDetailBinding binding;
    private RouteDetailViewModel        viewModel;
    private RouteDetailAdapter          adapter;
    private String                      routeId;

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
        adapter   = new RouteDetailAdapter();

        binding.climbsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.climbsRecycler.setAdapter(adapter);

        adapter.setOnClimbClickListener((climb, index) ->
                startActivity(ClimbDetailActivity.intentFor(this, routeId, index)));
        adapter.setOnFlatLongClickListener(this::showFlatSurfaceDialog);

        viewModel.route().observe(this, route -> {
            if (route == null) return;
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            binding.toolbar.setTitle(name != null ? name : route.routeId);
            binding.notesEdit.setText(route.notes != null ? route.notes : "");
            drawRoute(route);
        });

        viewModel.routeItems().observe(this, items -> adapter.setItems(items));

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
        if (route.lats == null || route.lons == null
                || route.lats.length == 0 || route.lons.length < route.lats.length) return;

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

    private void showFlatSurfaceDialog(StoredFlatSegment flat) {
        String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};
        int current = SurfaceType.fromInt(flat.surfaceType);
        new AlertDialog.Builder(this)
                .setTitle("Oppervlak voor vlak segment")
                .setSingleChoiceItems(typeLabels, current, null)
                .setPositiveButton("Opslaan", (dialog, which) -> {
                    android.widget.ListView lv = ((AlertDialog) dialog).getListView();
                    int chosen = lv.getCheckedItemPosition();
                    if (chosen >= 0 && chosen <= 5) {
                        viewModel.setFlatSegmentSurface(routeId, flat.startDistance, chosen);
                    }
                })
                .setNegativeButton("Annuleer", null)
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

- [ ] **Step 2: Run the full test suite**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build the debug APK to verify compilation**

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, `app-debug.apk` produced.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java
git commit -m "feat(android): RouteDetailActivity shows interleaved flat+climb list with surface type picker"
```

---

## Task 8: Merge to Tests

- [ ] **Step 1: Run all tests one final time**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Switch to Tests and merge**

```bash
git checkout Tests
git merge --no-ff feature/flat-segment-surface-type -m "feat: flat segment surface type (phone-only)"
```

- [ ] **Step 3: Verify the merge commit is on Tests**

```bash
git log --oneline -5
```

Expected: merge commit at the top, branch history visible.

- [ ] **Step 4: Delete the feature branch**

```bash
git branch -d feature/flat-segment-surface-type
```
