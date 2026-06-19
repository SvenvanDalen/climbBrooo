# Named, Coordinate-Checked Surface & Flat Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give both user-drawn surface sections (`StoredSurfaceSection`) and auto-detected flat segments (`StoredFlatSegment`) a free-text **name**, send them to the watch with **GPS checkpoint coordinates**, and have the watch surface datafield (a) correct its distance-based matching using those coordinates and (b) show the name as the section title.

**Architecture:** The phone already sends surface sections to the watch as a packed `surfSec` integer-triple array, matched purely by `elapsedDistance`. We change `surfSec` to an **array of section objects** (`{s,e,t,n?,cp}`) that additionally carries an optional name and a packed checkpoint array `cp = [distanceFromRouteStart, latInt, lonInt, ...]` (latInt/lonInt = degrees×100000, mirroring the climb `calib` encoding). Both surface sections and meaningful flat segments are merged into this one list, sorted by start distance. On the watch, `SurfaceData` parses the new shape, keeps distance as the primary matching axis, and on each tick snaps a small GPS-derived `distanceOffset` to the nearest checkpoint within a threshold to cancel drift. The view shows the name as the title with the surface type small underneath.

**Tech Stack:** Java (Android, MVVM + Repository), Jackson JSON persistence, JUnit 4 + Robolectric, Gradle Groovy DSL; Monkey C (Connect IQ, `garmin-surface/` datafield); JSON Schema (`protocol/schema.json`) + round-trip tests.

---

## Background: what already exists (read before starting)

- **Phone surface sections.** `StoredSurfaceSection {startDistance, endDistance, surfaceType}` — user-drawn, distance-only, phone-managed in `RouteDetailActivity` → "Ondergrond-stukken". No coordinates, no name.
- **Phone flat segments.** `StoredFlatSegment {startDistance, endDistance, length, surfaceType, startLat, startLon, endLat, endLon}` — auto-detected between climbs; **already carries start/end coordinates** but is deliberately *not* sent to the watch (decision 2026-06-10). This plan reverses that for flat segments that the user has named or assigned a surface to.
- **Wire builder.** `service/ClimbPayloadBuilder.buildSurfaceSectionPayload(StoredRoute)` emits `{v:3, mode:"route", routeId, name, climbs:[], surfSec:[start,end,type, ...]}`. Climbs already encode per-distance coordinates as a packed `calib = [dist, latInt, lonInt, ...]` triple array (`buildCalib`, latInt/lonInt = `Math.round(deg*100000)`). **We mirror that encoding for `cp`.**
- **Watch.** `garmin-surface/source/SurfaceData.mc` parses `surfSec` triples into parallel arrays and matches by `elapsedDistance` in `updateProgress`. `SurfaceFieldView.mc` draws the current section's surface name + remaining distance.
- **Schema.** `protocol/schema.json` models the logical `surfaceSections` array (definition `SurfaceSection`); the builder packs it into the `surfSec` wire field. `ProtocolRoundTripTest` validates every file in `protocol/examples/` against the schema.

### Design decisions locked for this plan

1. **One merged watch list.** Surface sections and flat segments are merged into a single `surfSec` list on the watch, sorted by start distance, displayed uniformly. The watch does not distinguish their origin.
2. **Flat-segment inclusion filter.** A flat segment is sent to the watch **only if it has a name OR a non-UNKNOWN surface type**. An untouched flat segment (no name, unknown surface) carries nothing useful for the surface field and is skipped to avoid clutter. Surface sections are always sent.
3. **Coordinates computed at build time**, not stored. Checkpoints are derived from the route's `distances/lats/lons` parallel arrays each time the payload is built, so they always match current geometry. Only the `name` is newly persisted.
4. **Distance-primary matching with coordinate correction.** The watch keeps `elapsedDistance` as the axis and applies a smoothed `distanceOffset` snapped to the nearest checkpoint within `SNAP_M` (40 m). Coordinates correct drift; they do not replace distance.
5. **Checkpoint density.** One checkpoint at the section start, one every `CHECKPOINT_SPACING_M` (200 m), and one at the end; capped at `MAX_CHECKPOINTS_PER_SECTION` (12) to bound payload size.

### Conventions

- Java POJOs: public fields, `@JsonIgnoreProperties(ignoreUnknown = true)`, no getters/setters.
- All distances are integer metres internally.
- `latInt = (int) Math.round(lat * 100000)` (same as `buildCalib`).
- Repository writes are atomic (`writeAtomic`) and refresh the catalog surface index (`rebuildCatalogSurfaceTypes`).
- **Robolectric repository-test gotcha:** `RouteRepository`'s constructor runs `migrateIfNeeded()`, which wipes route files unless the `segment_version` pref equals `ClimbConstants.SEGMENT_VERSION`. Every repo test MUST pre-seed that pref before constructing the repository (see existing `SurfaceSectionRepositoryTest.setUp`).

### Commands (this project)

- Android unit test (one class): `cd android && ./gradlew test --tests nl.paree.climbpro.<FQCN>` (Windows: `.\gradlew.bat`).
- Android compile: `cd android && ./gradlew compileDebugJavaWithJavac`.
- Android assemble: `cd android && ./gradlew assembleDebug`.
- Surface datafield build: `cd garmin-surface && monkeyc -o bin/surface.prg -f monkey.jungle -y <developer_key>` then run in the FR255 Music simulator.

---

## Task 1: Add `name` to both stored models

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredFlatSegment.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java`

Pure data declarations, exercised by later repository tests. No standalone test (empty fields have no behaviour); the fields must exist first so later test code compiles.

- [ ] **Step 1: Add `name` to `StoredFlatSegment`**

In `StoredFlatSegment.java`, add the field after `endLon`:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredFlatSegment {
    public int    startDistance;
    public int    endDistance;
    public int    length;
    public int    surfaceType = SurfaceType.UNKNOWN;
    public double startLat    = Double.NaN;
    public double startLon    = Double.NaN;
    public double endLat      = Double.NaN;
    public double endLon      = Double.NaN;
    /** Optional user-supplied display name; survives route re-import. */
    public String name;
}
```

- [ ] **Step 2: Add `name` to `StoredSurfaceSection`**

In `StoredSurfaceSection.java`, add the field after `surfaceType`:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSurfaceSection {
    public int startDistance;
    public int endDistance;
    public int surfaceType = SurfaceType.UNKNOWN;
    /** Optional user-supplied display name; survives route re-import. */
    public String name;
}
```

- [ ] **Step 3: Verify the module compiles**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredFlatSegment.java android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java
git commit -m "feat(android): add optional name field to flat segments and surface sections"
```

---

## Task 2: Repository — flat-segment name (set + preserve across re-import)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/FlatSegmentNameRepositoryTest.java`

