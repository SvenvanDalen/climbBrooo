# Custom Surface Sections (Vrije Ondergrond-stukken) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user manually mark an arbitrary stretch of a route (free-chosen start/end kilometre) with a surface type, independent of detected climb segments and flat segments.

**Architecture:** A new phone-side data structure `StoredSurfaceSection` (start distance, end distance, surface type, all in metres) is stored as a list on `StoredRoute`. `RouteRepository` gains add/delete methods with validation, preserves these sections across route re-imports (like notes/flat-segment surfaces), and folds their surface types into the catalog index. `RouteDetailActivity` gets a "Ondergrond-stukken" button opening a manager dialog (list + add + delete). The sections are **phone-only for now** — they are deliberately *not* added to the watch wire payload yet, but the distance-range shape is chosen so a future `surfSec` wire array can carry them unchanged.

**Tech Stack:** Java (Android, MVVM + Repository), Jackson JSON persistence under `getFilesDir()/routes/`, JUnit 4 + Robolectric for tests, Gradle Groovy DSL.

---

## Background: what already exists (read before starting)

The app **already** lets the user assign one of a fixed list of surface types to:
- each **climb segment** (`StoredSegment.surfaceType`) — long-press in `ClimbDetailActivity`, plus a bulk setter.
- each detected **flat segment** (`StoredFlatSegment.surfaceType`) — long-press in `RouteDetailActivity`.

The fixed surface types live in `android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceType.java`:
`ASPHALT=0, GRAVEL=1, DIRT=2, COBBLESTONE=3, MIXED=4, UNKNOWN=5`, with Dutch UI labels `{"Asfalt","Gravel","Onverhard","Kasseien","Mixed","Onbekend"}` repeated inline in each dialog.

**This feature is different:** it is *not* tied to a detected segment. The user picks any start/end distance on the route and assigns a surface. The fixed surface-type list is reused unchanged — we are **not** adding new surface-type categories.

### Key files you will touch
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`
- Modify: `Documentation/ARCHITECTURE.md`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java`

### Conventions to follow
- **Java, POJOs with public fields** matching `StoredFlatSegment` / `StoredSegment` style (no getters/setters, `@JsonIgnoreProperties(ignoreUnknown = true)`).
- All distances are **integer metres** internally; the UI shows kilometres with one decimal.
- Repository writes are atomic and update the catalog's `surfaceTypes` index via `rebuildCatalogSurfaceTypes`.
- Tests run with Robolectric. **Critical gotcha:** `RouteRepository`'s constructor runs `migrateIfNeeded()`, which **deletes all route files** unless the `segment_version` SharedPreference matches `ClimbConstants.SEGMENT_VERSION`. Every repository test MUST pre-seed that pref before constructing the repository (see Task 2 Step 1).

### Commands (this project)
- All commands run from the `android/` directory.
- Run one test class: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
- Run a single test method: `cd android && ./gradlew test --tests "nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest.addSurfaceSection_persists"`
- Full unit test suite: `cd android && ./gradlew test`
- On Windows PowerShell use `.\gradlew.bat` instead of `./gradlew`.

---

## Task 1: Data model — `StoredSurfaceSection` + field on `StoredRoute`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`

This task is pure data declarations; it is exercised by the repository tests in Tasks 2–4. There is no standalone test here because empty POJOs have no behaviour — the field must exist first so the later test code compiles.

- [ ] **Step 1: Create the `StoredSurfaceSection` POJO**

Create `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java`:

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * A user-defined surface override for an arbitrary stretch of a route.
 * Unlike {@link StoredFlatSegment} and {@link StoredSegment}, this is not tied to a
 * detected segment — the user picks the start/end distance freely.
 *
 * Distances are integer metres from the route start. Phone-only for now; the
 * distance-range shape is chosen so a future watch wire array can carry it unchanged.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSurfaceSection {
    public int startDistance;
    public int endDistance;
    public int surfaceType = SurfaceType.UNKNOWN;
}
```

- [ ] **Step 2: Add the `surfaceSections` field to `StoredRoute`**

In `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`, add a field directly after the `flatSegments` field (after line 24):

```java
    /** Flat (non-climb) stretches between climbs, in route order. */
    public List<StoredFlatSegment> flatSegments;

    /** User-defined surface overrides for arbitrary route stretches, sorted by startDistance. */
    public List<StoredSurfaceSection> surfaceSections;
