# Surface Type per Segment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-segment surface type (ASPHALT/GRAVEL/DIRT/COBBLESTONE/MIXED/UNKNOWN) with auto-detection from GPX/Strava, per-segment manual override in ClimbDetailActivity, route-list filtering, and a color-coded surface bar on the Garmin watch.

**Architecture:** `SurfaceType` enum lives in the domain layer (`domain/segment/`). `StoredSegment` gains a `surfaceType` field (int, default 5=UNKNOWN). `RouteCatalogEntry` gains a `surfaceTypes` int-array for cheap filtering. `SurfaceTypeDetector` is a pure-function utility that infers surface type from raw GPX bytes or a Strava `sub_type` integer. `ClimbPayloadBuilder` bumps to v3 and emits an optional parallel `surf` int-array per climb. The Garmin watch adds a 5px color bar below the gradient profile.

**Tech Stack:** Java 17, Android, JUnit 5, Jackson ObjectMapper, Monkey C (Garmin Connect IQ 4.2.2, Forerunner 255 Music)

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceType.java` | Enum of 6 surface types with index constants and a `fromInt()` factory |
| Create | `android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceTypeDetector.java` | Pure-function detection from GPX bytes or Strava `sub_type` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSegment.java` | Add `public int surfaceType = 5` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/RouteCatalogEntry.java` | Add `public int[] surfaceTypes` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java` | Add `setSegmentSurfaceType`, `setBulkClimbSurfaceType`, update `toCatalogEntry` + `toStoredSegments` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDto.java` | Add `public int subType` (`@JsonProperty("sub_type")`) |
| Modify | `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java` | Bump `SCHEMA_VERSION` to 3, add `buildSurf()`, emit optional `surf` array |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java` | Add `setSurfaceType(routeId, climbIdx, segIdx, type)` and `setBulkSurfaceType(routeId, climbIdx, type)` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbSegmentAdapter.java` | Show surface badge, expose long-press listener |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` | Wire long-press BottomSheet dialog and bulk setter |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListViewModel.java` | Add `surfaceFilter` LiveData and filtered routes |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java` | Add filter chip row |
| Modify | `android/app/src/main/res/layout/item_segment.xml` | Add `TextView` for surface badge |
| Modify | `android/app/src/main/res/layout/activity_climb_detail.xml` | Add bulk-setter spinner + button above segment list |
| Modify | `android/app/src/main/res/layout/activity_route_list.xml` | Add `HorizontalScrollView` + `ChipGroup` below toolbar |
| Modify | `garmin/source/ClimbData.mc` | Add `segSurf` parallel arrays + initialization |
| Modify | `garmin/source/CommListener.mc` | Version check `!= 2` → `!= 3`, decode `surf` array in `parseClimb` |
| Modify | `garmin/source/ClimbProView.mc` | Add `drawSurfaceBar()` + `surfaceColor()`, call from `drawActiveClimb` |
| Create | `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java` | Tests for enum, `fromInt()`, defaults |
| Create | `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeDetectorTest.java` | Tests for GPX and Strava detection |
| Modify | `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java` | Update version assertion to 3, add `surf` array tests |

---

## Task 1: SurfaceType enum

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceType.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.segment.SurfaceType;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceTypeTest {

    @Test
    public void constantsHaveCorrectIndices() {
        assertEquals(0, SurfaceType.ASPHALT);
        assertEquals(1, SurfaceType.GRAVEL);
        assertEquals(2, SurfaceType.DIRT);
        assertEquals(3, SurfaceType.COBBLESTONE);
        assertEquals(4, SurfaceType.MIXED);
        assertEquals(5, SurfaceType.UNKNOWN);
    }

    @Test
    public void fromIntReturnsKnownValues() {
        assertEquals(SurfaceType.ASPHALT,     SurfaceType.fromInt(0));
        assertEquals(SurfaceType.GRAVEL,      SurfaceType.fromInt(1));
        assertEquals(SurfaceType.DIRT,        SurfaceType.fromInt(2));
        assertEquals(SurfaceType.COBBLESTONE, SurfaceType.fromInt(3));
        assertEquals(SurfaceType.MIXED,       SurfaceType.fromInt(4));
        assertEquals(SurfaceType.UNKNOWN,     SurfaceType.fromInt(5));
    }

    @Test
    public void fromIntClampsOutOfRangeToUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceType.fromInt(-1));
        assertEquals(SurfaceType.UNKNOWN, SurfaceType.fromInt(99));
    }

    @Test
    public void labelReturnsExpectedStrings() {
        assertEquals("A", SurfaceType.label(SurfaceType.ASPHALT));
        assertEquals("G", SurfaceType.label(SurfaceType.GRAVEL));
        assertEquals("D", SurfaceType.label(SurfaceType.DIRT));
        assertEquals("K", SurfaceType.label(SurfaceType.COBBLESTONE));
        assertEquals("M", SurfaceType.label(SurfaceType.MIXED));
        assertNull(SurfaceType.label(SurfaceType.UNKNOWN));
    }
}
```

- [ ] **Step 2: Verify test fails to compile**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeTest
```
Expected: COMPILE FAIL — `SurfaceType` class not found.

- [ ] **Step 3: Create SurfaceType.java**

```java
package nl.paree.climbpro.domain.segment;

public final class SurfaceType {

    private SurfaceType() {}

    public static final int ASPHALT     = 0;
    public static final int GRAVEL      = 1;
    public static final int DIRT        = 2;
    public static final int COBBLESTONE = 3;
    public static final int MIXED       = 4;
    public static final int UNKNOWN     = 5;

    private static final String[] LABELS = {"A", "G", "D", "K", "M", null};

    /** Returns UNKNOWN for any value outside 0–5. */
    public static int fromInt(int v) {
        return (v >= 0 && v <= 5) ? v : UNKNOWN;
    }

    /** Returns the single-character watch label, or null for UNKNOWN. */
    public static String label(int v) {
        if (v < 0 || v >= LABELS.length) return null;
        return LABELS[v];
    }
}
```