We add `updateFlatSegment(routeId, startDistance, surfaceType, name)` (one atomic write that sets both surface and name) and carry the name over on re-import inside `toStoredFlatSegments`, keyed by `startDistance` exactly like the surface type is today.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/data/route/FlatSegmentNameRepositoryTest.java`:

```java
package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.io.File;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class FlatSegmentNameRepositoryTest {

    private Application app;

    /** Writes a route with one flat segment 1000..2000 m and a 0..5000 m axis. */
    private void seedRouteWithFlat(String routeId) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = routeId;
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01, 51.02};
        route.lons       = new double[]{5.0, 5.01, 5.02};
        route.elevations = new double[]{100, 120, 140};
        route.distances  = new double[]{0, 2500, 5000};

        StoredFlatSegment fs = new StoredFlatSegment();
        fs.startDistance = 1000;
        fs.endDistance   = 2000;
        fs.length        = 1000;
        route.flatSegments = new java.util.ArrayList<>();
        route.flatSegments.add(fs);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void updateFlatSegment_persistsSurfaceAndName() throws Exception {
        seedRouteWithFlat("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.updateFlatSegment("r1", 1000, SurfaceType.GRAVEL, "Bospad");

        StoredFlatSegment fs = repo.loadRoute("r1").flatSegments.get(0);
        assertEquals(SurfaceType.GRAVEL, fs.surfaceType);
        assertEquals("Bospad", fs.name);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.FlatSegmentNameRepositoryTest`
Expected: FAIL — `cannot find symbol: method updateFlatSegment`.

- [ ] **Step 3: Add `updateFlatSegment` to `RouteRepository`**

In `RouteRepository.java`, add directly after `setFlatSegmentSurfaceType` (after its closing brace, around line 461):

```java
    /**
     * Sets both the surface type and the optional display name of a flat segment
     * identified by its startDistance, in a single atomic write. A blank/empty name
     * is stored as null. Updates the catalog surface index.
     */
    public void updateFlatSegment(String routeId, int startDistance,
                                  int surfaceType, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        boolean found = false;
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.startDistance == startDistance) {
                    sf.surfaceType = SurfaceType.fromInt(surfaceType);
                    sf.name = (name == null || name.trim().isEmpty()) ? null : name.trim();
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            Log.w(TAG, "updateFlatSegment: no flat segment at startDistance " + startDistance);
            return;
        }
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.FlatSegmentNameRepositoryTest`
Expected: PASS (1 test).

- [ ] **Step 5: Add the failing re-import test**

Append this method inside `FlatSegmentNameRepositoryTest` (before the closing brace):

```java
    @Test
    public void flatSegmentName_survivesReimport() throws Exception {
        seedRouteWithFlat("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.updateFlatSegment("r1", 1000, SurfaceType.GRAVEL, "Bospad");

        // Re-import: a fresh route whose flat detector re-emits the same 1000..2000 segment.
        StoredRoute fresh = new StoredRoute();
        fresh.routeId = "r1";
        fresh.name    = "Test";
        java.util.List<nl.paree.climbpro.domain.route.RoutePoint> points = new java.util.ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.0, 5.0, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.01, 5.01, 120, 1500));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.02, 5.02, 140, 3000));

        nl.paree.climbpro.domain.climb.FlatSegment flat =
                new nl.paree.climbpro.domain.climb.FlatSegment(1000, 2000, 1000);
        repo.saveRoute(fresh, points, java.util.Collections.emptyList(),
                java.util.Collections.singletonList(flat));

        StoredFlatSegment fs = repo.loadRoute("r1").flatSegments.get(0);
        assertEquals("name must survive re-import", "Bospad", fs.name);
        assertEquals(SurfaceType.GRAVEL, fs.surfaceType);
    }
```

> **Before writing the implementation, confirm the real `saveRoute` signature and the `FlatSegment` constructor** with:
> `cd android && grep -n "public .*saveRoute\|class FlatSegment\|FlatSegment(" app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java app/src/main/java/nl/paree/climbpro/domain/climb/FlatSegment.java`
> Adjust the test's `saveRoute(...)` / `new FlatSegment(...)` calls to match the actual argument lists if they differ (the surface-section preservation test in `SurfaceSectionRepositoryTest` shows the established pattern for a re-import call).

- [ ] **Step 6: Run to verify failure**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.data.route.FlatSegmentNameRepositoryTest.flatSegmentName_survivesReimport"`
Expected: FAIL — `fs.name` is `null` (the new detector output dropped the name).

- [ ] **Step 7: Carry the name over in `toStoredFlatSegments`**

In `RouteRepository.toStoredFlatSegments` (around lines 281–306), add a `prevName` map alongside the existing `prevSurface` map and apply it:

```java
    private static List<StoredFlatSegment> toStoredFlatSegments(
            List<FlatSegment> flat, List<RoutePoint> points,
            List<StoredFlatSegment> previous) {
        Map<Integer, Integer> prevSurface = new HashMap<>();
        Map<Integer, String>  prevName    = new HashMap<>();
        for (StoredFlatSegment prev : previous) {
            if (prev.surfaceType != SurfaceType.UNKNOWN) {
                prevSurface.put(prev.startDistance, prev.surfaceType);
            }
            if (prev.name != null) {
                prevName.put(prev.startDistance, prev.name);
            }
        }
        List<StoredFlatSegment> result = new ArrayList<>(flat.size());
        for (FlatSegment fs : flat) {
            StoredFlatSegment sfs = new StoredFlatSegment();
            sfs.startDistance = fs.startDistance;
            sfs.endDistance   = fs.endDistance;
            sfs.length        = fs.length;
            sfs.surfaceType   = prevSurface.getOrDefault(fs.startDistance, SurfaceType.UNKNOWN);
            sfs.name          = prevName.get(fs.startDistance);
            double[] startCoord = nearestCoord(points, fs.startDistance);
            double[] endCoord   = nearestCoord(points, fs.endDistance);
            sfs.startLat = startCoord[0];
            sfs.startLon = startCoord[1];
            sfs.endLat   = endCoord[0];
            sfs.endLon   = endCoord[1];
            result.add(sfs);
        }
        return result;
    }
```

- [ ] **Step 8: Run the full class to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.FlatSegmentNameRepositoryTest`
Expected: PASS (2 tests).

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/FlatSegmentNameRepositoryTest.java
git commit -m "feat(android): name flat segments (set + preserve across re-import)"
```

---

## Task 3: Repository — surface-section name (add + rename)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java` (existing)

We extend `addSurfaceSection` with a `name` parameter and add `setSurfaceSectionName(routeId, index, name)`. The whole `StoredSurfaceSection` object already survives re-import via `loadPreviousSurfaceSections`, so no preservation work is needed for the name.

- [ ] **Step 1: Add failing tests**

Append these methods inside the existing `SurfaceSectionRepositoryTest` (before the closing brace):

```java
    @Test
    public void addSurfaceSection_storesName() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL, "Grindstrook");

        StoredSurfaceSection s = repo.loadRoute("r1").surfaceSections.get(0);
        assertEquals("Grindstrook", s.name);
    }

    @Test
    public void addSurfaceSection_blankNameStoredAsNull() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL, "   ");

        org.junit.Assert.assertNull(repo.loadRoute("r1").surfaceSections.get(0).name);
    }

    @Test
    public void setSurfaceSectionName_updatesByIndex() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL, null);

        repo.setSurfaceSectionName("r1", 0, "Hernoemd");

        assertEquals("Hernoemd", repo.loadRoute("r1").surfaceSections.get(0).name);
    }
```