```

(`java.util.List` is already imported in this file.)

- [ ] **Step 3: Verify the module still compiles**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredSurfaceSection.java android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java
git commit -m "feat(android): StoredSurfaceSection model + StoredRoute.surfaceSections field"
```

---

## Task 2: `RouteRepository.addSurfaceSection` with validation

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java`

We add a method that validates the requested stretch against the route length, clamps the surface type to a legal value, inserts the section keeping the list sorted by `startDistance`, persists atomically, and refreshes the catalog surface-type index.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java`:

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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SurfaceSectionRepositoryTest {

    private Application app;

    /** Writes a route with a 0..5000 m distance axis so routeLength == 5000. */
    private void seedRoute(String routeId) throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = routeId;
        route.name       = "Test";
        route.lats       = new double[]{51.0, 51.01, 51.02};
        route.lons       = new double[]{5.0, 5.01, 5.02};
        route.elevations = new double[]{100, 120, 140};
        route.distances  = new double[]{0, 2500, 5000};

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, routeId + ".json"), route);
    }

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        // Skip migrateIfNeeded()'s wipe of route files.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
    }

    @Test
    public void addSurfaceSection_persists() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals(1, reloaded.surfaceSections.size());
        StoredSurfaceSection s = reloaded.surfaceSections.get(0);
        assertEquals(1000, s.startDistance);
        assertEquals(2000, s.endDistance);
        assertEquals(SurfaceType.GRAVEL, s.surfaceType);
    }

    @Test
    public void addSurfaceSection_keepsListSortedByStart() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 3000, 4000, SurfaceType.DIRT);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);

        List<StoredSurfaceSection> sections = repo.loadRoute("r1").surfaceSections;
        assertEquals(2, sections.size());
        assertEquals(500, sections.get(0).startDistance);
        assertEquals(3000, sections.get(1).startDistance);
    }

    @Test
    public void addSurfaceSection_rejectsStartNotBeforeEnd() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 2000, 2000, SurfaceType.GRAVEL));
    }

    @Test
    public void addSurfaceSection_rejectsEndBeyondRouteLength() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        assertThrows(IllegalArgumentException.class,
                () -> repo.addSurfaceSection("r1", 1000, 6000, SurfaceType.GRAVEL));
    }

    @Test
    public void addSurfaceSection_clampsIllegalSurfaceToUnknown() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, 99); // out of 0..5 range

        assertEquals(SurfaceType.UNKNOWN,
                repo.loadRoute("r1").surfaceSections.get(0).surfaceType);
    }

    @Test
    public void addSurfaceSection_updatesCatalogSurfaceTypes() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);

        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        boolean gravelInCatalog = false;
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            if (e.routeId.equals("r1") && e.surfaceTypes != null) {
                for (int t : e.surfaceTypes) if (t == SurfaceType.GRAVEL) gravelInCatalog = true;
            }
        }
        assertTrue("GRAVEL must appear in the route's catalog surfaceTypes", gravelInCatalog);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: FAIL — compilation error `cannot find symbol: method addSurfaceSection`.

- [ ] **Step 3: Add a route-length helper to `RouteRepository`**

In `RouteRepository.java`, add this private static helper next to the other helpers (e.g. directly above `nearestCoord`, around line 285):

```java
    /** Total route length in metres = the largest cumulative distance, or 0 if unknown. */
    private static int routeLengthMeters(StoredRoute route) {
        if (route.distances == null || route.distances.length == 0) return 0;
        return (int) Math.round(route.distances[route.distances.length - 1]);
    }
```

- [ ] **Step 4: Add `addSurfaceSection` to `RouteRepository`**

In `RouteRepository.java`, add this method directly after `setFlatSegmentSurfaceType` (after line 432):