- [ ] **Step 4: Verify tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeTest
```
Expected: PASS (4 tests green).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceType.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java
git commit -m "feat: add SurfaceType constants with fromInt() and label()"
```

---

## Task 2: Add surfaceType to StoredSegment and RouteCatalogEntry

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredSegment.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteCatalogEntry.java`
- Extend: `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java`

- [ ] **Step 1: Write failing tests**

Add to `SurfaceTypeTest.java`:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredSegment;

@Test
public void storedSegmentDefaultsToUnknown() {
    StoredSegment s = new StoredSegment();
    assertEquals("default surfaceType must be UNKNOWN=5", SurfaceType.UNKNOWN, s.surfaceType);
}

@Test
public void storedSegmentRoundTripsWithSurfaceType() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    StoredSegment s = new StoredSegment();
    s.distance = 100;
    s.gradient = 0.05;
    s.colorIndex = 2;
    s.surfaceType = SurfaceType.GRAVEL;
    String json = mapper.writeValueAsString(s);
    StoredSegment back = mapper.readValue(json, StoredSegment.class);
    assertEquals(SurfaceType.GRAVEL, back.surfaceType);
}

@Test
public void storedSegmentOldJsonWithoutSurfaceTypeDefaultsToUnknown() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String oldJson = "{\"distance\":125,\"elevationGain\":5,\"gradient\":0.04,\"colorIndex\":2}";
    StoredSegment s = mapper.readValue(oldJson, StoredSegment.class);
    assertEquals("old JSON without surfaceType must default to UNKNOWN", SurfaceType.UNKNOWN, s.surfaceType);
}

@Test
public void routeCatalogEntryHasSurfaceTypesField() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    RouteCatalogEntry e = new RouteCatalogEntry();
    e.routeId = "r1";
    e.surfaceTypes = new int[]{SurfaceType.ASPHALT, SurfaceType.GRAVEL};
    String json = mapper.writeValueAsString(e);
    RouteCatalogEntry back = mapper.readValue(json, RouteCatalogEntry.class);
    assertArrayEquals(new int[]{0, 1}, back.surfaceTypes);
}
```

- [ ] **Step 2: Verify tests fail**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeTest
```
Expected: COMPILE FAIL — `s.surfaceType` and `e.surfaceTypes` not found.

- [ ] **Step 3: Update StoredSegment.java**

Replace the entire file:

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredSegment {

    public int distance;
    public int elevationGain;
    public double gradient;
    public int colorIndex;
    public int surfaceType = SurfaceType.UNKNOWN;
}
```

- [ ] **Step 4: Update RouteCatalogEntry.java**

Add the following field after `public long lastModifiedMs;`:

```java
/** Deduplicated set of non-UNKNOWN SurfaceType indices across all segments of all climbs. */
public int[] surfaceTypes;
```

- [ ] **Step 5: Verify tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeTest
```
Expected: PASS (all 8 tests green).

- [ ] **Step 6: Verify no regressions**

```
./gradlew test
```
Expected: all existing tests still pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredSegment.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/RouteCatalogEntry.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeTest.java
git commit -m "feat: add surfaceType to StoredSegment (default UNKNOWN) and surfaceTypes to RouteCatalogEntry"
```

---

## Task 3: SurfaceTypeDetector (pure function — GPX + Strava)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceTypeDetector.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeDetectorTest.java`

- [ ] **Step 1: Write failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeDetectorTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.domain.segment.SurfaceTypeDetector;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceTypeDetectorTest {

    // ---- GPX detection ----

    @Test
    public void detectsGarminRoadCycling() {
        byte[] gpx = "<trk><type>road_cycling</type></trk>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsGarminMountainBiking() {
        byte[] gpx = "<trk><type>mountain_biking</type></trk>".getBytes();
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootGravel() {
        byte[] gpx = "<komoot:meta sport=\"gravel\"/>".getBytes();
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootMtb() {
        byte[] gpx = "<komoot:meta sport=\"mtb\"/>".getBytes();
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootRacebike() {
        byte[] gpx = "<komoot:meta sport=\"racebike\"/>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsKomootTouringbicycle() {
        byte[] gpx = "<komoot:meta sport=\"touringbicycle\"/>".getBytes();
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void detectsRideWithGpsGravel() {
        byte[] gpx = "<type>gravel</type>".getBytes();
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void unknownGpxReturnsUnknown() {
        byte[] gpx = "<gpx><trk><name>My route</name></trk></gpx>".getBytes();
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromGpxBytes(gpx));
    }

    @Test
    public void nullGpxBytesReturnsUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromGpxBytes(null));
    }

    // ---- Strava sub_type detection ----

    @Test
    public void stravaSubType1Road() {
        assertEquals(SurfaceType.ASPHALT, SurfaceTypeDetector.detectFromStravaSubType(1));
    }

    @Test
    public void stravaSubType2MountainBike() {
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromStravaSubType(2));
    }

    @Test
    public void stravaSubType3Cross() {
        assertEquals(SurfaceType.GRAVEL, SurfaceTypeDetector.detectFromStravaSubType(3));
    }

    @Test
    public void stravaSubType4Trail() {
        assertEquals(SurfaceType.DIRT, SurfaceTypeDetector.detectFromStravaSubType(4));
    }

    @Test
    public void stravaSubType5Mixed() {
        assertEquals(SurfaceType.MIXED, SurfaceTypeDetector.detectFromStravaSubType(5));
    }

    @Test
    public void stravaSubType0AndUnknownReturnUnknown() {
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromStravaSubType(0));
        assertEquals(SurfaceType.UNKNOWN, SurfaceTypeDetector.detectFromStravaSubType(99));
    }
}
```

- [ ] **Step 2: Verify tests fail to compile**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeDetectorTest
```
Expected: COMPILE FAIL — `SurfaceTypeDetector` not found.