> The existing tests call the old 4-arg `addSurfaceSection`. Step 3 keeps a 4-arg overload, so those tests keep compiling unchanged.

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: FAIL — `cannot find symbol: method addSurfaceSection(...,String)` / `setSurfaceSectionName`.

- [ ] **Step 3: Replace `addSurfaceSection` with a named version + a 4-arg overload**

In `RouteRepository.java`, change the existing `addSurfaceSection` method (lines ~471–501) so the implementation takes a name, and add a backward-compatible 4-arg overload that passes `null`. Replace the whole method with:

```java
    /** Backwards-compatible overload: adds a section with no name. */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) throws IOException {
        addSurfaceSection(routeId, startDistance, endDistance, surfaceType, null);
    }

    /**
     * Adds a user-defined surface override for an arbitrary route stretch.
     * Distances are integer metres. The surface type is clamped to a legal value.
     * A blank/empty name is stored as null. The list is kept sorted by startDistance.
     *
     * @throws IllegalArgumentException if start &lt; 0, end &lt;= start, or end &gt; route length.
     */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        int routeLength = routeLengthMeters(route);
        if (startDistance < 0) {
            throw new IllegalArgumentException("startDistance must be >= 0: " + startDistance);
        }
        if (endDistance <= startDistance) {
            throw new IllegalArgumentException(
                    "endDistance must be > startDistance: " + startDistance + ".." + endDistance);
        }
        if (routeLength > 0 && endDistance > routeLength) {
            throw new IllegalArgumentException(
                    "endDistance " + endDistance + " exceeds route length " + routeLength);
        }

        StoredSurfaceSection section = new StoredSurfaceSection();
        section.startDistance = startDistance;
        section.endDistance   = endDistance;
        section.surfaceType   = SurfaceType.fromInt(surfaceType);
        section.name          = (name == null || name.trim().isEmpty()) ? null : name.trim();

        if (route.surfaceSections == null) {
            route.surfaceSections = new ArrayList<>();
        }
        route.surfaceSections.add(section);
        route.surfaceSections.sort((a, b) -> Integer.compare(a.startDistance, b.startDistance));

        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }
```

- [ ] **Step 4: Add `setSurfaceSectionName`**

In `RouteRepository.java`, add directly after `deleteSurfaceSection` (after its closing brace, around line 518):

```java
    /**
     * Renames the surface section at the given index (after sorting by startDistance).
     * A blank/empty name clears it (stored as null). Out-of-range indices are ignored.
     */
    public void setSurfaceSectionName(String routeId, int index, String name) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.surfaceSections == null
                || index < 0 || index >= route.surfaceSections.size()) {
            Log.w(TAG, "setSurfaceSectionName: index out of range: " + index);
            return;
        }
        route.surfaceSections.get(index).name =
                (name == null || name.trim().isEmpty()) ? null : name.trim();
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
    }
```

- [ ] **Step 5: Run the full class to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: PASS (existing tests + 3 new = all green).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java
git commit -m "feat(android): name surface sections (add with name + rename)"
```

---

## Task 4: Checkpoint builder helper (pure)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/SurfaceCheckpointTest.java`

A pure static helper that, given a route's `distances/lats/lons` and a `[startDist, endDist]` range, returns a packed `int[] = [dist, latInt, lonInt, ...]` of checkpoints: the start, one every `CHECKPOINT_SPACING_M`, and the end, capped at `MAX_CHECKPOINTS_PER_SECTION`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/service/SurfaceCheckpointTest.java`:

```java
package nl.paree.climbpro.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceCheckpointTest {

    // A straight 0..1000 m axis, lat climbing 51.0000..51.0090, lon constant 5.0.
    private static final double[] DIST = {0, 250, 500, 750, 1000};
    private static final double[] LAT  = {51.0000, 51.0025, 51.0050, 51.0075, 51.0090};
    private static final double[] LON  = {5.0, 5.0, 5.0, 5.0, 5.0};

    @Test
    public void buildCheckpoints_startEveryStepAndEnd() {
        // Range 0..1000, spacing 200 -> 0,200,400,600,800,1000 = 6 checkpoints.
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 1000);
        assertEquals(6 * 3, cp.length);
        assertEquals(0, cp[0]);                 // first checkpoint distance = start
        assertEquals(1000, cp[cp.length - 3]);  // last checkpoint distance = end
    }

    @Test
    public void buildCheckpoints_encodesLatLonTimes100000() {
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 0 /*=>clamped*/);
        // start checkpoint encodes nearest route coord to distance 0 = 51.0,5.0
        assertEquals((int) Math.round(51.0 * 100000), cp[1]);
        assertEquals((int) Math.round(5.0 * 100000), cp[2]);
    }

    @Test
    public void buildCheckpoints_respectsMaxCount() {
        // A very long range with tiny spacing would exceed the cap; assert the cap holds.
        int[] cp = ClimbPayloadBuilder.buildCheckpoints(DIST, LAT, LON, 0, 1000);
        assertTrue("never more than MAX*3 ints",
                cp.length <= ClimbPayloadBuilder.MAX_CHECKPOINTS_PER_SECTION * 3);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceCheckpointTest`
Expected: FAIL — `cannot find symbol: method buildCheckpoints` / field `MAX_CHECKPOINTS_PER_SECTION`.

- [ ] **Step 3: Implement the helper**

In `ClimbPayloadBuilder.java`, add the constants near the top of the class (after `SCHEMA_VERSION`, around line 32):

```java
    private static final int SCHEMA_VERSION = 3;

    /** One checkpoint at the section start, one every 200 m, and one at the end. */
    static final int CHECKPOINT_SPACING_M = 200;
    /** Hard cap on checkpoints per section to bound the watch payload. */
    static final int MAX_CHECKPOINTS_PER_SECTION = 12;
```

And add this static helper near the other packers (e.g. directly after `buildCalib`, around line 162):