```java
    /**
     * Adds a user-defined surface override for an arbitrary route stretch.
     * Distances are integer metres. The surface type is clamped to a legal value.
     * The list is kept sorted by startDistance. Overlaps with existing sections are
     * allowed (phone-only display); the most-recently-added section wins visually.
     *
     * @throws IllegalArgumentException if start &lt; 0, end &lt;= start, or end &gt; route length.
     */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) throws IOException {
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

(`java.util.ArrayList` and `SurfaceType` are already imported in this file.)

- [ ] **Step 5: Include surface sections in the catalog index**

In `RouteRepository.computeSurfaceTypes` (around line 478), add a loop over `surfaceSections` directly after the `flatSegments` loop, before the `return`:

```java
        if (route.flatSegments != null) {
            for (StoredFlatSegment sf : route.flatSegments) {
                if (sf.surfaceType != SurfaceType.UNKNOWN) {
                    surfaceSet.add(sf.surfaceType);
                }
            }
        }
        if (route.surfaceSections != null) {
            for (StoredSurfaceSection ss : route.surfaceSections) {
                if (ss.surfaceType != SurfaceType.UNKNOWN) {
                    surfaceSet.add(ss.surfaceType);
                }
            }
        }
        return surfaceSet.isEmpty() ? null
                : surfaceSet.stream().mapToInt(Integer::intValue).toArray();
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java
git commit -m "feat(android): RouteRepository.addSurfaceSection with validation + catalog index"
```

---

## Task 3: `RouteRepository.deleteSurfaceSection`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java`

- [ ] **Step 1: Add the failing tests**

Append these two test methods inside `SurfaceSectionRepositoryTest` (before the closing brace):