- [ ] **Step 3: Create SurfaceTypeDetector.java**

```java
package nl.paree.climbpro.domain.segment;

import java.nio.charset.StandardCharsets;

public final class SurfaceTypeDetector {

    private SurfaceTypeDetector() {}

    /**
     * Scans raw GPX bytes for known sport/surface markers.
     * Uses simple string search — no XML parse needed for these single-value tags.
     * Returns UNKNOWN if no recognised marker found.
     */
    public static int detectFromGpxBytes(byte[] gpxBytes) {
        if (gpxBytes == null || gpxBytes.length == 0) return SurfaceType.UNKNOWN;
        String xml = new String(gpxBytes, StandardCharsets.UTF_8).toLowerCase();

        // Komoot meta sport attribute — check before generic <type> to avoid false matches
        if (xml.contains("sport=\"gravel\""))          return SurfaceType.GRAVEL;
        if (xml.contains("sport=\"mtb\""))             return SurfaceType.DIRT;
        if (xml.contains("sport=\"racebike\""))        return SurfaceType.ASPHALT;
        if (xml.contains("sport=\"touringbicycle\""))  return SurfaceType.ASPHALT;

        // Garmin Connect GPX <type> inside <trk>
        if (xml.contains("<type>road_cycling</type>"))  return SurfaceType.ASPHALT;
        if (xml.contains("<type>mountain_biking</type>")) return SurfaceType.DIRT;
        if (xml.contains("<type>cycling</type>"))        return SurfaceType.ASPHALT;

        // Ride with GPS / generic
        if (xml.contains("<type>gravel</type>"))  return SurfaceType.GRAVEL;

        return SurfaceType.UNKNOWN;
    }

    /**
     * Maps a Strava route sub_type integer to a SurfaceType.
     * Strava sub_type: 1=road, 2=mountain bike, 3=cross, 4=trail, 5=mixed.
     */
    public static int detectFromStravaSubType(int subType) {
        switch (subType) {
            case 1: return SurfaceType.ASPHALT;
            case 2: return SurfaceType.DIRT;
            case 3: return SurfaceType.GRAVEL;
            case 4: return SurfaceType.DIRT;
            case 5: return SurfaceType.MIXED;
            default: return SurfaceType.UNKNOWN;
        }
    }
}
```

- [ ] **Step 4: Verify all tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SurfaceTypeDetectorTest
```
Expected: PASS (14 tests green).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/SurfaceTypeDetector.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SurfaceTypeDetectorTest.java
git commit -m "feat: SurfaceTypeDetector — detect from GPX bytes and Strava sub_type"
```

---

## Task 4: RouteRepository — surfaceTypes in catalog, set/bulk methods

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDto.java`

No pure-unit tests possible for RouteRepository (requires Android Context). Verify via integration tests / manual emulator run. The `toCatalogEntry` helper is private-static so changes are verified indirectly by the payload tests later.

- [ ] **Step 1: Add sub_type to StravaRouteDto.java**

Open `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDto.java`.

Add after the `updatedAt` field:

```java
@JsonProperty("sub_type")
public int subType;
```

- [ ] **Step 2: Update toCatalogEntry() to compute surfaceTypes**

In `RouteRepository.java`, find the `toCatalogEntry(StoredRoute, List<RoutePoint>, List<Climb>)` method. Add the `surfaceTypes` computation after setting `e.climbStartCoords`:

```java
// Compute deduplicated set of non-UNKNOWN surface types across all segments
java.util.TreeSet<Integer> surfaceSet = new java.util.TreeSet<>();
if (route.climbs != null) {
    for (nl.paree.climbpro.data.route.StoredClimb sc : route.climbs) {
        if (sc.segments != null) {
            for (nl.paree.climbpro.data.route.StoredSegment ss : sc.segments) {
                if (ss.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
                    surfaceSet.add(ss.surfaceType);
                }
            }
        }
    }
}
if (!surfaceSet.isEmpty()) {
    e.surfaceTypes = surfaceSet.stream().mapToInt(Integer::intValue).toArray();
}
```

- [ ] **Step 3: Add setSegmentSurfaceType to RouteRepository**

Add this public method after `reSegmentClimb`:

```java
/**
 * Sets the surface type of a single segment and updates the catalog's surfaceTypes index.
 * Only overwrites user-set values (not guarded by UNKNOWN check — user intent is explicit).
 */
public void setSegmentSurfaceType(String routeId, int climbIndex, int segmentIndex,
                                   int surfaceType) throws IOException {
    StoredRoute route = loadRoute(routeId);
    if (route.climbs == null || climbIndex >= route.climbs.size()) {
        throw new IOException("Climb index out of range: " + climbIndex);
    }
    StoredClimb sc = route.climbs.get(climbIndex);
    if (sc.segments == null || segmentIndex >= sc.segments.size()) {
        throw new IOException("Segment index out of range: " + segmentIndex);
    }
    sc.segments.get(segmentIndex).surfaceType = surfaceType;
    route.lastModifiedMs = System.currentTimeMillis();
    writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
    rebuildCatalogSurfaceTypes(routeId, route);
}
```

- [ ] **Step 4: Add setBulkClimbSurfaceType to RouteRepository**

Add after `setSegmentSurfaceType`:

```java
/**
 * Sets the surface type of every segment in one climb, then updates the catalog.
 */