```java
    /**
     * Packs GPS checkpoints for the route stretch [startDist, endDist] into
     * [dist, latInt, lonInt, ...] triples. Emits the start, one every
     * {@link #CHECKPOINT_SPACING_M}, and the end, capped at
     * {@link #MAX_CHECKPOINTS_PER_SECTION}. latInt/lonInt = degrees × 100000.
     * Each checkpoint's coordinate is the route point whose distance is closest.
     */
    static int[] buildCheckpoints(double[] distances, double[] lats, double[] lons,
                                  int startDist, int endDist) {
        if (distances == null || distances.length == 0
                || lats == null || lons == null) {
            return new int[0];
        }
        int routeLen = (int) Math.round(distances[distances.length - 1]);
        int start = Math.max(0, Math.min(startDist, routeLen));
        int end   = Math.max(start, Math.min(endDist, routeLen));

        // Collect target distances: start, start+spacing, ..., end (dedup end).
        List<Integer> targets = new ArrayList<>();
        for (int d = start; d < end; d += CHECKPOINT_SPACING_M) targets.add(d);
        targets.add(end);
        // Enforce the cap by thinning evenly while always keeping first and last.
        if (targets.size() > MAX_CHECKPOINTS_PER_SECTION) {
            List<Integer> thinned = new ArrayList<>(MAX_CHECKPOINTS_PER_SECTION);
            int last = targets.size() - 1;
            for (int i = 0; i < MAX_CHECKPOINTS_PER_SECTION; i++) {
                int idx = (int) Math.round(i * (double) last / (MAX_CHECKPOINTS_PER_SECTION - 1));
                thinned.add(targets.get(idx));
            }
            targets = thinned;
        }

        int[] out = new int[targets.size() * 3];
        for (int i = 0; i < targets.size(); i++) {
            int target = targets.get(i);
            int nearest = nearestIndex(distances, target);
            out[i * 3]     = target;
            out[i * 3 + 1] = (int) Math.round(lats[nearest] * 100000);
            out[i * 3 + 2] = (int) Math.round(lons[nearest] * 100000);
        }
        return out;
    }

    /** Index of the distances[] entry closest to targetM. */
    private static int nearestIndex(double[] distances, int targetM) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - targetM);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - targetM);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceCheckpointTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/SurfaceCheckpointTest.java
git commit -m "feat(protocol): packed GPS checkpoint builder for surface sections"
```

---

## Task 5: Rewrite `buildSurfaceSectionPayload` to object array with name + checkpoints

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/SurfaceSectionPayloadTest.java`

`surfSec` changes from a packed int-triple array to an **array of objects** `{s,e,t,n?,cp}`. Surface sections and *qualifying* flat segments (name set OR non-UNKNOWN surface) are merged and sorted by start distance.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/service/SurfaceSectionPayloadTest.java`:

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurfaceSectionPayloadTest {

    private static StoredRoute route() {
        StoredRoute r = new StoredRoute();
        r.routeId   = "r1";
        r.name      = "Demo";
        r.distances = new double[]{0, 1000, 2000, 3000, 4000, 5000};
        r.lats      = new double[]{51.0, 51.01, 51.02, 51.03, 51.04, 51.05};
        r.lons      = new double[]{5.0, 5.0, 5.0, 5.0, 5.0, 5.0};
        r.climbs    = new ArrayList<>();
        return r;
    }

    @Test
    public void surfaceSection_emittedAsObjectWithNameAndCheckpoints() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 1000; s.endDistance = 2000;
        s.surfaceType = SurfaceType.GRAVEL; s.name = "Grind";
        r.surfaceSections = new ArrayList<>();
        r.surfaceSections.add(s);

        JsonNode payload = build(r);
        JsonNode arr = payload.get("surfSec");
        assertEquals(1, arr.size());
        JsonNode sec = arr.get(0);
        assertEquals(1000, sec.get("s").asInt());
        assertEquals(2000, sec.get("e").asInt());
        assertEquals(SurfaceType.GRAVEL, sec.get("t").asInt());
        assertEquals("Grind", sec.get("n").asText());
        assertTrue("checkpoints present", sec.get("cp").size() >= 3);
        // first checkpoint distance is the section start
        assertEquals(1000, sec.get("cp").get(0).asInt());
    }

    @Test
    public void qualifyingFlatSegment_isIncluded_plainFlatSegment_isSkipped() throws Exception {
        StoredRoute r = route();
        StoredFlatSegment named = new StoredFlatSegment();
        named.startDistance = 3000; named.endDistance = 4000; named.length = 1000;
        named.surfaceType = SurfaceType.UNKNOWN; named.name = "Vlak stuk";
        StoredFlatSegment plain = new StoredFlatSegment();
        plain.startDistance = 4000; plain.endDistance = 5000; plain.length = 1000;
        plain.surfaceType = SurfaceType.UNKNOWN; plain.name = null;
        r.flatSegments = new ArrayList<>();
        r.flatSegments.add(named);
        r.flatSegments.add(plain);

        JsonNode arr = build(r).get("surfSec");
        assertEquals("only the named flat segment is sent", 1, arr.size());
        assertEquals("Vlak stuk", arr.get(0).get("n").asText());
    }

    @Test
    public void mergedSections_sortedByStart() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 3000; s.endDistance = 3500; s.surfaceType = SurfaceType.DIRT;
        r.surfaceSections = new ArrayList<>(); r.surfaceSections.add(s);
        StoredFlatSegment f = new StoredFlatSegment();
        f.startDistance = 1000; f.endDistance = 2000; f.length = 1000;
        f.surfaceType = SurfaceType.GRAVEL;
        r.flatSegments = new ArrayList<>(); r.flatSegments.add(f);

        JsonNode arr = build(r).get("surfSec");
        assertEquals(2, arr.size());
        assertEquals(1000, arr.get(0).get("s").asInt());
        assertEquals(3000, arr.get(1).get("s").asInt());
    }

    @Test
    public void emptyRoute_emitsEmptySurfSec() throws Exception {
        JsonNode arr = build(route()).get("surfSec");
        assertTrue(arr.isArray());
        assertEquals(0, arr.size());
    }

    @Test
    public void nameOmittedWhenAbsent() throws Exception {
        StoredRoute r = route();
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = 1000; s.endDistance = 2000; s.surfaceType = SurfaceType.MIXED;
        r.surfaceSections = new ArrayList<>(); r.surfaceSections.add(s);
        assertFalse(build(r).get("surfSec").get(0).has("n"));
    }

    private static JsonNode build(StoredRoute r) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] bytes = new ClimbPayloadBuilder(mapper).buildSurfaceSectionPayload(r);
        return mapper.readTree(bytes);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceSectionPayloadTest`
Expected: FAIL — the current builder emits packed int triples (no `s`/`n`/`cp` keys, no flat-segment merge).

- [ ] **Step 3: Rewrite `buildSurfaceSectionPayload`**

In `ClimbPayloadBuilder.java`, replace the entire existing `buildSurfaceSectionPayload` method (lines ~83–107) with:

```java
    /**
     * Lean payload for the surface-sections datafield: no climbs, and a 'surfSec'
     * array of section objects {s,e,t,n?,cp}. 'cp' is a packed checkpoint array
     * [dist, latInt, lonInt, ...]. Both user surface sections and qualifying flat
     * segments (named OR with a known surface) are merged and sorted by start
     * distance. An empty array is sent deliberately to clear a stale route.
     */
    public byte[] buildSurfaceSectionPayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        payload.put("climbs", new ArrayList<>());

        // Merge surface sections + qualifying flat segments into one sorted list.
        List<int[]> ranges = new ArrayList<>();   // {start, end, surfaceType}
        List<String> names = new ArrayList<>();
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection s : route.surfaceSections) {
                ranges.add(new int[]{s.startDistance, s.endDistance,
                        SurfaceType.fromInt(s.surfaceType)});
                names.add(s.name);
            }
        }
        if (route.flatSegments != null) {
            for (StoredFlatSegment f : route.flatSegments) {
                boolean qualifies = (f.name != null && !f.name.isEmpty())
                        || f.surfaceType != SurfaceType.UNKNOWN;
                if (!qualifies) continue;
                ranges.add(new int[]{f.startDistance, f.endDistance,
                        SurfaceType.fromInt(f.surfaceType)});
                names.add(f.name);
            }
        }
        // Sort by start distance, keeping name aligned. Build an index permutation.
        Integer[] order = new Integer[ranges.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) ->
                Integer.compare(ranges.get(a)[0], ranges.get(b)[0]));

        List<Map<String, Object>> surfSec = new ArrayList<>(order.length);
        for (int oi : order) {
            int[] rg = ranges.get(oi);
            Map<String, Object> sec = new LinkedHashMap<>();
            sec.put("s", rg[0]);
            sec.put("e", rg[1]);
            sec.put("t", rg[2]);
            String secName = names.get(oi);
            if (secName != null && secName.length() <= 24) sec.put("n", secName);
            sec.put("cp", buildCheckpoints(route.distances, route.lats, route.lons,
                    rg[0], rg[1]));
            surfSec.add(sec);
        }
        payload.put("surfSec", surfSec);
        return mapper.writeValueAsBytes(payload);
    }