```java
    @Test
    public void deleteSurfaceSection_removesByIndex() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);
        repo.addSurfaceSection("r1", 3000, 4000, SurfaceType.DIRT);

        repo.deleteSurfaceSection("r1", 0); // removes the 500..1000 ASPHALT section

        List<StoredSurfaceSection> sections = repo.loadRoute("r1").surfaceSections;
        assertEquals(1, sections.size());
        assertEquals(3000, sections.get(0).startDistance);
    }

    @Test
    public void deleteSurfaceSection_ignoresOutOfRangeIndex() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 500, 1000, SurfaceType.ASPHALT);

        repo.deleteSurfaceSection("r1", 7); // no-op, must not throw

        assertEquals(1, repo.loadRoute("r1").surfaceSections.size());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: FAIL — `cannot find symbol: method deleteSurfaceSection`.

- [ ] **Step 3: Implement `deleteSurfaceSection`**

In `RouteRepository.java`, add directly after `addSurfaceSection`:

```java
    /**
     * Removes the surface section at the given index (after sorting by startDistance).
     * Out-of-range indices are ignored.
     */
    public void deleteSurfaceSection(String routeId, int index) throws IOException {
        StoredRoute route = loadRoute(routeId);
        if (route.surfaceSections == null
                || index < 0 || index >= route.surfaceSections.size()) {
            Log.w(TAG, "deleteSurfaceSection: index out of range: " + index);
            return;
        }
        route.surfaceSections.remove(index);
        route.lastModifiedMs = System.currentTimeMillis();
        writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
        rebuildCatalogSurfaceTypes(routeId, route);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java
git commit -m "feat(android): RouteRepository.deleteSurfaceSection"
```

---

## Task 4: Preserve surface sections across route re-import

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java`

`saveRoute` rebuilds `lats/lons/climbs/flatSegments` from scratch on every (re-)import. Custom surface sections are pure user data that is never re-derived, so — exactly like flat-segment surfaces are carried over via `loadPreviousFlatSegments` — we must copy the previous `surfaceSections` into the freshly-saved route. Without this, a Strava resync silently wipes the user's sections.

- [ ] **Step 1: Add the failing test**

Append this test method inside `SurfaceSectionRepositoryTest`:

```java
    @Test
    public void saveRoute_preservesSurfaceSectionsAcrossReimport() throws Exception {
        seedRoute("r1");
        RouteRepository repo = new RouteRepository(app);
        repo.addSurfaceSection("r1", 1000, 2000, SurfaceType.GRAVEL);

        // Simulate a re-import: a brand-new StoredRoute with the same id, no surfaceSections set.
        StoredRoute fresh = new StoredRoute();
        fresh.routeId = "r1";
        fresh.name    = "Test";
        java.util.List<nl.paree.climbpro.domain.route.RoutePoint> points = new java.util.ArrayList<>();
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.0, 5.0, 100, 0));
        points.add(new nl.paree.climbpro.domain.route.RoutePoint(51.02, 5.02, 140, 5000));

        repo.saveRoute(fresh, points, java.util.Collections.emptyList());

        StoredRoute reloaded = repo.loadRoute("r1");
        assertEquals("surface sections must survive re-import",
                1, reloaded.surfaceSections.size());
        assertEquals(SurfaceType.GRAVEL, reloaded.surfaceSections.get(0).surfaceType);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android && ./gradlew test --tests "nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest.saveRoute_preservesSurfaceSectionsAcrossReimport"`
Expected: FAIL — `reloaded.surfaceSections` is `null` → `NullPointerException`, or assertion fails (0 sections).

- [ ] **Step 3: Add the `loadPreviousSurfaceSections` helper**

In `RouteRepository.java`, add directly after the existing `loadPreviousFlatSegments` method (after line 203):

```java
    private List<StoredSurfaceSection> loadPreviousSurfaceSections(String routeId) {
        File f = routeFile(routeId);
        if (!f.exists()) return Collections.emptyList();
        try (FileInputStream in = new FileInputStream(f)) {
            StoredRoute existing = mapper.readValue(in, StoredRoute.class);
            return existing.surfaceSections != null
                    ? existing.surfaceSections : Collections.emptyList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
```

- [ ] **Step 4: Carry the sections over inside `saveRoute`**

In `RouteRepository.saveRoute`, directly after the `route.flatSegments = toStoredFlatSegments(...)` assignment (after line 98) and before `route.lastModifiedMs = ...`, add:

```java
        route.flatSegments = toStoredFlatSegments(flatDomain, pts,
                loadPreviousFlatSegments(route.routeId));
        route.surfaceSections = new ArrayList<>(loadPreviousSurfaceSections(route.routeId));
        route.lastModifiedMs = System.currentTimeMillis();
```

- [ ] **Step 5: Run the full test class to verify pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.SurfaceSectionRepositoryTest`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/SurfaceSectionRepositoryTest.java
git commit -m "feat(android): preserve custom surface sections across route re-import"
```

---

## Task 5: `RouteDetailViewModel` — add/delete + expose sections

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`

The ViewModel runs repository calls on its background executor and reloads the route afterwards so observers refresh. Sections are **phone-only**, so — unlike `setFlatSegmentSurface` — we do **not** call `SyncScheduler.triggerImmediateSync` here; that would push a watch payload that does not yet carry sections. We surface validation failures through the existing `error` LiveData.

There is no separate unit test for this thin wrapper; the repository logic it delegates to is already covered in Tasks 2–4, and `RouteDetailViewModel` has no existing test harness. It is verified manually in Task 6.

- [ ] **Step 1: Import the new model type**

In `RouteDetailViewModel.java`, add an import next to the other `data.route` imports (after line 13):

```java
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
```

- [ ] **Step 2: Add a LiveData for the sections and expose it**

In `RouteDetailViewModel.java`, add a field next to the other `MutableLiveData` fields (after line 32):

```java
    private final MutableLiveData<Boolean>      saved      = new MutableLiveData<>(false);
    private final MutableLiveData<List<StoredSurfaceSection>> surfaceSections = new MutableLiveData<>();
```

And add an accessor next to the other accessors (after line 42):

```java
    public LiveData<Boolean>      saved()      { return saved; }
    public LiveData<List<StoredSurfaceSection>> surfaceSections() { return surfaceSections; }
```

- [ ] **Step 3: Publish sections when the route loads**

In `RouteDetailViewModel.loadRoute`, inside the `try` block after `routeItems.postValue(...)` (after line 49), add:

```java
                StoredRoute r = routeRepo.loadRoute(routeId);
                route.postValue(r);
                routeItems.postValue(buildRouteItems(r));
                surfaceSections.postValue(
                        r.surfaceSections != null ? r.surfaceSections : Collections.emptyList());
```

(`java.util.Collections` is already imported.)

- [ ] **Step 4: Add the `addSurfaceSection` and `deleteSurfaceSection` methods**

In `RouteDetailViewModel.java`, add these methods after `setFlatSegmentSurface` (after line 98):

```java
    /** Adds a user-defined surface override for an arbitrary stretch (phone-only). */
    public void addSurfaceSection(String routeId, int startDistance, int endDistance,
                                  int surfaceType) {
        executor.execute(() -> {
            try {
                routeRepo.addSurfaceSection(routeId, startDistance, endDistance, surfaceType);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (IllegalArgumentException e) {
                error.postValue("Ongeldig stuk: " + e.getMessage());
            } catch (Exception e) {
                error.postValue("Opslaan mislukt: " + e.getMessage());
            }
        });
    }

    /** Removes the surface section at the given index (phone-only). */
    public void deleteSurfaceSection(String routeId, int index) {
        executor.execute(() -> {
            try {
                routeRepo.deleteSurfaceSection(routeId, index);
                loadRoute(routeId);
                saved.postValue(true);
            } catch (Exception e) {
                error.postValue("Verwijderen mislukt: " + e.getMessage());
            }
        });
    }
```

- [ ] **Step 5: Verify compilation**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java
git commit -m "feat(android): RouteDetailViewModel add/delete + expose custom surface sections"
```

---

## Task 6: UI — manager button + add/list/delete dialogs

**Files:**
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`

We add one button under the existing route buttons. Tapping it opens a manager dialog listing the current sections; tapping a row offers delete; a "Toevoegen" button opens an add dialog with two kilometre inputs and a surface picker. This mirrors the existing dialog style in `RouteDetailActivity.showFlatSurfaceDialog`.

- [ ] **Step 1: Add the button to the layout**

In `android/app/src/main/res/layout/activity_route_detail.xml`, add a `Button` directly after `btn_share_to_garmin` (after line 90, before the "Klimmen & vlakke stukken" TextView at line 92):

```xml
            <Button
                android:id="@+id/btn_share_to_garmin"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="Open in Garmin Connect" />

            <Button
                android:id="@+id/btn_surface_sections"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="Ondergrond-stukken" />
```

- [ ] **Step 2: Add the surface-label constant and wire the button**

In `RouteDetailActivity.java`, add a constant inside the class, directly after the `private static final String EXTRA_ROUTE_ID = "route_id";` line (after line 32):

```java
    private static final String EXTRA_ROUTE_ID = "route_id";

    /** Dutch surface labels, index = SurfaceType constant (0..5). */
    private static final String[] SURFACE_LABELS_NL =
            {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};
```

In `onCreate`, register the click listener directly after the `btn_share_to_garmin` listener (after line 92):

```java
        binding.btnShareToGarmin.setOnClickListener(v -> shareToGarminConnect());
        binding.btnSurfaceSections.setOnClickListener(v -> showSurfaceSectionsManager());
```

- [ ] **Step 3: Add the manager dialog**

In `RouteDetailActivity.java`, add this method after `showFlatSurfaceDialog` (after line 174):

```java
    private void showSurfaceSectionsManager() {
        java.util.List<nl.paree.climbpro.data.route.StoredSurfaceSection> sections =
                viewModel.surfaceSections().getValue();
        if (sections == null) sections = java.util.Collections.emptyList();

        final java.util.List<nl.paree.climbpro.data.route.StoredSurfaceSection> current = sections;
        String[] rows;
        if (current.isEmpty()) {
            rows = new String[]{"(nog geen stukken)"};
        } else {
            rows = new String[current.size()];
            for (int i = 0; i < current.size(); i++) {
                nl.paree.climbpro.data.route.StoredSurfaceSection s = current.get(i);
                rows[i] = String.format("%.1f–%.1f km · %s",
                        s.startDistance / 1000.0, s.endDistance / 1000.0,
                        SURFACE_LABELS_NL[SurfaceType.fromInt(s.surfaceType)]);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ondergrond-stukken")
                .setItems(rows, (dialog, which) -> {
                    if (!current.isEmpty()) confirmDeleteSection(which);
                })
                .setPositiveButton("Toevoegen", (d, w) -> showAddSurfaceSectionDialog())
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void confirmDeleteSection(int index) {
        new AlertDialog.Builder(this)
                .setTitle("Stuk verwijderen?")
                .setPositiveButton("Verwijder", (d, w) ->
                        viewModel.deleteSurfaceSection(routeId, index))
                .setNegativeButton("Annuleer", null)
                .show();
    }
```

(`SurfaceType` is already imported at line 24.)

- [ ] **Step 4: Add the "add section" dialog**

In `RouteDetailActivity.java`, add this method directly after `confirmDeleteSection`:

```java
    private void showAddSurfaceSectionDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final android.widget.EditText startKm = new android.widget.EditText(this);
        startKm.setHint("Start (km)");
        startKm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(startKm);

        final android.widget.EditText endKm = new android.widget.EditText(this);
        endKm.setHint("Eind (km)");
        endKm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(endKm);

        // Surface picker excludes "Onbekend" (index 0..4 only).
        final android.widget.Spinner surface = new android.widget.Spinner(this);
        String[] choices = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed"};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        surface.setAdapter(adapter);
        layout.addView(surface);

        new AlertDialog.Builder(this)
                .setTitle("Nieuw ondergrond-stuk")
                .setView(layout)
                .setPositiveButton("Toevoegen", (dialog, which) -> {
                    Integer startM = parseKmToMeters(startKm.getText().toString());
                    Integer endM   = parseKmToMeters(endKm.getText().toString());
                    if (startM == null || endM == null) {
                        Toast.makeText(this, "Vul start en eind in km in", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    viewModel.addSurfaceSection(routeId, startM, endM,
                            surface.getSelectedItemPosition());
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    /** Parses a kilometre string (e.g. "1.5") to integer metres, or null if blank/invalid. */
    private static Integer parseKmToMeters(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(text.trim()) * 1000.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 5: Verify compilation**

Run: `cd android && ./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`. (`binding.btnSurfaceSections` is generated from the new `@+id/btn_surface_sections`; a failure here usually means the layout id was mistyped.)

- [ ] **Step 6: Manual verification (Robolectric build + device/emulator)**

Run the assemble to ensure resources link:
Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Then, on an emulator or device, open a route → tap **Ondergrond-stukken** → **Toevoegen** → enter start `1.0`, end `2.0`, pick **Gravel** → confirm a Toast "Saved" appears and reopening the manager lists `1.0–2.0 km · Gravel`. Tap that row → **Verwijder** → confirm it disappears. (If you cannot run an emulator, note this step as deferred and rely on the assemble passing.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/activity_route_detail.xml android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java
git commit -m "feat(android): RouteDetailActivity UI for managing custom surface sections"
```

---

## Task 7: Document the phone-only scope and planned wire extension

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`

Per CLAUDE.md, a change that adds a new persisted data structure and anticipates a future wire-format change must be noted in `Documentation/ARCHITECTURE.md`. We record that custom surface sections are phone-only today and describe the intended `surfSec` wire array so a future implementer does not reinvent the shape.

- [ ] **Step 1: Find the right section to extend**

Run: `cd .. && grep -n "surface" Documentation/ARCHITECTURE.md` (or open the file and locate where surface types / the wire payload are described).
Expected: locate the payload/surface discussion so the new note sits beside related content. If no surface section exists, append the note under the wire-format / payload section.

- [ ] **Step 2: Add the note**

Add the following paragraph to `Documentation/ARCHITECTURE.md` in (or immediately after) the section that describes surface types and the wire payload:

```markdown
### Custom surface sections (phone-only)

Beyond auto-detected per-climb-segment and per-flat-segment surface types, the user can
manually mark an **arbitrary stretch** of a route with a surface type. These live in
`StoredRoute.surfaceSections` (`StoredSurfaceSection`: `startDistance`, `endDistance`,
`surfaceType`, all integer metres) and are managed from the route detail screen.

They are **phone-only for now** — deliberately not serialised into the Connect IQ payload.
The distance-range shape is chosen so a future wire extension can carry them unchanged:
a packed `surfSec` array of `[startDistance, endDistance, surfaceType, …]` integers on the
route payload, added via `protocol/schema.json` first (then regenerated Java POJOs and a
hand-written Monkey C match), with overlap-resolution decided watch-side at that time.
Custom sections survive route re-import (preserved in `RouteRepository.saveRoute` like
flat-segment surfaces) and contribute to the catalog `surfaceTypes` index.
```

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md
git commit -m "docs: describe phone-only custom surface sections + planned wire extension"
```

---

## Final verification

- [ ] **Step 1: Run the full unit-test suite**

Run: `cd android && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, all tests pass (including the 9 in `SurfaceSectionRepositoryTest`).

- [ ] **Step 2: Confirm a debug build assembles**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

---

## Self-review notes (for the implementer)

- **Spec coverage:** "vrije stukken markeren" → Tasks 1–6 (free start/end metres, any surface). "voorlopig alleen telefoon" → no payload/schema change; Task 5 omits `triggerImmediateSync`; Task 7 documents it. "moeten ooit nog naar de horloge" → Task 7 records the exact planned `surfSec` wire shape.
- **No new surface categories** were added — the fixed `SurfaceType` list is reused, matching the user's intent (mark sections, not invent types).
- **Type consistency:** `addSurfaceSection(String, int, int, int)`, `deleteSurfaceSection(String, int)`, and `StoredSurfaceSection{startDistance, endDistance, surfaceType}` are used identically in repository, tests, ViewModel, and Activity.
- **Watch budget:** nothing is sent to the watch in this change, so the memory-constrained payload is unaffected.