public void setBulkClimbSurfaceType(String routeId, int climbIndex,
                                     int surfaceType) throws IOException {
    StoredRoute route = loadRoute(routeId);
    if (route.climbs == null || climbIndex >= route.climbs.size()) {
        throw new IOException("Climb index out of range: " + climbIndex);
    }
    StoredClimb sc = route.climbs.get(climbIndex);
    if (sc.segments != null) {
        for (StoredSegment seg : sc.segments) {
            seg.surfaceType = surfaceType;
        }
    }
    route.lastModifiedMs = System.currentTimeMillis();
    writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
    rebuildCatalogSurfaceTypes(routeId, route);
}
```

- [ ] **Step 5: Add rebuildCatalogSurfaceTypes private helper**

Add as a private method:

```java
private void rebuildCatalogSurfaceTypes(String routeId, StoredRoute route) throws IOException {
    java.util.TreeSet<Integer> surfaceSet = new java.util.TreeSet<>();
    if (route.climbs != null) {
        for (StoredClimb sc : route.climbs) {
            if (sc.segments != null) {
                for (StoredSegment ss : sc.segments) {
                    if (ss.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
                        surfaceSet.add(ss.surfaceType);
                    }
                }
            }
        }
    }
    int[] types = surfaceSet.isEmpty() ? null
            : surfaceSet.stream().mapToInt(Integer::intValue).toArray();

    List<RouteCatalogEntry> catalog = loadCatalog();
    for (RouteCatalogEntry e : catalog) {
        if (e.routeId.equals(routeId)) {
            e.surfaceTypes   = types;
            e.lastModifiedMs = route.lastModifiedMs;
            break;
        }
    }
    saveCatalog(catalog);
}
```

- [ ] **Step 6: Wire auto-detection in the GPX import (RouteListActivity)**

Open `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java`.

In the `importGpx()` method, after `List<Climb> climbs = ClimbDetector.detect(simple);`, add:

```java
int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
        .detectFromGpxBytes(bytes);
```

After `new RouteRepository(this).saveRoute(stored, simple, climbs);`, add:

```java
if (detectedSurface != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
    RouteRepository repo = new RouteRepository(this);
    // Apply detected type to all segments (segments start as UNKNOWN)
    StoredRoute saved = repo.loadRoute(routeId);
    if (saved.climbs != null) {
        for (int ci = 0; ci < saved.climbs.size(); ci++) {
            repo.setBulkClimbSurfaceType(routeId, ci, detectedSurface);
        }
    }
}
```

- [ ] **Step 7: Note — Strava sub_type wiring**

`StravaRouteDto.subType` is now populated from the API response. To wire detection in the Strava import flow, open `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java`. Find where routes are saved via `RouteRepository.saveRoute()`. Directly after saving, add:

```java
int detectedSurface = nl.paree.climbpro.domain.segment.SurfaceTypeDetector
        .detectFromStravaSubType(routeDto.subType);
if (detectedSurface != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
    StoredRoute saved = routeRepo.loadRoute(routeId);
    if (saved.climbs != null) {
        for (int ci = 0; ci < saved.climbs.size(); ci++) {
            routeRepo.setBulkClimbSurfaceType(routeId, ci, detectedSurface);
        }
    }
}
```

(Exact variable names depend on existing code in `StravaRoutesRepository`.)

- [ ] **Step 8: Verify all existing tests still pass**

```
./gradlew test
```
Expected: PASS — no regressions.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java \
        android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDto.java \
        android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java
git commit -m "feat: RouteRepository setSegmentSurfaceType, setBulkClimbSurfaceType, catalog surfaceTypes index"
```

---

## Task 5: ClimbPayloadBuilder v3 — surf array

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java`

- [ ] **Step 1: Write failing tests**

Open `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java`.

Change the version assertion in `payloadVersionIs2()` to:

```java
@Test
public void payloadVersionIs3() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
    JsonNode root = mapper.readTree(b.buildRoutePayload(buildRoute()));
    assertEquals(3, root.get("v").asInt());
}
```

Add these new tests at the end of the class:

```java
@Test
public void surfArrayOmittedWhenAllUnknown() throws Exception {
    // buildRoute() creates segments with surfaceType = 5 (UNKNOWN default)
    ObjectMapper mapper = new ObjectMapper();
    ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
    JsonNode climb = mapper.readTree(b.buildRoutePayload(buildRoute()))
            .get("climbs").get(0);
    assertFalse("surf must be absent when all segments are UNKNOWN", climb.has("surf"));
}

@Test
public void surfArrayPresentWhenAtLeastOneNonUnknown() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
    StoredRoute route = buildRoute();
    route.climbs.get(0).segments.get(0).surfaceType = 1; // GRAVEL
    JsonNode surf = mapper.readTree(b.buildRoutePayload(route))
            .get("climbs").get(0).get("surf");
    assertNotNull("surf must exist when at least one segment is non-UNKNOWN", surf);
    assertTrue("surf is array", surf.isArray());
    assertEquals("surf has 16 elements (one per segment)", 16, surf.size());
    assertEquals("first segment = GRAVEL (1)", 1, surf.get(0).asInt());
    assertEquals("second segment = UNKNOWN (5)", 5, surf.get(1).asInt());
}