```

Ensure the import for `StoredFlatSegment` is present at the top of the file:

```java
import nl.paree.climbpro.data.route.StoredFlatSegment;
```

(`SurfaceType` is referenced fully-qualified elsewhere in this file as `nl.paree.climbpro.domain.segment.SurfaceType`; either add an import `import nl.paree.climbpro.domain.segment.SurfaceType;` or fully-qualify the three `SurfaceType.fromInt` / `SurfaceType.UNKNOWN` uses above. Prefer adding the import.)

- [ ] **Step 4: Run to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceSectionPayloadTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Update the class header comment**

In `ClimbPayloadBuilder.java`, the class Javadoc (lines ~16–29) documents the format. Update the `surfSec` line so it describes the object array, e.g. replace any `surfSec triples` mention with:

```
 *   surfSec:[{s,e,t,n?,cp:[dist,latInt,lonInt, ...]}, ...]   // surface + flat sections
```

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/SurfaceSectionPayloadTest.java
git commit -m "feat(protocol): surfSec as object array with name + GPS checkpoints, merging flat segments"
```

---

## Task 6: Schema + example + round-trip coverage

**Files:**
- Modify: `protocol/schema.json`
- Create: `protocol/examples/route_mode_surface.json`
- Modify: `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java`

The canonical schema models the logical `SurfaceSection`. We add `name` and `checkpoints` to that definition and a new example that exercises them, then register the example with the round-trip schema-validation test.

- [ ] **Step 1: Extend the `SurfaceSection` definition in `schema.json`**

In `protocol/schema.json`, replace the `SurfaceSection` definition (lines ~133–155) with:

```json
    "SurfaceSection": {
      "type": "object",
      "additionalProperties": false,
      "required": ["startDistance", "endDistance", "surfaceType"],
      "properties": {
        "startDistance": {
          "description": "Metres from route start where the section begins.",
          "type": "integer",
          "minimum": 0
        },
        "endDistance": {
          "description": "Metres from route start where the section ends. Must exceed startDistance.",
          "type": "integer",
          "minimum": 1
        },
        "surfaceType": {
          "description": "SurfaceType constant: 0=asphalt 1=gravel 2=dirt 3=cobblestone 4=mixed 5=unknown.",
          "type": "integer",
          "minimum": 0,
          "maximum": 5
        },
        "name": {
          "description": "Optional user-supplied display name for the section, shown on the watch surface datafield.",
          "type": "string",
          "maxLength": 24
        },
        "checkpoints": {
          "description": "GPS checkpoints along the section for distance-correction on the watch. Wire encoding: packed int array 'cp' of [distanceFromRouteStart, latInt, lonInt, ...] where latInt/lonInt = degrees x 100000.",
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["distance", "lat", "lon"],
            "properties": {
              "distance": { "type": "integer", "minimum": 0 },
              "lat": { "type": "number", "minimum": -90.0, "maximum": 90.0 },
              "lon": { "type": "number", "minimum": -180.0, "maximum": 180.0 }
            }
          }
        }
      }
    }
```

Also update the `surfaceSections` property description (lines ~32–38) so the wire-encoding note matches:

```json
    "surfaceSections": {
      "description": "Optional user-defined surface overrides and named flat sections for arbitrary stretches of the route (route mode only), ordered by startDistance. Wire encoding: 'surfSec' array of objects {s:startDistance, e:endDistance, t:surfaceType, n:name?, cp:[distance,latInt,lonInt, ...]}.",
      "type": "array",
      "minItems": 0,
      "maxItems": 32,
      "items": { "$ref": "#/definitions/SurfaceSection" }
    },
```

- [ ] **Step 2: Create the example payload**

Create `protocol/examples/route_mode_surface.json` (logical shape that validates against the schema):

```json
{
  "v": 1,
  "mode": "route",
  "routeId": "demo_surface_001",
  "name": "Surface demo loop",
  "surfaceSections": [
    {
      "startDistance": 1000,
      "endDistance": 2000,
      "surfaceType": 1,
      "name": "Grindstrook",
      "checkpoints": [
        { "distance": 1000, "lat": 51.01000, "lon": 5.00000 },
        { "distance": 1200, "lat": 51.01200, "lon": 5.00100 },
        { "distance": 1400, "lat": 51.01400, "lon": 5.00200 },
        { "distance": 2000, "lat": 51.02000, "lon": 5.00400 }
      ]
    },
    {
      "startDistance": 3000,
      "endDistance": 3600,
      "surfaceType": 3,
      "name": "Kasseien",
      "checkpoints": [
        { "distance": 3000, "lat": 51.03000, "lon": 5.00800 },
        { "distance": 3600, "lat": 51.03600, "lon": 5.01000 }
      ]
    }
  ],
  "climbs": []
}
```

> Note: this example uses the **logical** schema shape (`surfaceSections` with object checkpoints). The actual wire payload uses the packed `surfSec`/`cp` form, which is covered by the Java unit test in Task 5. This split matches the project's existing convention (the schema/examples model the logical payload; the builder packs it).

- [ ] **Step 3: Register the example in the round-trip test**

In `ProtocolRoundTripTest.java`, add the new file to the `@Parameters` list (around line 44):

```java
        return Arrays.asList(new Object[][]{
                {"route_mode_short.json"},
                {"route_mode_full.json"},
                {"radius_mode.json"},
                {"route_mode_surface.json"},
        });
```

- [ ] **Step 4: Run the round-trip test**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest`
Expected: PASS — `route_mode_surface.json` validates against the updated schema.

> If the test resource is not found, confirm `protocol/examples/` is wired into `sourceSets.test.resources.srcDirs` in `android/app/build.gradle` (the existing examples already are).

- [ ] **Step 5: Commit**

```bash
git add protocol/schema.json protocol/examples/route_mode_surface.json android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java
git commit -m "feat(protocol): schema + example for named surface sections with GPS checkpoints"
```

---

## Task 7: ViewModel — thread name through, expose rename

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`