@Test
public void surfArrayRadiusModeAlsoEmitted() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
    StoredRoute src = buildRoute();
    src.climbs.get(0).segments.get(3).surfaceType = 2; // DIRT
    JsonNode surf = mapper.readTree(b.buildRadiusPayload(src.climbs))
            .get("climbs").get(0).get("surf");
    assertNotNull(surf);
    assertEquals(2, surf.get(3).asInt());
}
```

- [ ] **Step 2: Verify tests fail**

```
./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest
```
Expected: FAIL — `payloadVersionIs2` renamed, version still returns 2, `surf` key absent.

- [ ] **Step 3: Update ClimbPayloadBuilder.java**

Change `SCHEMA_VERSION` from 2 to 3:

```java
private static final int SCHEMA_VERSION = 3;
```

Add the `buildSurf` private static method after `buildCalib`:

```java
private static int[] buildSurf(List<StoredSegment> segs) {
    if (segs == null || segs.isEmpty()) return null;
    boolean allUnknown = true;
    for (StoredSegment s : segs) {
        if (s.surfaceType != nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) {
            allUnknown = false;
            break;
        }
    }
    if (allUnknown) return null;
    int[] arr = new int[segs.size()];
    for (int i = 0; i < segs.size(); i++) {
        arr[i] = nl.paree.climbpro.domain.segment.SurfaceType.fromInt(segs.get(i).surfaceType);
    }
    return arr;
}
```

In `addCommonClimbFields()`, after the `calib` block, add:

```java
int[] surf = buildSurf(sc.segments);
if (surf != null) c.put("surf", surf);
```

- [ ] **Step 4: Verify all payload tests pass**

```
./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest
```
Expected: PASS (all tests green including new surf tests).

- [ ] **Step 5: Run full test suite**

```
./gradlew test
```
Expected: PASS — no regressions.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java \
        android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java
git commit -m "feat: ClimbPayloadBuilder v3 — optional surf[] array per climb"
```

---

## Task 6: ClimbData.mc — segSurf parallel arrays

**Files:**
- Modify: `garmin/source/ClimbData.mc`

No Monkey C unit tests. Verify with compilation only.

- [ ] **Step 1: Add segSurf variable declaration**

In `ClimbData.mc`, after the `var segColor;` line (line 47), add:

```monkeyc
var segSurf;          // surface type per segment: 0=asphalt 1=gravel 2=dirt 3=cobble 4=mixed 5=unknown
```

- [ ] **Step 2: Initialize segSurf in initialize()**

In the `initialize()` function, after `segColor = new [MAX_CLIMBS];`, add:

```monkeyc
segSurf = new [MAX_CLIMBS];
```

Inside the `for (var i = 0; i < MAX_CLIMBS; i++)` loop, after `segColor[i] = new [MAX_SEGMENTS];`, add:

```monkeyc
segSurf[i] = new [MAX_SEGMENTS];
```

Inside the inner `for (var s = 0; s < MAX_SEGMENTS; s++)` loop, after `segColor[i][s] = 0;`, add:

```monkeyc
segSurf[i][s] = 5; // UNKNOWN
```

- [ ] **Step 3: Compile check**

```
monkeyc -o app.prg -f garmin/monkey.jungle -y <developer_key> --device fr255m
```
Expected: BUILD SUCCESSFUL — no compile errors.

- [ ] **Step 4: Commit**

```bash
git add garmin/source/ClimbData.mc
git commit -m "feat: ClimbData adds segSurf parallel array (surface type per segment)"
```

---

## Task 7: CommListener.mc — v3 version check + surf decode

**Files:**
- Modify: `garmin/source/CommListener.mc`

- [ ] **Step 1: Update version check from 2 to 3**

In `PhoneMessageCallback.onMessage()`, find:

```monkeyc
if (version == null || version != 2) {
```

Change to:

```monkeyc
if (version == null || version != 3) {
```

- [ ] **Step 2: Add surf array decoding in parseClimb()**

In `parseClimb()`, after the entire `calib` block (after `data.calibCount[idx] = 0;` closing brace), add:

```monkeyc
// Optional surf array: [surfaceType, ...] one int per segment (parallel to segs)
var surf = climbDict.get("surf");
if (surf != null && surf instanceof Toybox.Lang.Array) {
    var surfSize = surf.size();
    var segCnt = data.segCount[idx];
    for (var s = 0; s < segCnt && s < surfSize; s++) {
        var sv = surf[s];
        if (sv instanceof Toybox.Lang.Number) {
            var si = sv.toNumber();
            data.segSurf[idx][s] = (si >= 0 && si <= 5) ? si : 5;
        } else {
            data.segSurf[idx][s] = 5;
        }
    }
} else {
    // surf absent — all segments unknown
    for (var s = 0; s < data.segCount[idx]; s++) {
        data.segSurf[idx][s] = 5;
    }
}
```

- [ ] **Step 3: Compile check**

```
monkeyc -o app.prg -f garmin/monkey.jungle -y <developer_key> --device fr255m
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add garmin/source/CommListener.mc
git commit -m "feat: CommListener v3 — decode optional surf[] array into segSurf"
```

---

## Task 8: ClimbProView.mc — surface bar below gradient profile

**Files:**
- Modify: `garmin/source/ClimbProView.mc`

- [ ] **Step 1: Add SURFACE_COLORS constant**

In `ClimbProView`, after the existing `hidden const COLORS` block (after line 27), add:

```monkeyc
// Surface type color palette (indices match SurfaceType constants)
hidden const SURFACE_COLORS = [
    0x404040,  // 0: ASPHALT   — dark grey
    0xC8A050,  // 1: GRAVEL    — sandy yellow
    0x8B4513,  // 2: DIRT      — brown
    0x909090,  // 3: COBBLESTONE — medium grey
    0x9060C0,  // 4: MIXED     — purple
];
```

- [ ] **Step 2: Add surfaceColor() helper function**

Add as a hidden function at the end of the class (before the closing `}`):

```monkeyc
hidden function surfaceColor(surfType) {
    if (surfType >= 0 && surfType < SURFACE_COLORS.size()) {
        return SURFACE_COLORS[surfType];
    }
    return -1; // UNKNOWN — caller checks for -1 to skip drawing
}
```

- [ ] **Step 3: Add drawSurfaceBar() function**

Add as a hidden function:

```monkeyc
// Draws a 5px-tall bar directly below the gradient profile.
// barX/barY: top-left corner of the bar (barY = profile bottom + 2).
// barWidth: same pixel width as the gradient profile.
// Skips drawing entirely if all segments are UNKNOWN (surfType == 5).
hidden function drawSurfaceBar(dc, data, ci, barX, barY, barWidth) {
    var segCnt = data.segCount[ci];
    if (segCnt <= 0) { return; }

    var allUnknown = true;
    for (var s = 0; s < segCnt; s++) {
        if (data.segSurf[ci][s] != 5) { allUnknown = false; break; }
    }
    if (allUnknown) { return; }

    var segW = barWidth / segCnt;
    if (segW < 1) { segW = 1; }

    for (var s = 0; s < segCnt; s++) {
        var color = surfaceColor(data.segSurf[ci][s]);
        if (color == -1) { continue; } // UNKNOWN — leave transparent
        dc.setColor(color, Gfx.COLOR_TRANSPARENT);
        var x = barX + s * segW;
        var w = (s == segCnt - 1) ? (barX + barWidth - x) : segW; // fill remainder on last
        dc.fillRectangle(x, barY, w, 5);
    }
}
```

- [ ] **Step 4: Call drawSurfaceBar from drawActiveClimb()**

In `drawActiveClimb()`, find the call to `drawProfile(...)`:

```monkeyc
drawProfile(dc, data, ci, 4,
    profileTop.toNumber(),
    w - 8,
    profileHeight.toNumber()
);
```

Directly after that call, add:

```monkeyc
// Surface bar: 5px tall, 2px below the gradient profile bottom
drawSurfaceBar(dc, data, ci, 4, profileBottom.toNumber() + 2, w - 8);
```

This requires `profileBottom` to be a local variable in `drawActiveClimb`. It is already defined there as `var profileBottom = safeBottom - 18;`.

- [ ] **Step 5: Compile check**

```
monkeyc -o app.prg -f garmin/monkey.jungle -y <developer_key> --device fr255m
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual visual check in simulator**

Open the Connect IQ simulator with FR255M profile, install `.prg`. Send this v3 test payload:

```json
{"v":3,"mode":"route","routeId":"test","climbs":[{"sd":0,"ed":2000,"len":2000,"eg":80,"ag":40,"segs":[125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2],"surf":[0,0,1,1,1,1,0,0,1,1,2,2,3,3,4,5],"calib":[800,5150000,510000]}]}
```

Expected: gradient profile renders as before. Surface bar appears below it with 5px-tall colored blocks (dark grey for segments 0,1,6,7; sandy yellow for 2-5,8,9; brown for 10,11; medium grey for 12,13; purple for 14; no color for segment 15).

- [ ] **Step 7: Commit**

```bash
git add garmin/source/ClimbProView.mc
git commit -m "feat: ClimbProView draws 5px surface bar below gradient profile"
```

---

## Task 9: Android UI — segment surface badge + per-segment long-press dialog

**Files:**
- Modify: `android/app/src/main/res/layout/item_segment.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbSegmentAdapter.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml`

- [ ] **Step 1: Add surface badge to item_segment.xml**

Replace the contents of `android/app/src/main/res/layout/item_segment.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp"
    android:gravity="center_vertical">

    <View
        android:id="@+id/segment_color_bar"
        android:layout_width="12dp"
        android:layout_height="40dp"
        android:layout_marginEnd="12dp"/>

    <TextView
        android:id="@+id/segment_gradient"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="15sp"
        android:textStyle="bold"/>

    <TextView
        android:id="@+id/segment_distance"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:layout_marginEnd="8dp"
        android:textColor="?android:attr/textColorSecondary"/>

    <TextView
        android:id="@+id/segment_surface_badge"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:gravity="center"
        android:textSize="11sp"
        android:textStyle="bold"
        android:textColor="@android:color/white"
        android:visibility="invisible"/>

</LinearLayout>
```

- [ ] **Step 2: Update ClimbSegmentAdapter.java**

Replace the entire file:

```java
package nl.paree.climbpro.ui.climbs;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;
import java.util.List;