Thin wrappers over the repository methods from Tasks 2–3, run on the existing background executor, reloading the route afterwards. No unit test (the repository logic is already covered; the ViewModel has no test harness) — verified manually in Task 8.

- [ ] **Step 1: Replace `setFlatSegmentSurface` with `updateFlatSegment`**

In `RouteDetailViewModel.java`, find `setFlatSegmentSurface` (around line 93) and add a sibling that also carries the name (keep the old one if other callers exist; the only caller is the dialog we change in Task 8, so you may replace it):

```java
    /** Sets a flat segment's surface type and optional name (phone + watch). */
    public void updateFlatSegment(String routeId, int startDistance,
                                  int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.updateFlatSegment(routeId, startDistance, surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }
```

- [ ] **Step 2: Add the `name` parameter to `addSurfaceSection` and add `setSurfaceSectionName`**

In `RouteDetailViewModel.java`, change `addSurfaceSection` (around line 106) to take a name and pass it through, and add a rename method after `deleteSurfaceSection`:

```java
    /** Adds a user-defined surface override for an arbitrary stretch (phone + watch). */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType, String name) {
        executor.execute(() -> {
            try {
                routeRepo.addSurfaceSection(routeId, startDistance, endDistance,
                        surfaceType, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (IllegalArgumentException e) {
                error.postValue("Ongeldig stuk: " + e.getMessage());
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Renames the surface section at the given index (phone + watch). */
    public void setSurfaceSectionName(String routeId, int index, String name) {
        executor.execute(() -> {
            try {
                routeRepo.setSurfaceSectionName(routeId, index, name);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Hernoemen mislukt: " + e.getMessage());
            }
        });
    }
```

- [ ] **Step 3: Verify compilation**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL` (if the old `setFlatSegmentSurface`/3-arg `addSurfaceSection` are still referenced by the Activity, that reference is updated in Task 8 — compile after Task 8 if needed, or keep the old methods as thin delegates temporarily).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java
git commit -m "feat(android): ViewModel name plumbing for flat segments and surface sections"
```

---