public final class ClimbSegmentAdapter
        extends RecyclerView.Adapter<ClimbSegmentAdapter.ViewHolder> {

    public interface OnSegmentLongClickListener {
        void onSegmentLongClick(int position, StoredSegment segment);
    }

    private static final int[] SEGMENT_COLORS = SegmentColorPalette.COLORS;

    // Surface badge background colors (match SurfaceType constants)
    private static final int[] SURFACE_BG = {
        0xFF404040, // ASPHALT — dark grey
        0xFFC8A050, // GRAVEL  — sandy yellow
        0xFF8B4513, // DIRT    — brown
        0xFF909090, // COBBLESTONE — medium grey
        0xFF9060C0, // MIXED   — purple
    };

    private List<StoredSegment> items = new ArrayList<>();
    private OnSegmentLongClickListener longClickListener;

    public void setItems(List<StoredSegment> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnSegmentLongClickListener(OnSegmentLongClickListener l) {
        longClickListener = l;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_segment, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        StoredSegment s = items.get(position);
        h.gradientView.setText(String.format("%.1f%%", s.gradient * 100));
        h.distView.setText(s.distance + " m");
        int ci = Math.max(0, Math.min(5, s.colorIndex));
        h.colorBar.setBackgroundColor(SEGMENT_COLORS[ci]);

        // Surface badge
        String label = SurfaceType.label(s.surfaceType);
        if (label != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(label);
            int st = SurfaceType.fromInt(s.surfaceType);
            if (st < SURFACE_BG.length) {
                h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
            }
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        // Long-press
        h.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onSegmentLongClick(position, s);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView gradientView;
        TextView distView;
        View     colorBar;
        TextView surfaceBadge;
        ViewHolder(View v) {
            super(v);
            gradientView  = v.findViewById(R.id.segment_gradient);
            distView      = v.findViewById(R.id.segment_distance);
            colorBar      = v.findViewById(R.id.segment_color_bar);
            surfaceBadge  = v.findViewById(R.id.segment_surface_badge);
        }
    }
}
```

- [ ] **Step 3: Add setSurfaceType and setBulkSurfaceType to ClimbDetailViewModel.java**

Add these two methods after `reSegment`:

```java
public void setSurfaceType(String routeId, int climbIndex, int segmentIndex, int surfaceType) {
    executor.execute(() -> {
        try {
            routeRepo.setSegmentSurfaceType(routeId, climbIndex, segmentIndex, surfaceType);
            loadClimb(routeId, climbIndex);
            saved.postValue(true);
        } catch (Exception e) {
            error.postValue("Opslaan mislukt: " + e.getMessage());
        }
    });
}

public void setBulkSurfaceType(String routeId, int climbIndex, int surfaceType) {
    executor.execute(() -> {
        try {
            routeRepo.setBulkClimbSurfaceType(routeId, climbIndex, surfaceType);
            loadClimb(routeId, climbIndex);
            saved.postValue(true);
        } catch (Exception e) {
            error.postValue("Opslaan mislukt: " + e.getMessage());
        }
    });
}
```

- [ ] **Step 4: Add bulk setter row to activity_climb_detail.xml**

Open `android/app/src/main/res/layout/activity_climb_detail.xml`. Find the `btn_re_segment` Button element. Directly after its closing tag, add:

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_marginBottom="16dp"
    android:gravity="center_vertical">

    <Spinner
        android:id="@+id/spinner_surface_type"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginEnd="8dp"/>

    <Button
        android:id="@+id/btn_bulk_surface"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Toepassen"/>
</LinearLayout>
```

- [ ] **Step 5: Wire UI in ClimbDetailActivity.java**

First, add these imports at the top of `ClimbDetailActivity.java` (after the existing imports):

```java
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import nl.paree.climbpro.domain.segment.SurfaceType;
```

In `onCreate()`, after `binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());`, add:

```java
setupBulkSurfaceSetter();

adapter.setOnSegmentLongClickListener((position, segment) ->
        showSegmentSurfaceDialog(position, segment));
```

Add these two private methods to the class:

```java
private void setupBulkSurfaceSetter() {
    String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed"};
    ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, typeLabels);
    spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    binding.spinnerSurfaceType.setAdapter(spinnerAdapter);

    binding.btnBulkSurface.setOnClickListener(v -> {
        int selected = binding.spinnerSurfaceType.getSelectedItemPosition();
        // spinner index 0=ASPHALT,1=GRAVEL,2=DIRT,3=COBBLESTONE,4=MIXED
        if (loadedClimb != null && loadedClimb.segments != null && !loadedClimb.segments.isEmpty()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Alle segmenten instellen?")
                    .setMessage("Dit overschrijft alle individuele instellingen voor deze klim.")
                    .setPositiveButton("Toepassen", (d, w) ->
                            viewModel.setBulkSurfaceType(routeId, climbIndex, selected))
                    .setNegativeButton("Annuleer", null)
                    .show();
        }
    });
}

private void showSegmentSurfaceDialog(int segmentIndex, nl.paree.climbpro.data.route.StoredSegment segment) {
    String[] typeLabels = {"Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "Onbekend"};
    int current = SurfaceType.fromInt(segment.surfaceType);

    new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Oppervlak voor segment " + (segmentIndex + 1))
            .setSingleChoiceItems(typeLabels, current, null)
            .setPositiveButton("Opslaan", (dialog, which) -> {
                android.widget.ListView lv =
                        ((androidx.appcompat.app.AlertDialog) dialog).getListView();
                int chosen = lv.getCheckedItemPosition();
                if (chosen >= 0 && chosen <= 5) {
                    viewModel.setSurfaceType(routeId, climbIndex, segmentIndex, chosen);
                }
            })
            .setNegativeButton("Annuleer", null)
            .show();
}
```

- [ ] **Step 6: Build check**

```
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL — no compile errors.

- [ ] **Step 7: Manual test on emulator**

1. `./gradlew installDebug`
2. Import any GPX file → open route → tap a climb
3. Long-press segment 3 → dialog appears with "Oppervlak voor segment 3", 6 radio options
4. Select "Gravel" → "Opslaan" → segment row shows `[G]` badge in sandy-yellow
5. Set spinner to "Kasseien", tap "Toepassen" → confirm → all segments show `[K]` in grey
6. Rotate device / reopen ClimbDetail → surface badges persist

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/res/layout/item_segment.xml \
        android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbSegmentAdapter.java \
        android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java \
        android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java \
        android/app/src/main/res/layout/activity_climb_detail.xml
git commit -m "feat: segment surface badge, long-press dialog, bulk setter in ClimbDetail"
```

---

## Task 10: Route list filter chips

**Files:**
- Modify: `android/app/src/main/res/layout/activity_route_list.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java`

- [ ] **Step 1: Read activity_route_list.xml**

Read `android/app/src/main/res/layout/activity_route_list.xml` to understand the current structure before editing.

- [ ] **Step 2: Add ChipGroup to activity_route_list.xml**

Open `android/app/src/main/res/layout/activity_route_list.xml`. Add a `HorizontalScrollView` containing a `ChipGroup` directly after the `AppBarLayout` closing tag and before the `RecyclerView` (or its parent). The exact placement depends on the current structure; add it as a sibling of the RecyclerView inside the main layout:

```xml
<HorizontalScrollView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:scrollbars="none"
    android:paddingStart="8dp"
    android:paddingEnd="8dp"
    android:paddingTop="4dp"
    android:paddingBottom="4dp">

    <com.google.android.material.chip.ChipGroup
        android:id="@+id/chip_group_surface"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:singleLine="true"
        app:singleSelection="true"
        app:selectionRequired="true">

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_all"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Alle"
            android:checked="true"/>

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_asphalt"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Asfalt"/>

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_gravel"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Gravel"/>

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_dirt"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Onverhard"/>

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_cobblestone"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Kasseien"/>

        <com.google.android.material.chip.Chip
            android:id="@+id/chip_mixed"
            style="@style/Widget.Material3.Chip.Filter"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Mixed"/>

    </com.google.android.material.chip.ChipGroup>
</HorizontalScrollView>
```

- [ ] **Step 3: Add filter logic to RouteListViewModel.java**

Replace the entire file:

```java
package nl.paree.climbpro.ui.routes;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.service.SyncScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouteListViewModel extends AndroidViewModel {

    private final RouteRepository      routeRepo;
    private final StravaAuthRepository authRepo;
    private final ExecutorService      executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<RouteCatalogEntry>> allRoutes   = new MutableLiveData<>();
    private final MutableLiveData<List<RouteCatalogEntry>> routes      = new MutableLiveData<>();
    private final MutableLiveData<String>                  error       = new MutableLiveData<>();
    private final MutableLiveData<Boolean>                 loading     = new MutableLiveData<>(false);

    /** -1 = show all; 0–4 = filter by SurfaceType constant */
    private int activeSurfaceFilter = -1;

    public RouteListViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        authRepo  = new StravaAuthRepository(app);
        loadRoutes();
    }

    public LiveData<List<RouteCatalogEntry>> routes()  { return routes;  }
    public LiveData<String>                  error()   { return error;   }
    public LiveData<Boolean>                 loading() { return loading; }
    public boolean isSignedInToStrava() { return authRepo.isAuthorised(); }

    public void loadRoutes() {
        executor.execute(() -> {
            List<RouteCatalogEntry> all = routeRepo.loadCatalog();
            allRoutes.postValue(all);
            routes.postValue(applyFilter(all, activeSurfaceFilter));
        });
    }

    public void setSurfaceFilter(int surfaceType) {
        activeSurfaceFilter = surfaceType;
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyFilter(all, surfaceType));
        }
    }

    public void deleteRoute(String routeId) {
        executor.execute(() -> {
            try {
                routeRepo.deleteRoute(routeId);
                loadRoutes();
            } catch (Exception e) {
                error.postValue("Delete failed: " + e.getMessage());
            }
        });
    }

    public void triggerSync() {
        SyncScheduler.triggerImmediateSync(getApplication());
    }

    /** Returns routes matching the filter. -1 means "all". */
    private static List<RouteCatalogEntry> applyFilter(List<RouteCatalogEntry> all, int surfaceType) {
        if (surfaceType == -1) return all;
        List<RouteCatalogEntry> result = new ArrayList<>();
        for (RouteCatalogEntry e : all) {
            if (hasSurfaceType(e, surfaceType)) result.add(e);
        }
        return result;
    }

    private static boolean hasSurfaceType(RouteCatalogEntry e, int surfaceType) {
        if (e.surfaceTypes == null) return false;
        for (int t : e.surfaceTypes) {
            if (t == surfaceType) return true;
        }
        return false;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
```

- [ ] **Step 4: Wire chips in RouteListActivity.java**

In `RouteListActivity.onCreate()`, after `viewModel.error().observe(...)`, add:

```java
binding.chipAll.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(-1);
});
binding.chipAsphalt.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(nl.paree.climbpro.domain.segment.SurfaceType.ASPHALT);
});
binding.chipGravel.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(nl.paree.climbpro.domain.segment.SurfaceType.GRAVEL);
});
binding.chipDirt.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(nl.paree.climbpro.domain.segment.SurfaceType.DIRT);
});
binding.chipCobblestone.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(nl.paree.climbpro.domain.segment.SurfaceType.COBBLESTONE);
});
binding.chipMixed.setOnCheckedChangeListener((btn, checked) -> {
    if (checked) viewModel.setSurfaceFilter(nl.paree.climbpro.domain.segment.SurfaceType.MIXED);
});
```

- [ ] **Step 5: Build check**

```
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual test on emulator**

1. Import a GPX route → sets segments to UNKNOWN
2. Set some segments of a climb to Gravel via ClimbDetail → return to route list
3. Tap "Gravel" chip → route appears in list
4. Tap "Asfalt" chip → route disappears (has no asphalt segments)
5. Tap "Alle" → route reappears

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/activity_route_list.xml \
        android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListViewModel.java \
        android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java
git commit -m "feat: route list filter chips by surface type"
```

---

## Verification checklist

After all 10 tasks complete, run the full test suite:

```
./gradlew test
```

Expected: all green. Then verify end-to-end manually:

1. **Import GPX** (Garmin GPX with `<type>road_cycling</type>`) → all segments default to Asfalt badge `[A]`
2. **Import unknown GPX** → all segments show no badge (UNKNOWN)
3. **Long-press segment** → dialog with 6 radio options → select Gravel → badge `[G]` appears
4. **Bulk setter** → spinner "Kasseien" → Toepassen → all segments show `[K]`
5. **Route list Gravel chip** → only routes with ≥1 gravel segment appear
6. **Garmin simulator** → send test payload with mixed `surf` array → surface bar renders below gradient profile with correct colors per segment
7. **Payload budget** → 10-climb route with surf → `./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest#tenClimbPayloadFitsIn4KB` → PASS