## Task 8: Phone UI — name inputs + rename

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`

We give the flat-segment dialog a name field (custom view with name input + surface picker), add a name field to the add-section dialog, and let the manager rename an existing section.

- [ ] **Step 1: Rebuild `showFlatSurfaceDialog` as a name + surface custom-view dialog**

In `RouteDetailActivity.java`, replace `showFlatSurfaceDialog` (lines ~164–178) with:

```java
    private void showFlatSurfaceDialog(StoredFlatSegment flat) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Naam (optioneel)");
        nameInput.setSingleLine(true);
        if (flat.name != null) nameInput.setText(flat.name);
        layout.addView(nameInput);

        final android.widget.Spinner surface = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, SURFACE_LABELS_NL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(adapter);
        surface.setSelection(SurfaceType.fromInt(flat.surfaceType));
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Vlak segment")
                .setView(layout)
                .setPositiveButton("Opslaan", (dialog, which) ->
                        viewModel.updateFlatSegment(routeId, flat.startDistance,
                                surface.getSelectedItemPosition(),
                                nameInput.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }
```

(`SURFACE_LABELS_NL` and `EditText` are already available in this class.)

- [ ] **Step 2: Add a name field to the add-section dialog**

In `showAddSurfaceSectionDialog` (lines ~218–260), add a name `EditText` as the first field and pass its value through. Insert directly after the `layout.setPadding(...)` line:

```java
        final android.widget.EditText nameInput = new android.widget.EditText(this);
        nameInput.setHint("Naam (optioneel)");
        nameInput.setSingleLine(true);
        layout.addView(nameInput);
```

And change the `viewModel.addSurfaceSection(...)` call in the positive button to pass the name:

```java
                    viewModel.addSurfaceSection(routeId, startM, endM,
                            surface.getSelectedItemPosition(),
                            nameInput.getText().toString());
```

- [ ] **Step 3: Show names in the manager and offer rename**

In `showSurfaceSectionsManager` (lines ~180–207), change the row label to include the name when present, and replace the item click so it offers rename + delete. Replace the row-building loop body and the `.setItems(...)` block:

```java
            rows = new String[current.size()];
            for (int i = 0; i < current.size(); i++) {
                nl.paree.climbpro.data.route.StoredSurfaceSection s = current.get(i);
                String label = String.format("%.1f–%.1f km · %s",
                        s.startDistance / 1000.0, s.endDistance / 1000.0,
                        SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)]);
                rows[i] = (s.name != null ? s.name + " — " : "") + label;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ondergrond-stukken")
                .setItems(rows, (dialog, which) -> {
                    if (!current.isEmpty()) showSectionActions(which, current.get(which).name);
                })
                .setPositiveButton("Toevoegen", (d, w) -> showAddSurfaceSectionDialog())
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void showSectionActions(int index, String currentName) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Hernoemen", "Verwijderen"}, (d, which) -> {
                    if (which == 0) showRenameSectionDialog(index, currentName);
                    else confirmDeleteSection(index);
                })
                .show();
    }

    private void showRenameSectionDialog(int index, String currentName) {
        final EditText input = new EditText(this);
        input.setHint("Naam");
        input.setSingleLine(true);
        if (currentName != null) input.setText(currentName);
        new AlertDialog.Builder(this)
                .setTitle("Hernoem stuk")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.setSurfaceSectionName(routeId, index,
                                input.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`. (If it fails on a removed `setFlatSegmentSurface`/3-arg `addSurfaceSection`, finish removing those old ViewModel methods or update remaining callers.)

- [ ] **Step 5: Assemble to link resources**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual verification (emulator/device, optional)**

Open a route → long-press a flat segment → set name "Bospad" + Gravel → Opslaan. Open "Ondergrond-stukken" → Toevoegen → name "Grind", 1.0–2.0 km, Gravel → confirm it lists as "Grind — 1.0–2.0 km · Gravel". Tap it → Hernoemen → "Grind 2" → confirm relabel. (If no emulator, note deferred and rely on the assemble passing.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java
git commit -m "feat(android): name inputs for flat segments and surface sections + rename"
```

---

## Task 9: Watch — parse new `surfSec` objects + GPS distance correction

**Files:**
- Modify: `garmin-surface/source/SurfaceData.mc`
- Modify: `garmin-surface/source/SurfaceFieldView.mc`

`SurfaceData` learns the new object array (with `n` and `cp`), stores checkpoints in flat parallel arrays, and gains `correctElapsed(elapsed, posDegrees)` which snaps a smoothed `distanceOffset` to the nearest checkpoint within `SNAP_M`. `SurfaceFieldView.compute` feeds it the current GPS position.

- [ ] **Step 1: Add checkpoint/name/offset state to `SurfaceData`**

In `SurfaceData.mc`, add fields after the existing `secType;` declaration (after line 17) and to `initialize`:

```java
    var secType;    // SurfaceType constant 0..5
    var secName;    // String or null per section

    // Flat checkpoint store, shared across sections.
    const MAX_CP = 256;
    const SNAP_M = 40;          // only snap when a checkpoint is within this many metres
    var cpDist;                 // metres from route start
    var cpLat;                  // degrees * 100000
    var cpLon;                  // degrees * 100000
    var secCpOff;               // first cp index for section i
    var secCpCnt;               // cp count for section i
    var totalCp = 0;
    var distanceOffset = 0;     // smoothed GPS correction, metres
```

And in `initialize`, allocate them alongside the existing arrays:

```java
    function initialize() {
        secStart = new [MAX_SECTIONS];
        secEnd   = new [MAX_SECTIONS];
        secType  = new [MAX_SECTIONS];
        secName  = new [MAX_SECTIONS];
        secCpOff = new [MAX_SECTIONS];
        secCpCnt = new [MAX_SECTIONS];
        cpDist   = new [MAX_CP];
        cpLat    = new [MAX_CP];
        cpLon    = new [MAX_CP];
    }
```

- [ ] **Step 2: Rewrite `parse` for the object array**

In `SurfaceData.mc`, replace the body of `parse` from the `surfSec` handling onward (the block from `var surfSec = msg.get("surfSec");` to the end of the method) with:

```java
        var surfSec = msg.get("surfSec");
        if (!(surfSec instanceof Toybox.Lang.Array)) { return false; }

        routeId   = msg.get("routeId");
        routeName = msg.get("name");

        var n = surfSec.size();
        if (n > MAX_SECTIONS) { n = MAX_SECTIONS; }
        count = n;
        totalCp = 0;
        distanceOffset = 0;
        for (var i = 0; i < n; i++) {
            var sec = surfSec[i];
            if (!(sec instanceof Toybox.Lang.Dictionary)) {
                secStart[i] = 0; secEnd[i] = 0; secType[i] = 5;
                secName[i] = null; secCpOff[i] = totalCp; secCpCnt[i] = 0;
                continue;
            }
            secStart[i] = numOr(sec.get("s"), 0);
            secEnd[i]   = numOr(sec.get("e"), 0);
            var t = sec.get("t");
            secType[i]  = (t instanceof Toybox.Lang.Number && t >= 0 && t <= 5) ? t : 5;
            secName[i]  = sec.get("n");   // String or null

            secCpOff[i] = totalCp;
            secCpCnt[i] = 0;
            var cp = sec.get("cp");
            if (cp instanceof Toybox.Lang.Array) {
                var triples = cp.size() / 3;
                for (var k = 0; k < triples && totalCp < MAX_CP; k++) {
                    cpDist[totalCp] = numOr(cp[k * 3], 0);
                    cpLat[totalCp]  = numOr(cp[k * 3 + 1], 0);
                    cpLon[totalCp]  = numOr(cp[k * 3 + 2], 0);
                    totalCp++;
                    secCpCnt[i]++;
                }
            }
        }
        payloadReceived = true;
        currentIdx = -1;
        nextIdx = -1;
        Sys.println("SurfaceData: " + count + " sections, " + totalCp + " checkpoints");
        return true;
    }

    hidden function numOr(v, fallback) {
        return (v instanceof Toybox.Lang.Number) ? v : fallback;
    }
```

- [ ] **Step 3: Add `correctElapsed`**

In `SurfaceData.mc`, add this method directly before `updateProgress`:

```java
    // posDegrees = [lat, lon] in decimal degrees, or null when no GPS fix.
    // Returns elapsed corrected by a smoothed offset snapped to the nearest
    // checkpoint within SNAP_M. Distance stays the primary matching axis.
    function correctElapsed(elapsed, posDegrees) {
        if (posDegrees == null || totalCp == 0) { return elapsed + distanceOffset; }
        var lat = (posDegrees[0] * 100000).toNumber();
        var lon = (posDegrees[1] * 100000).toNumber();
        var bestM = SNAP_M + 1;
        var bestDist = -1;
        for (var i = 0; i < totalCp; i++) {
            var m = approxMeters(lat, lon, cpLat[i], cpLon[i]);
            if (m < bestM) { bestM = m; bestDist = cpDist[i]; }
        }
        if (bestDist >= 0) {
            var raw = bestDist - elapsed;
            distanceOffset = ((distanceOffset * 3) + raw) / 4;  // low-pass smoothing
        }
        return elapsed + distanceOffset;
    }

    // Equirectangular approximation. Inputs are degrees * 100000.
    hidden function approxMeters(latA, lonA, latB, lonB) {
        var dLat = (latA - latB) * 0.011132;                  // 1.1132 m per 1e-5 deg
        var meanLatRad = (latA / 100000.0) * 0.0174533;       // deg -> rad
        var dLon = (lonA - lonB) * 0.011132 * Math.cos(meanLatRad);
        return Math.sqrt((dLat * dLat) + (dLon * dLon));
    }
```

> `Math` requires `using Toybox.Math as Math;` — add it to the top imports if not already present. (`Sys` is already imported.)

- [ ] **Step 4: Feed GPS position from the view's `compute`**

In `SurfaceFieldView.mc`, replace the `compute` method (lines ~24–32) with:

```java
    function compute(info) {
        var data = App.getApp().surfaceData;
        if (data == null || !data.payloadReceived) { return; }
        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        var pos = null;
        if (info != null && info has :currentLocation && info.currentLocation != null) {
            pos = info.currentLocation.toDegrees();  // [lat, lon] decimal degrees
        }
        data.updateProgress(data.correctElapsed(elapsed, pos));
    }
```

- [ ] **Step 5: Compile the surface datafield**

Run: `cd garmin-surface && monkeyc -o bin/surface.prg -f monkey.jungle -y <developer_key>`
Expected: build succeeds with no errors. (If `monkeyc` is not on PATH, use the full SDK path; see `Documentation/SETUP.md`.)

- [ ] **Step 6: Commit**

```bash
git add garmin-surface/source/SurfaceData.mc garmin-surface/source/SurfaceFieldView.mc
git commit -m "feat(surface-field): parse named surfSec objects + GPS checkpoint distance correction"
```

---

## Task 10: Watch — show the name as the section title

**Files:**
- Modify: `garmin-surface/source/SurfaceFieldView.mc`

The current view shows the surface type as the title. Per the chosen design, the **name** becomes the title with the surface type small underneath; fall back to the surface type when no name is set.

- [ ] **Step 1: Add a title helper**

In `SurfaceFieldView.mc`, add a helper method (e.g. after `formatDist`):

```java
    // Title = user name when present, else the surface-type label.
    hidden function titleFor(data, idx) {
        var nm = data.secName[idx];
        if (nm != null && nm.length() > 0) { return nm; }
        return SURF_NAMES[data.secType[idx]];
    }
```

- [ ] **Step 2: Show name-as-title in `drawCurrentSection`**

In `SurfaceFieldView.mc`, replace the title/subtitle lines inside `drawCurrentSection` (the two `drawText` calls at `h/4` and `h/2`, lines ~67–70) with:

```java
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_MEDIUM, titleFor(data, data.currentIdx),
            Gfx.TEXT_JUSTIFY_CENTER);
        // Surface type small under the name (only meaningful when a name overrides it).
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 8, Gfx.FONT_XTINY, SURF_NAMES[t],
            Gfx.TEXT_JUSTIFY_CENTER);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 + 8, Gfx.FONT_SMALL,
            "nog " + formatDist(data.remainingInSection), Gfx.TEXT_JUSTIFY_CENTER);
```

- [ ] **Step 3: Use name-as-title in the next-section preview**

In `drawCurrentSection`, the "dan:" preview (lines ~72–77) and `drawNextOnly` (lines ~80–88) reference `SURF_NAMES[nt]`. Replace those `SURF_NAMES[nt]` lookups with `titleFor(data, data.nextIdx)` so the upcoming section also shows its name. For `drawNextOnly`, replace the `SURF_NAMES[nt]` argument with `titleFor(data, data.nextIdx)`; the `var nt = ...` line may stay (still used for nothing else) or be removed.

- [ ] **Step 4: Compile the surface datafield**

Run: `cd garmin-surface && monkeyc -o bin/surface.prg -f monkey.jungle -y <developer_key>`
Expected: build succeeds.

- [ ] **Step 5: Manual simulator check**

Run the surface datafield in the FR255 Music simulator, push `route_mode_surface.json`-style data (or activate a route from the watch app), and simulate riding through a named section: the section's **name** shows as the large title, the surface type small under it, and remaining distance below. Drive the simulated position slightly off the recorded line and confirm the section boundary still flips near the right place (checkpoint correction).

- [ ] **Step 6: Commit**

```bash
git add garmin-surface/source/SurfaceFieldView.mc
git commit -m "feat(surface-field): show section name as title with surface type subtitle"
```

---

## Task 11: Documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`

Record the reversed decision (flat segments now reach the watch when named/surfaced), the new `surfSec` object shape with checkpoints, and the watch's distance-primary + GPS-correction matching.

- [ ] **Step 1: Update the surface datafield paragraph**

In `Documentation/ARCHITECTURE.md`, replace the "Surface-sections datafield" paragraph in the "Watch app, active-route relay & surface datafield (2026-06-10)" section (around lines 343) with:

```markdown
**Surface-sections datafield** (`garmin-surface/`, app ID `00112233...`): shows the
user-defined surface/flat section the rider is in (name as title, surface type small
underneath, remaining metres) and the next one. It receives a lean payload
`{v:3, mode:"route", routeId, name, climbs:[], surfSec:[{s,e,t,n?,cp:[dist,latInt,lonInt, ...]}, ...]}`
built by `ClimbPayloadBuilder.buildSurfaceSectionPayload`. The `surfSec` array merges
`StoredRoute.surfaceSections` with **qualifying** `StoredFlatSegment`s (those the user has
named or assigned a surface to) — this **reverses** the earlier 2026-06-10 decision to
never send flat segments, but only for ones the user has explicitly touched; untouched flat
segments are still skipped. Each section carries an optional `name` and a packed checkpoint
array `cp = [distanceFromRouteStart, latInt, lonInt, ...]` (latInt/lonInt = degrees×100000,
mirroring climb `calib`), computed at build time from the route geometry. An empty `surfSec`
is sent on purpose to clear stale sections. Single-climb activation sends no surface payload.

On the watch, `SurfaceData` keeps `elapsedDistance` as the primary matching axis and applies
a smoothed `distanceOffset` snapped to the nearest checkpoint within 40 m (`correctElapsed`),
so GPS coordinates correct drift without replacing distance matching.
```

- [ ] **Step 2: Update the "Custom surface sections (phone-only)" section heading + body**

In the Domain Model section (around lines 231–245), the "(phone-only)" framing is now stale. Update the heading to "Custom surface sections" and adjust the body to note they are now serialised to the watch via `surfSec` (object array with name + checkpoints), survive re-import, and that flat-segment names follow the same pattern.

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md
git commit -m "docs: named surface/flat sections with GPS checkpoints now reach the watch"
```

---

## Final verification

- [ ] **Step 1: Full Android unit-test suite**

Run: `cd android && ./gradlew test`
Expected: `BUILD SUCCESSFUL` — including `FlatSegmentNameRepositoryTest`, `SurfaceSectionRepositoryTest`, `SurfaceCheckpointTest`, `SurfaceSectionPayloadTest`, and `ProtocolRoundTripTest`.

- [ ] **Step 2: Android debug assemble**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Surface datafield builds**

Run: `cd garmin-surface && monkeyc -o bin/surface.prg -f monkey.jungle -y <developer_key>`
Expected: build succeeds.

---

## Self-review notes (for the implementer)

- **Spec coverage:**
  - *"vlakke segmenten ook controleren met coordinaten + tussentijdse checks dmv coordinaten"* → checkpoints built per section (Task 4), parsed on the watch and used to correct distance every tick (Task 9 `correctElapsed`, snapped to the nearest checkpoint within 40 m).
  - *"de coordinaten moet je ook meesturen naar de Garmin"* → `cp` packed array on every `surfSec` object (Task 5), schema + example (Task 6), parsed by `SurfaceData` (Task 9).
  - *"vlakke segmenten een naampje geven, ook zien op het horloge"* → name field on both models (Task 1), repository set/preserve (Tasks 2–3), phone UI (Task 8), wire `n` field (Task 5), shown as the title on the watch (Task 10).
  - *"allebei"* (both flat segments and surface sections) → both are named and merged into the same watch list (Tasks 2, 3, 5); flat segments included only when named or surfaced (deliberate clutter filter, documented in Task 11).
- **Type consistency:** wire keys `s,e,t,n,cp` are used identically in `buildSurfaceSectionPayload` (Task 5) and `SurfaceData.parse` (Task 9). `buildCheckpoints(double[],double[],double[],int,int)` returns `[dist,latInt,lonInt,...]`, matching the `cp` parser. `updateFlatSegment(String,int,int,String)`, `addSurfaceSection(String,int,int,int,String)`, and `setSurfaceSectionName(String,int,String)` have identical signatures across repository, ViewModel, and Activity.
- **Watch budget:** checkpoints are capped (`MAX_CHECKPOINTS_PER_SECTION = 12` phone-side, `MAX_CP = 256` watch-side); names capped at 24 chars; flat-segment clutter filter keeps the list small.
- **Open follow-up (out of scope):** phone-side edits reach the watch on the next route activation through the existing relay; no new immediate-push trigger is added. If instant push is wanted later, hook `buildSurfaceSectionPayload` into the same path that pushes on `SET_ACTIVE_ROUTE`.
- **Verify-before-implement reminders:** Task 2 Step 5 asks you to confirm the real `saveRoute` / `FlatSegment` signatures before writing that test; Task 9 Step 3 asks you to confirm `Toybox.Math` is imported.
