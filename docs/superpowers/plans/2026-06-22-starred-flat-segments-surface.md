# Starred Flat Segments with Surface Tagging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Strava starred segments that are too flat to be climbs (< 3%) as a user-curated, surface-taggable entity — visible in the phone app always, and once a surface is assigned, listed as a separate section in the Garmin widget and pushed to the surface datafield when the route is set active.

**Architecture:** Flat starred segments are matched on the route during Strava sync (phone, CPU only) and stored in a new `StoredStarredSegment` list on `StoredRoute`, preserved across resync by Strava `id`. The phone app lists/edits them. Specialized (surface-assigned) ones flow to the watch via the existing `surfSec` array (surface datafield) and a new `fss` array (widget route payload). Non-specialized stay phone-only.

**Tech Stack:** Java 16+ (Android, POJOs with public fields per existing style), Jackson JSON persistence, JUnit4 + Robolectric + Mockito, JSON Schema (networknt validator), Monkey C (Connect IQ, hand-written, review-only).

## Global Constraints

- **Android language: Java**, not Kotlin. Match existing POJO style (public fields, `@JsonIgnoreProperties(ignoreUnknown = true)`).
- **Climb threshold**: `ClimbConstants.MIN_AVG_GRADIENT` = 0.03 (3%). "Flat" = `averageGrade/100.0 < 0.03`.
- **Specialized** = `surfaceType != SurfaceType.UNKNOWN` (UNKNOWN = 5). A name alone never specializes.
- **Identity** across resync = Strava segment `id` (`long`).
- **Wire format**: v3, packed, short keys. When the wire format changes you MUST update `protocol/schema.json`, `protocol/examples/`, `service/ClimbPayloadBuilder`, AND the hand-written Monkey C parsers together (`garmin-widget/source/CommListener.mc`, `garmin-surface/source/SurfaceData.mc`). Never hand-edit generated Java.
- **Heavy compute on the phone**; the watch only renders.
- **Test command** (run from `android/`): `./gradlew test --tests <FQCN>` (Windows: `.\gradlew.bat test --tests <FQCN>`).
- **Commit** at the end of every task. End commit messages with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `domain/climb/StarredSegmentLocator.java` | add `locateSpan` + `Span`; share index/direction matching with `locate` | 1 |
| `data/route/StoredStarredSegment.java` (new) | persisted starred-flat-segment POJO | 2 |
| `data/route/StoredRoute.java` | add `starredSegments` list | 2 |
| `data/route/RouteRepository.java` | `saveRoute` 4-arg overload, resync preservation, surface-index, edit methods | 2, 4 |
| `data/strava/StravaRoutesRepository.java` | split starred matching into climbs (≥3%) + flat segments (<3%) | 3 |
| `ui/routes/RouteDetailViewModel.java` | include starred in `buildRouteItems`; `updateStarredSegment` | 5 |
| `ui/routes/RouteDetailAdapter.java` | new `VIEW_TYPE_STARRED` row | 5 |
| `res/layout/item_starred_segment.xml` (new) | starred row layout with ★ | 5 |
| `ui/routes/RouteDetailActivity.java` | starred tap → surface dialog | 5 |
| `service/ClimbPayloadBuilder.java` | `surfSec` includes specialized starred; new top-level `fss` | 6, 7 |
| `protocol/schema.json` | add `fss` + `FlatStarredSection` definition | 7 |
| `protocol/examples/route_mode_starred.json` (new) | wire sample with `fss` | 7 |
| `garmin-widget/source/{CommListener,ClimbData,ClimbListView}.mc` | parse + render starred section (review-only) | 8 |
| `Documentation/ARCHITECTURE.md`, `README.md` | document the entity + data flow | 9 |

---

### Task 1: `StarredSegmentLocator.locateSpan`

Extract the on-route matching so a flat starred segment can be located without paying for climb segmentation/calibration. Refactor `locate` to share the index+direction checks (no behaviour change for the climb path).

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/StarredSegmentLocator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/StarredSegmentLocatorTest.java`

**Interfaces:**
- Produces: `StarredSegmentLocator.Span locateSpan(List<RoutePoint> route, double startLat, double startLon, double endLat, double endLon, double maxMatchM)` — returns `null` when not on route or reversed/zero-length. `Span` has public final ints `startDistance, endDistance, length` and doubles `startLat, startLon, endLat, endLon, avgGradient`.

- [ ] **Step 1: Write the failing test**

Add to `StarredSegmentLocatorTest.java`:

```java
@Test
public void locateSpan_flatSegmentOnRoute_returnsSpan() {
    java.util.List<RoutePoint> route = new java.util.ArrayList<>();
    // 5 points, ~100 m apart, nearly flat (1 m gain over 400 m = 0.25%).
    route.add(new RoutePoint(51.0000, 5.0, 0,   0.0));
    route.add(new RoutePoint(51.0009, 5.0, 100, 0.25));
    route.add(new RoutePoint(51.0018, 5.0, 200, 0.50));
    route.add(new RoutePoint(51.0027, 5.0, 300, 0.75));
    route.add(new RoutePoint(51.0036, 5.0, 400, 1.0));

    StarredSegmentLocator.Span span = StarredSegmentLocator.locateSpan(
            route, 51.0000, 5.0, 51.0036, 5.0, 50.0);

    org.junit.Assert.assertNotNull(span);
    org.junit.Assert.assertEquals(0, span.startDistance);
    org.junit.Assert.assertEquals(400, span.endDistance);
    org.junit.Assert.assertEquals(400, span.length);
    org.junit.Assert.assertTrue(span.avgGradient < 0.03);
}

@Test
public void locateSpan_reversed_returnsNull() {
    java.util.List<RoutePoint> route = new java.util.ArrayList<>();
    route.add(new RoutePoint(51.0000, 5.0, 0,   0.0));
    route.add(new RoutePoint(51.0018, 5.0, 200, 0.5));
    route.add(new RoutePoint(51.0036, 5.0, 400, 1.0));
    // end before start along the route → reversed
    org.junit.Assert.assertNull(StarredSegmentLocator.locateSpan(
            route, 51.0036, 5.0, 51.0000, 5.0, 50.0));
}

@Test
public void locateSpan_offRoute_returnsNull() {
    java.util.List<RoutePoint> route = new java.util.ArrayList<>();
    route.add(new RoutePoint(51.0000, 5.0, 0,   0.0));
    route.add(new RoutePoint(51.0036, 5.0, 400, 1.0));
    // start far from any route point (~1 km east)
    org.junit.Assert.assertNull(StarredSegmentLocator.locateSpan(
            route, 51.0000, 5.02, 51.0036, 5.0, 50.0));
}
```

> Note: confirm the `RoutePoint` constructor argument order by reading `domain/route/RoutePoint.java` before running; adjust the literals if it differs from `(lat, lon, distance, elevation)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.climb.StarredSegmentLocatorTest`
Expected: FAIL — `locateSpan` / `Span` do not exist (compile error).

- [ ] **Step 3: Implement `Span`, `locateSpan`, and shared `matchIndices`**

In `StarredSegmentLocator.java`, add the `Span` class and `locateSpan`, and refactor the index/direction logic into a private `matchIndices` helper used by both `locate` and `locateSpan`:

```java
/** Located span of a starred segment on a route (distances cumulative from route start). */
public static final class Span {
    public final int startDistance, endDistance, length;
    public final double startLat, startLon, endLat, endLon, avgGradient;
    Span(int startDistance, int endDistance, int length,
         double startLat, double startLon, double endLat, double endLon, double avgGradient) {
        this.startDistance = startDistance; this.endDistance = endDistance; this.length = length;
        this.startLat = startLat; this.startLon = startLon;
        this.endLat = endLat; this.endLon = endLon; this.avgGradient = avgGradient;
    }
}

public static Span locateSpan(List<RoutePoint> route,
                              double startLat, double startLon,
                              double endLat, double endLon, double maxMatchM) {
    int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
    if (idx == null) return null;
    RoutePoint first = route.get(idx[0]);
    RoutePoint last  = route.get(idx[1]);
    double length = last.distance - first.distance;
    if (length <= 0) return null;
    double eleGain = last.elevation - first.elevation;
    return new Span(
            (int) Math.round(first.distance), (int) Math.round(last.distance),
            (int) Math.round(length),
            first.lat, first.lon, last.lat, last.lon, eleGain / length);
}

/** Shared start/end index match with direction guard; null if not on route or reversed. */
private static int[] matchIndices(List<RoutePoint> route,
                                  double startLat, double startLon,
                                  double endLat, double endLon, double maxMatchM) {
    if (route == null || route.size() < 2) return null;
    int startIdx = nearestIndex(route, startLat, startLon, maxMatchM);
    int endIdx   = nearestIndex(route, endLat, endLon, maxMatchM);
    if (startIdx < 0 || endIdx < 0) return null;
    if (endIdx <= startIdx) return null;
    return new int[]{startIdx, endIdx};
}
```

Then refactor the existing `locate(...)` so its first lines become:

```java
int[] idx = matchIndices(route, startLat, startLon, endLat, endLon, maxMatchM);
if (idx == null) return null;
List<RoutePoint> climbPoints = route.subList(idx[0], idx[1] + 1);
```

(keep the rest of `locate` — `first`/`last`, length guard, `Segmenter` calls, `Climb.builder()` — unchanged).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.domain.climb.StarredSegmentLocatorTest`
Expected: PASS (new tests + the pre-existing `locate` tests still green).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/StarredSegmentLocator.java android/app/src/test/java/nl/paree/climbpro/domain/climb/StarredSegmentLocatorTest.java
git commit -m "feat(android): add StarredSegmentLocator.locateSpan for flat starred segments"
```

---

### Task 2: `StoredStarredSegment` model + persistence with resync preservation

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredStarredSegment.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/RouteRepositoryStarredSegmentTest.java` (new)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `StoredStarredSegment` POJO (fields per code below).
  - `StoredRoute.starredSegments` (`List<StoredStarredSegment>`).
  - `void RouteRepository.saveRoute(StoredRoute route, List<RoutePoint> points, List<Climb> climbs, List<StoredStarredSegment> starredSegments)` — preserves prior `surfaceType`/`userDisplayName` by `stravaId`. Existing 3-arg `saveRoute` delegates with an empty list.

- [ ] **Step 1: Write the failing test**

Create `RouteRepositoryStarredSegmentTest.java`:

```java
package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.climb.Climb;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryStarredSegmentTest {

    private RouteRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app);
    }

    private static List<RoutePoint> points() {
        return new ArrayList<>(Arrays.asList(
                new RoutePoint(51.0000, 5.0, 0,   0.0),
                new RoutePoint(51.0018, 5.0, 200, 0.5),
                new RoutePoint(51.0036, 5.0, 400, 1.0)));
    }

    private static StoredStarredSegment seg(long id, int surface, String userName) {
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = id;
        s.startDistance = 0; s.endDistance = 400; s.length = 400;
        s.startLat = 51.0; s.startLon = 5.0; s.endLat = 51.0036; s.endLon = 5.0;
        s.avgGradient = 0.0025;
        s.name = "Vlak ster";
        s.surfaceType = surface;
        s.userDisplayName = userName;
        return s;
    }

    @Test
    public void saveRoute_persistsStarredSegments() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

        StoredRoute loaded = repo.loadRoute("strava_1");
        assertEquals(1, loaded.starredSegments.size());
        assertEquals(7L, loaded.starredSegments.get(0).stravaId);
        assertEquals(SurfaceType.UNKNOWN, loaded.starredSegments.get(0).surfaceType);
    }

    @Test
    public void saveRoute_resyncPreservesSurfaceAndNameByStravaId() throws Exception {
        StoredRoute r1 = new StoredRoute();
        r1.routeId = "strava_1"; r1.name = "R";
        // Simulate a prior user edit: surface + rename already on the stored segment.
        repo.saveRoute(r1, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.GRAVEL, "Mijn gravel")));

        // Resync: Strava re-supplies the same segment with no surface / no rename.
        StoredRoute r2 = new StoredRoute();
        r2.routeId = "strava_1"; r2.name = "R";
        repo.saveRoute(r2, points(), Collections.<Climb>emptyList(),
                Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

        StoredRoute loaded = repo.loadRoute("strava_1");
        assertEquals(SurfaceType.GRAVEL, loaded.starredSegments.get(0).surfaceType);
        assertEquals("Mijn gravel", loaded.starredSegments.get(0).userDisplayName);
    }

    @Test
    public void saveRoute_threeArgOverload_leavesEmptyStarredList() throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = "strava_1"; r.name = "R";
        repo.saveRoute(r, points(), Collections.<Climb>emptyList());

        StoredRoute loaded = repo.loadRoute("strava_1");
        org.junit.Assert.assertEquals(0,
                loaded.starredSegments == null ? 0 : loaded.starredSegments.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.RouteRepositoryStarredSegmentTest`
Expected: FAIL — `StoredStarredSegment`, `StoredRoute.starredSegments`, and the 4-arg `saveRoute` do not exist.

- [ ] **Step 3: Create the POJO**

`StoredStarredSegment.java`:

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.paree.climbpro.domain.segment.SurfaceType;

/**
 * A Strava starred segment too flat to be a climb (< 3%), located on the route.
 * User-curated and surface-taggable; re-derived from Strava each sync, so user edits
 * (surfaceType, userDisplayName) are preserved across resync by {@link #stravaId}.
 * Distances are integer metres from the route start.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredStarredSegment {
    public long   stravaId;
    public int    startDistance;
    public int    endDistance;
    public int    length;
    public double startLat = Double.NaN, startLon = Double.NaN;
    public double endLat   = Double.NaN, endLon   = Double.NaN;
    public double avgGradient;
    public int    surfaceType = SurfaceType.UNKNOWN;
    /** Strava segment name; re-derived each sync. */
    public String name;
    /** Optional user rename; survives resync. */
    public String userDisplayName;
}
```

- [ ] **Step 4: Add the field to `StoredRoute`**

In `StoredRoute.java`, after `surfaceSections`:

```java
/** Strava starred segments too flat to be climbs (< 3%); user-curated, surface-taggable. */
public List<StoredStarredSegment> starredSegments;
```

- [ ] **Step 5: Add the `saveRoute` overload + preservation + surface index**

In `RouteRepository.java`:

Change the existing 3-arg method to delegate:

```java
public void saveRoute(StoredRoute route, List<RoutePoint> points, List<Climb> climbs) throws IOException {
    saveRoute(route, points, climbs, java.util.Collections.<StoredStarredSegment>emptyList());
}

public void saveRoute(StoredRoute route, List<RoutePoint> points, List<Climb> climbs,
                      List<StoredStarredSegment> starredSegments) throws IOException {
```

(Move the original body into the 4-arg version.) Inside, alongside the existing `prevFlats`/`prevSections` reads, add:

```java
List<StoredStarredSegment> prevStarred = prev != null && prev.starredSegments != null
        ? prev.starredSegments : Collections.emptyList();
```

and after `route.surfaceSections = new ArrayList<>(prevSections);` add:

```java
route.starredSegments = mergePreviousStarredSegmentUserData(starredSegments, prevStarred);
```

Add the helper (mirrors `toStoredFlatSegments` preservation, keyed by `stravaId`):

```java
private static List<StoredStarredSegment> mergePreviousStarredSegmentUserData(
        List<StoredStarredSegment> fresh, List<StoredStarredSegment> previous) {
    List<StoredStarredSegment> result = fresh != null ? new ArrayList<>(fresh) : new ArrayList<>();
    if (previous == null || previous.isEmpty()) return result;
    java.util.Map<Long, Integer> prevSurface = new java.util.HashMap<>();
    java.util.Map<Long, String>  prevName    = new java.util.HashMap<>();
    for (StoredStarredSegment p : previous) {
        if (p.surfaceType != SurfaceType.UNKNOWN) prevSurface.put(p.stravaId, p.surfaceType);
        if (p.userDisplayName != null)             prevName.put(p.stravaId, p.userDisplayName);
    }
    for (StoredStarredSegment s : result) {
        Integer su = prevSurface.get(s.stravaId);
        if (su != null) s.surfaceType = su;
        String nm = prevName.get(s.stravaId);
        if (nm != null) s.userDisplayName = nm;
    }
    return result;
}
```

In `computeSurfaceTypes(StoredRoute route)`, after the `surfaceSections` block, fold in starred surfaces:

```java
if (route.starredSegments != null) {
    for (StoredStarredSegment s : route.starredSegments) {
        if (s.surfaceType != SurfaceType.UNKNOWN) surfaceSet.add(s.surfaceType);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.RouteRepositoryStarredSegmentTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredStarredSegment.java android/app/src/main/java/nl/paree/climbpro/data/route/StoredRoute.java android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/RouteRepositoryStarredSegmentTest.java
git commit -m "feat(android): persist starred flat segments with resync preservation"
```

---

### Task 3: Stop dropping flat starred segments during Strava sync

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java`

**Interfaces:**
- Consumes: `StarredSegmentLocator.locateSpan` (Task 1), `StoredStarredSegment` + 4-arg `saveRoute` (Task 2).
- Produces: `processRoute` now stores flat (< 3%) starred segments in `route.starredSegments`; ≥ 3% still become climbs.

- [ ] **Step 1: Write the failing test**

Add to `StravaRoutesRepositoryTest.java` (reuses the existing `shortClimbGpx` coordinate scheme but flat elevation):

```java
/** A flat single-track GPX (~445 m, ~0% gradient) → ClimbDetector yields zero climbs. */
private static String flatGpx() {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\"?><gpx><trk><trkseg>");
    double lat = 51.0;
    for (int i = 0; i < 6; i++) {
        sb.append(String.format(java.util.Locale.US,
                "<trkpt lat=\"%.6f\" lon=\"5.0\"><ele>10.0</ele></trkpt>", lat));
        lat += 0.0008;
    }
    sb.append("</trkseg></trk></gpx>");
    return sb.toString();
}

@SuppressWarnings("unchecked")
private void stubFlatStarredOnFlatRoute() throws Exception {
    StravaRouteDto dto = new StravaRouteDto();
    dto.id = 123L; dto.name = "Flat Route"; dto.distance = 445f;
    dto.updatedAt = "2026-05-05T00:00:00Z";

    Call<List<StravaRouteDto>> page1 = mock(Call.class);
    when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
    Call<List<StravaRouteDto>> page2 = mock(Call.class);
    when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
    when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
    when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

    Call<ResponseBody> gpx = mock(Call.class);
    when(gpx.execute()).thenReturn(Response.success(
            ResponseBody.create(flatGpx(), MediaType.parse("application/gpx+xml"))));
    when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);

    StravaSegmentDto seg = new StravaSegmentDto();
    seg.id = 777L;
    seg.name = "Vlak Sterstuk";
    seg.averageGrade = 1.0f;                 // < 3% → flat, not a climb
    seg.startLatlng = new double[]{51.0, 5.0};
    seg.endLatlng   = new double[]{51.0040, 5.0};

    Call<List<StravaSegmentDto>> starred1 = mock(Call.class);
    when(starred1.execute()).thenReturn(Response.success(Collections.singletonList(seg)));
    Call<List<StravaSegmentDto>> starred2 = mock(Call.class);
    when(starred2.execute()).thenReturn(Response.success(Collections.<StravaSegmentDto>emptyList()));
    when(api.listStarredSegments(anyString(), eq(1), anyInt())).thenReturn(starred1);
    when(api.listStarredSegments(anyString(), eq(2), anyInt())).thenReturn(starred2);
}

@Test
public void syncRoutes_flatStarredSegment_storedAsStarredNotClimb() throws Exception {
    stubFlatStarredOnFlatRoute();
    StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);

    repo.syncRoutes();

    StoredRoute stored = routeRepo.loadRoute("strava_123");
    assertEquals("flat starred segment must NOT be promoted to a climb",
            0, stored.climbs.size());
    assertEquals("flat starred segment must be stored as a starred segment",
            1, stored.starredSegments.size());
    assertEquals(777L, stored.starredSegments.get(0).stravaId);
    assertEquals("Vlak Sterstuk", stored.starredSegments.get(0).name);
    assertEquals(SurfaceType.UNKNOWN, stored.starredSegments.get(0).surfaceType);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: FAIL — `stored.starredSegments` is null/empty (flat segment currently dropped).

- [ ] **Step 3: Add flat-segment matching and wire it into `processRoute`**

In `StravaRoutesRepository.java`, add the import:

```java
import nl.paree.climbpro.data.route.StoredStarredSegment;
```

Add a sibling matcher next to `matchStarredClimbs`:

```java
/**
 * Builds a {@link StoredStarredSegment} for every starred segment that lies on the route
 * and is too flat to be a climb (avg gradient < {@link ClimbConstants#MIN_AVG_GRADIENT}).
 * Pure CPU; returns an empty list (never null) when nothing qualifies.
 */
private static List<StoredStarredSegment> matchStarredFlatSegments(
        List<RoutePoint> route, List<StravaSegmentDto> starredSegments) {
    if (starredSegments.isEmpty()) return Collections.emptyList();
    List<StoredStarredSegment> result = new ArrayList<>();
    for (StravaSegmentDto seg : starredSegments) {
        if (seg == null) continue;
        if (seg.averageGrade / 100.0 >= ClimbConstants.MIN_AVG_GRADIENT) continue; // climbs handled separately
        if (seg.startLatlng == null || seg.startLatlng.length < 2
                || seg.endLatlng == null || seg.endLatlng.length < 2) continue;

        StarredSegmentLocator.Span span = StarredSegmentLocator.locateSpan(
                route,
                seg.startLatlng[0], seg.startLatlng[1],
                seg.endLatlng[0], seg.endLatlng[1],
                ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M);
        if (span == null) continue;

        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = seg.id;
        s.startDistance = span.startDistance;
        s.endDistance = span.endDistance;
        s.length = span.length;
        s.startLat = span.startLat; s.startLon = span.startLon;
        s.endLat = span.endLat;     s.endLon = span.endLon;
        s.avgGradient = span.avgGradient;
        s.name = seg.name;
        result.add(s);
    }
    return result;
}
```

In `processRoute`, after the `matchStarredClimbs` block (before `StoredRoute stored = new StoredRoute();`), add:

```java
List<StoredStarredSegment> starredFlats = matchStarredFlatSegments(simplified, starredSegments);
```

and change the save call from:

```java
routeRepo.saveRoute(stored, simplified, climbs);
```

to:

```java
routeRepo.saveRoute(stored, simplified, climbs, starredFlats);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: PASS (new test + existing climb/promotion/retry tests still green).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java
git commit -m "feat(android): keep flat starred Strava segments instead of dropping them"
```

---

### Task 4: Repository edit methods for assigning a surface

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/RouteRepositoryStarredSegmentTest.java`

**Interfaces:**
- Produces:
  - `void setStarredSegmentSurface(String routeId, long stravaId, int surfaceType)`
  - `void updateStarredSegment(String routeId, long stravaId, int surfaceType, String name)` — clamps surface via `SurfaceType.fromInt`; blank/whitespace name stored as `null`; rebuilds catalog surface index.

- [ ] **Step 1: Write the failing test**

Add to `RouteRepositoryStarredSegmentTest.java`:

```java
@Test
public void updateStarredSegment_setsSurfaceAndName() throws Exception {
    StoredRoute r = new StoredRoute();
    r.routeId = "strava_1"; r.name = "R";
    repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
            Arrays.asList(seg(7L, SurfaceType.UNKNOWN, null)));

    repo.updateStarredSegment("strava_1", 7L, SurfaceType.DIRT, "  Bospad  ");

    StoredStarredSegment s = repo.loadRoute("strava_1").starredSegments.get(0);
    assertEquals(SurfaceType.DIRT, s.surfaceType);
    assertEquals("Bospad", s.userDisplayName);
}

@Test
public void updateStarredSegment_blankNameClears() throws Exception {
    StoredRoute r = new StoredRoute();
    r.routeId = "strava_1"; r.name = "R";
    repo.saveRoute(r, points(), Collections.<Climb>emptyList(),
            Arrays.asList(seg(7L, SurfaceType.GRAVEL, "Oud")));

    repo.updateStarredSegment("strava_1", 7L, SurfaceType.GRAVEL, "   ");

    assertNull(repo.loadRoute("strava_1").starredSegments.get(0).userDisplayName);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.RouteRepositoryStarredSegmentTest`
Expected: FAIL — `updateStarredSegment` does not exist.

- [ ] **Step 3: Implement the edit methods**

In `RouteRepository.java`, mirroring `updateFlatSegment` (including the `rebuildCatalogSurfaceTypes(routeId, route)` tail):

```java
/** Sets a starred segment's surface type (phone + watch). */
public void setStarredSegmentSurface(String routeId, long stravaId, int surfaceType) throws IOException {
    updateStarredSegment(routeId, stravaId, surfaceType, null);
}

/**
 * Sets a starred segment's surface type and optional display name in a single atomic
 * write, identified by Strava id. A blank/empty name is stored as null. Updates the
 * catalog surface index.
 */
public void updateStarredSegment(String routeId, long stravaId,
                                 int surfaceType, String name) throws IOException {
    StoredRoute route = loadRoute(routeId);
    boolean found = false;
    if (route.starredSegments != null) {
        for (StoredStarredSegment s : route.starredSegments) {
            if (s.stravaId == stravaId) {
                s.surfaceType = SurfaceType.fromInt(surfaceType);
                s.userDisplayName = (name == null || name.trim().isEmpty()) ? null : name.trim();
                found = true;
                break;
            }
        }
    }
    if (!found) {
        Log.w(TAG, "updateStarredSegment: no starred segment with id " + stravaId);
        return;
    }
    route.lastModifiedMs = System.currentTimeMillis();
    writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
    rebuildCatalogSurfaceTypes(routeId, route);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.data.route.RouteRepositoryStarredSegmentTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/RouteRepositoryStarredSegmentTest.java
git commit -m "feat(android): repository edit methods for starred-segment surface"
```

---

### Task 5: App UI — list and tag starred segments

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java`
- Create: `android/app/src/main/res/layout/item_starred_segment.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteDetailViewModelStarredTest.java` (new)

**Interfaces:**
- Consumes: `RouteRepository.updateStarredSegment` (Task 4), `StoredRoute.starredSegments` (Task 2).
- Produces: `buildRouteItems` includes ALL starred segments (specialized + non-specialized) ordered by `startDistance`; `RouteDetailViewModel.updateStarredSegment(String routeId, long stravaId, int surfaceType, String name)`.

- [ ] **Step 1: Write the failing test**

Create `RouteDetailViewModelStarredTest.java`. `buildRouteItems` is currently private; make it package-private (`static List<Object> buildRouteItems(StoredRoute r)`) so it is unit-testable.

```java
package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteDetailViewModelStarredTest {

    private static StoredStarredSegment starred(int startDist, int surface) {
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = startDist;          // unique-enough for the test
        s.startDistance = startDist;
        s.endDistance = startDist + 300;
        s.length = 300;
        s.surfaceType = surface;
        s.name = "S" + startDist;
        return s;
    }

    @Test
    public void buildRouteItems_includesSpecializedAndNonSpecialized_orderedByDistance() {
        StoredRoute r = new StoredRoute();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000; c.endDistance = 2000; c.length = 1000;
        r.climbs = new ArrayList<>(Arrays.asList(c));
        r.starredSegments = new ArrayList<>(Arrays.asList(
                starred(200, SurfaceType.UNKNOWN),   // non-specialized, before climb
                starred(2500, SurfaceType.GRAVEL))); // specialized, after climb

        List<Object> items = RouteDetailViewModel.buildRouteItems(r);

        assertEquals(3, items.size());
        assertTrue("first item is the early starred segment",
                items.get(0) instanceof StoredStarredSegment);
        assertEquals(200, ((StoredStarredSegment) items.get(0)).startDistance);
        assertTrue("middle item is the climb", items.get(1) instanceof StoredClimb);
        assertTrue("last item is the later starred segment",
                items.get(2) instanceof StoredStarredSegment);
        assertEquals(2500, ((StoredStarredSegment) items.get(2)).startDistance);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.ui.routes.RouteDetailViewModelStarredTest`
Expected: FAIL — `buildRouteItems` is private / does not include starred segments.

- [ ] **Step 3: Include starred segments in `buildRouteItems`**

In `RouteDetailViewModel.java`, change `private static` to `static` on `buildRouteItems`, and rewrite it to merge three ordered streams by `startDistance` (climb start for climbs):

```java
static List<Object> buildRouteItems(StoredRoute r) {
    List<StoredFlatSegment>     flats   = r.flatSegments    != null ? r.flatSegments    : Collections.emptyList();
    List<StoredClimb>           climbs  = r.climbs          != null ? r.climbs          : Collections.emptyList();
    List<StoredStarredSegment>  starred = r.starredSegments != null ? r.starredSegments : Collections.emptyList();

    List<Object> result = new ArrayList<>(flats.size() + climbs.size() + starred.size());
    int fi = 0, ci = 0, si = 0;
    while (fi < flats.size() || ci < climbs.size() || si < starred.size()) {
        int flatPos    = fi < flats.size()   ? flats.get(fi).startDistance    : Integer.MAX_VALUE;
        int climbPos   = ci < climbs.size()  ? climbs.get(ci).startDistance   : Integer.MAX_VALUE;
        int starredPos = si < starred.size() ? starred.get(si).startDistance  : Integer.MAX_VALUE;

        if (flatPos <= climbPos && flatPos <= starredPos) {
            result.add(flats.get(fi++));
        } else if (starredPos <= climbPos) {
            result.add(starred.get(si++));
        } else {
            result.add(climbs.get(ci++));
        }
    }
    return result;
}
```

Add the import `import nl.paree.climbpro.data.route.StoredStarredSegment;`.

Add the editing method (mirrors `updateFlatSegment`):

```java
/** Sets a starred segment's surface type and optional name (phone + watch). */
public void updateStarredSegment(String routeId, long stravaId, int surfaceType, String name) {
    executor.execute(() -> {
        try {
            routeRepo.updateStarredSegment(routeId, stravaId, surfaceType, name);
            loadRoute(routeId);
            saved.postValue(true);
        } catch (Exception e) {
            error.postValue("Kon ster-segment niet opslaan: " + e.getMessage());
        }
    });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.ui.routes.RouteDetailViewModelStarredTest`
Expected: PASS.

- [ ] **Step 5: Create the starred-row layout**

`android/app/src/main/res/layout/item_starred_segment.xml` (mirrors `item_flat_segment.xml` with a leading ★):

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
        android:id="@+id/starred_star"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="★"
        android:textSize="16sp"
        android:textColor="#FFC107"
        android:paddingEnd="8dp"
        android:paddingRight="8dp"/>

    <TextView
        android:id="@+id/starred_name"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="14sp"
        android:textColor="?android:attr/textColorPrimary"/>

    <TextView
        android:id="@+id/starred_surface_badge"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:gravity="center"
        android:textSize="11sp"
        android:textStyle="bold"
        android:textColor="@android:color/white"
        android:visibility="invisible"/>

</LinearLayout>
```

- [ ] **Step 6: Add `VIEW_TYPE_STARRED` to the adapter**

In `RouteDetailAdapter.java`:

Add the constant and import:

```java
private static final int VIEW_TYPE_STARRED = 2;
```
```java
import nl.paree.climbpro.data.route.StoredStarredSegment;
```

Add a click-listener interface + field/setter:

```java
public interface OnStarredClickListener { void onStarredClick(StoredStarredSegment seg); }
private OnStarredClickListener starredClickListener;
public void setOnStarredClickListener(OnStarredClickListener l) { starredClickListener = l; }
```

Extend `getItemViewType`:

```java
@Override
public int getItemViewType(int position) {
    Object o = items.get(position);
    if (o instanceof StoredFlatSegment)    return VIEW_TYPE_FLAT;
    if (o instanceof StoredStarredSegment) return VIEW_TYPE_STARRED;
    return VIEW_TYPE_CLIMB;
}
```

Extend `onCreateViewHolder`:

```java
if (viewType == VIEW_TYPE_STARRED) {
    return new StarredViewHolder(inflater.inflate(R.layout.item_starred_segment, parent, false));
}
```

Extend `onBindViewHolder`:

```java
if (holder instanceof StarredViewHolder) {
    bindStarred((StarredViewHolder) holder, (StoredStarredSegment) items.get(position));
    return;
}
```

Add the binder + holder (badge invisible when UNKNOWN, matching flats):

```java
private void bindStarred(StarredViewHolder h, StoredStarredSegment s) {
    String name = s.userDisplayName != null ? s.userDisplayName : s.name;
    h.nameView.setText(String.format("%s · %.1f km",
            name != null ? name : "Ster-segment", s.length / 1000.0));

    String label = SurfaceType.label(s.surfaceType);
    if (label != null) {
        h.surfaceBadge.setVisibility(View.VISIBLE);
        h.surfaceBadge.setText(label);
        int st = SurfaceType.fromInt(s.surfaceType);
        if (st < SURFACE_BG.length) h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
    } else {
        h.surfaceBadge.setVisibility(View.INVISIBLE);
    }

    h.itemView.setOnClickListener(v -> {
        if (starredClickListener != null) starredClickListener.onStarredClick(s);
    });
}

static final class StarredViewHolder extends RecyclerView.ViewHolder {
    TextView nameView;
    TextView surfaceBadge;
    StarredViewHolder(View v) {
        super(v);
        nameView     = v.findViewById(R.id.starred_name);
        surfaceBadge = v.findViewById(R.id.starred_surface_badge);
    }
}
```

- [ ] **Step 7: Wire the tap dialog in the activity**

In `RouteDetailActivity.java`, where the other adapter listeners are set (near `adapter.setOnFlatLongClickListener(...)`), add:

```java
adapter.setOnStarredClickListener(this::showStarredSurfaceDialog);
```

Add the dialog (modelled on `showFlatSurfaceDialog`):

```java
private void showStarredSurfaceDialog(
        nl.paree.climbpro.data.route.StoredStarredSegment seg) {
    android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
    layout.setOrientation(android.widget.LinearLayout.VERTICAL);

    final android.widget.EditText nameInput = new android.widget.EditText(this);
    nameInput.setHint("Naam");
    nameInput.setSingleLine(true);
    if (seg.userDisplayName != null) nameInput.setText(seg.userDisplayName);
    else if (seg.name != null)       nameInput.setText(seg.name);
    layout.addView(nameInput);

    final android.widget.Spinner surface = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, SURFACE_LABELS_NL);
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    surface.setAdapter(a);
    surface.setSelection(SurfaceType.fromInt(seg.surfaceType));
    layout.addView(surface);

    new AlertDialog.Builder(this)
            .setTitle("Ster-segment")
            .setView(layout)
            .setPositiveButton("Opslaan", (dialog, which) ->
                    viewModel.updateStarredSegment(routeId, seg.stravaId,
                            surface.getSelectedItemPosition(),
                            nameInput.getText().toString()))
            .setNegativeButton("Annuleer", null)
            .show();
}
```

- [ ] **Step 8: Build to verify the app compiles**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (UI dialog/adapter rendering is verified by review + manual run; no instrumentation test here).

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailAdapter.java android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java android/app/src/main/res/layout/item_starred_segment.xml android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteDetailViewModelStarredTest.java
git commit -m "feat(android): list and surface-tag starred segments in route detail"
```

---

### Task 6: Surface datafield payload includes specialized starred segments

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/SurfaceSectionPayloadTest.java`

**Interfaces:**
- Consumes: `StoredRoute.starredSegments` (Task 2).
- Produces: `buildSurfaceSectionPayload` merges specialized (`surfaceType != UNKNOWN`) starred segments into `surfSec`, with checkpoints; non-specialized skipped.

- [ ] **Step 1: Write the failing test**

Read `SurfaceSectionPayloadTest.java` for its existing fixture style, then add a test that builds a `StoredRoute` with `distances/lats/lons`, one specialized and one non-specialized starred segment, and asserts the surfSec contains exactly the specialized one. Use the JSON tree shape the file already uses. Example:

```java
@Test
public void surfaceSectionPayload_includesSpecializedStarredOnly() throws Exception {
    StoredRoute route = new StoredRoute();
    route.routeId = "r1";
    route.distances = new double[]{0, 200, 400, 600};
    route.lats = new double[]{51.0, 51.001, 51.002, 51.003};
    route.lons = new double[]{5.0, 5.0, 5.0, 5.0};

    StoredStarredSegment specialized = new StoredStarredSegment();
    specialized.stravaId = 1; specialized.startDistance = 0; specialized.endDistance = 400;
    specialized.length = 400; specialized.surfaceType = SurfaceType.GRAVEL; specialized.name = "Gravel ster";

    StoredStarredSegment plain = new StoredStarredSegment();
    plain.stravaId = 2; plain.startDistance = 400; plain.endDistance = 600;
    plain.length = 200; plain.surfaceType = SurfaceType.UNKNOWN; plain.name = "Naamloos";

    route.starredSegments = new java.util.ArrayList<>(java.util.Arrays.asList(specialized, plain));

    ClimbPayloadBuilder b = new ClimbPayloadBuilder(new ObjectMapper());
    JsonNode payload = new ObjectMapper().readTree(b.buildSurfaceSectionPayload(route));

    JsonNode surfSec = payload.get("surfSec");
    assertEquals(1, surfSec.size());
    assertEquals(0, surfSec.get(0).get("s").asInt());
    assertEquals(400, surfSec.get(0).get("e").asInt());
    assertEquals(SurfaceType.GRAVEL, surfSec.get(0).get("t").asInt());
}
```

(Match the imports/`ObjectMapper` setup already in the test file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceSectionPayloadTest`
Expected: FAIL — `surfSec` is empty (starred segments not yet merged).

- [ ] **Step 3: Merge specialized starred segments into `surfSec`**

In `ClimbPayloadBuilder.buildSurfaceSectionPayload`, after the existing `flatSegments` loop (before the sort), add:

```java
if (route.starredSegments != null) {
    for (StoredStarredSegment s : route.starredSegments) {
        if (s.surfaceType == nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) continue;
        ranges.add(new int[]{s.startDistance, s.endDistance,
                nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType)});
        names.add(s.userDisplayName != null ? s.userDisplayName : s.name);
    }
}
```

Add the import `import nl.paree.climbpro.data.route.StoredStarredSegment;`. (The existing sort + checkpoint generation then covers them.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.service.SurfaceSectionPayloadTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/SurfaceSectionPayloadTest.java
git commit -m "feat(android): send specialized starred segments to surface datafield"
```

---

### Task 7: Widget route payload `fss` array + schema/examples/round-trip

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Modify: `protocol/schema.json`
- Create: `protocol/examples/route_mode_starred.json`
- Modify: `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java`

**Interfaces:**
- Consumes: `StoredRoute.starredSegments` (Task 2).
- Produces: `buildRoutePayload` emits optional top-level `fss:[{s,e,t,n?}, ...]` containing only specialized starred segments; omitted when none qualify. Schema gains `fss` + `FlatStarredSection`.

- [ ] **Step 1: Write the failing test**

In `ProtocolRoundTripTest.java`:

(a) Add `route.starredSegments` to `routeFixture()` so the existing `builderRoutePayloadValidatesAgainstSchema` exercises `fss`:

```java
StoredStarredSegment ss = new StoredStarredSegment();
ss.stravaId = 99; ss.startDistance = 3200; ss.endDistance = 3600; ss.length = 400;
ss.surfaceType = SurfaceType.GRAVEL; ss.name = "Gravel ster";
route.starredSegments = new ArrayList<>(Arrays.asList(ss));
```
(add `import nl.paree.climbpro.data.route.StoredStarredSegment;`)

(b) Add `"route_mode_starred.json"` to the `EXAMPLES` array.

(c) Add a content assertion test:

```java
@Test
public void builderRoutePayload_fssContainsOnlySpecialized() throws Exception {
    StoredRoute route = routeFixture();
    StoredStarredSegment plain = new StoredStarredSegment();
    plain.stravaId = 100; plain.startDistance = 4000; plain.endDistance = 4300;
    plain.length = 300; plain.surfaceType = SurfaceType.UNKNOWN; plain.name = "Naamloos";
    route.starredSegments.add(plain);

    ClimbPayloadBuilder b = new ClimbPayloadBuilder(MAPPER);
    JsonNode payload = MAPPER.readTree(b.buildRoutePayload(route));

    JsonNode fss = payload.get("fss");
    assertTrue("fss present", fss != null && fss.isArray());
    org.junit.Assert.assertEquals("only the specialized segment is included", 1, fss.size());
    org.junit.Assert.assertEquals(3200, fss.get(0).get("s").asInt());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest`
Expected: FAIL — missing example resource and/or `fss` not emitted / not in schema.

- [ ] **Step 3: Emit `fss` from `buildRoutePayload`**

In `ClimbPayloadBuilder.java`, in `buildRoutePayload(StoredRoute route, int[][] targetSeconds)`, after `payload.put("climbs", climbs);` and before `return`:

```java
List<Map<String, Object>> fss = buildFlatStarredSections(route.starredSegments);
if (fss != null && !fss.isEmpty()) payload.put("fss", fss);
```

Add the helper:

```java
/** Specialized (surface-assigned) starred segments for the widget list. Null if none. */
private static List<Map<String, Object>> buildFlatStarredSections(
        List<StoredStarredSegment> segs) {
    if (segs == null || segs.isEmpty()) return null;
    List<Map<String, Object>> out = new ArrayList<>();
    for (StoredStarredSegment s : segs) {
        if (s.surfaceType == nl.paree.climbpro.domain.segment.SurfaceType.UNKNOWN) continue;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", s.startDistance);
        m.put("e", s.endDistance);
        m.put("t", nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType));
        String n = s.userDisplayName != null ? s.userDisplayName : s.name;
        if (n != null && n.length() <= 24) m.put("n", n);
        out.add(m);
    }
    return out;
}
```

(`StoredStarredSegment` import already added in Task 6.) Also update the class Javadoc `Format:` block to mention the optional top-level `fss` array.

- [ ] **Step 4: Update the schema**

In `protocol/schema.json`, add to top-level `properties` (after `surfSec`):

```json
"fss": {
  "description": "Specialized starred flat segments (route mode), shown as a separate list section in the widget. Only segments with an assigned surface (t 0-4) are sent. Omitted when none qualify.",
  "type": "array",
  "maxItems": 32,
  "items": { "$ref": "#/definitions/FlatStarredSection" }
},
```

And add to `definitions` (after `SurfaceSection`):

```json
"FlatStarredSection": {
  "type": "object",
  "additionalProperties": false,
  "required": ["s", "e", "t"],
  "properties": {
    "s": { "description": "Metres from route start where the segment begins.", "type": "integer", "minimum": 0 },
    "e": { "description": "Metres from route start where the segment ends (> s).", "type": "integer", "minimum": 1 },
    "t": { "description": "SurfaceType: 0=asphalt 1=gravel 2=dirt 3=cobblestone 4=mixed.", "type": "integer", "minimum": 0, "maximum": 4 },
    "n": { "description": "Optional display name (<= 24 chars).", "type": "string", "maxLength": 24 }
  }
}
```

- [ ] **Step 5: Create the example wire sample**

`protocol/examples/route_mode_starred.json`:

```json
{
  "v": 3,
  "mode": "route",
  "routeId": "demo_starred",
  "name": "Starred demo route",
  "climbs": [],
  "fss": [
    { "s": 3200, "e": 3600, "t": 1, "n": "Gravel ster" },
    { "s": 5000, "e": 5400, "t": 2 }
  ]
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd android && ./gradlew test --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest`
Expected: PASS (examples + live builder route/radius/surface payloads all validate; `fss` content test green).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java protocol/schema.json protocol/examples/route_mode_starred.json android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java
git commit -m "feat(protocol): add fss array for specialized starred segments"
```

---

### Task 8: Monkey C — parse `fss` and render the widget section (review-only)

No JVM harness exists for Monkey C (per CLAUDE.md it is review-only). Verify by code review and the Connect IQ simulator for the Forerunner 255 Music profile. This task MUST land in the same change-set window as Task 7 so the wire format stays symmetric.

**Files:**
- Modify: `garmin-widget/source/ClimbData.mc`
- Modify: `garmin-widget/source/CommListener.mc`
- Modify: `garmin-widget/source/ClimbListView.mc`
- Review: `garmin-surface/source/SurfaceData.mc` (no format change — starred segments arrive as ordinary `surfSec` entries)
- Review: `garmin/source/CommListener.mc` (climb datafield — ignores the unknown `fss` key; no change)

- [ ] **Step 1: Add storage fields to `ClimbData.mc`**

Add a cap constant and parallel arrays:

```monkeyc
const MAX_FLAT_STARRED = 16;

var flatStarredCount = 0;
var flatStarredStart;   // start distance (m)
var flatStarredEnd;     // end distance (m)
var flatStarredSurf;    // surface type 0-4
var flatStarredName;    // String or null
```

In `initialize()`, allocate + zero them:

```monkeyc
flatStarredStart = new [MAX_FLAT_STARRED];
flatStarredEnd   = new [MAX_FLAT_STARRED];
flatStarredSurf  = new [MAX_FLAT_STARRED];
flatStarredName  = new [MAX_FLAT_STARRED];
for (var i = 0; i < MAX_FLAT_STARRED; i++) {
    flatStarredStart[i] = 0;
    flatStarredEnd[i]   = 0;
    flatStarredSurf[i]  = 5;
    flatStarredName[i]  = null;
}
```

- [ ] **Step 2: Parse `fss` in `CommListener.mc`**

In `onMessage`, after the `climbs` block (and before `data.payloadReceived = true;`), reset then fill — mirroring the `surf` reset discipline so a resync without `fss` clears stale entries:

```monkeyc
data.flatStarredCount = 0;
var fss = msg.get("fss");
if (fss != null && fss instanceof Toybox.Lang.Array) {
    var maxF = data.MAX_FLAT_STARRED < fss.size() ? data.MAX_FLAT_STARRED : fss.size();
    data.flatStarredCount = maxF;
    for (var i = 0; i < maxF; i++) {
        var fd = fss[i];
        if (fd instanceof Toybox.Lang.Dictionary) {
            data.flatStarredStart[i] = getInt(fd, "s", 0);
            data.flatStarredEnd[i]   = getInt(fd, "e", 0);
            data.flatStarredSurf[i]  = getInt(fd, "t", 5);
            data.flatStarredName[i]  = fd.get("n");
        }
    }
}
```

- [ ] **Step 3: Render a separate "Ster-segmenten" section in `ClimbListView.mc`**

Change `totalItems` to include the starred rows and add a labelled section between the climbs and the action rows. Update both `onUpdate` and the delegate's index math so:
- indices `0 .. climbCount-1` → climbs (unchanged),
- next `flatStarredCount` indices → starred rows (draw `flatStarredName[k]` or `"Ster " + (k+1)`, plus the surface label via a single-char map `["A","G","D","K","M"]`),
- then the existing `Save/Delete route` and `Zet actief` rows (now at `climbCount + flatStarredCount` and `+1`).

Keep the changes localized: compute `var starredBase = data.climbCount;` and `var actionBase = data.climbCount + data.flatStarredCount;` and branch on those in the draw loop, `onSelect`, and `onNextPage` bounds. Tapping a starred row is display-only (no detail view required for this feature).

- [ ] **Step 4: Compile the widget (simulator toolchain)**

Run (if the Connect IQ SDK is installed): `monkeyc -o build/widget.prg -f garmin-widget/monkey.jungle -y <developer_key>`
Expected: compiles clean. Then load in the simulator (Forerunner 255 Music) and confirm a route with a specialized starred segment shows the new section; a route without one shows no section.

> If the SDK is not available in this environment, mark this step verified-by-review and note it for the user to run in the simulator.

- [ ] **Step 5: Commit**

```bash
git add garmin-widget/source/ClimbData.mc garmin-widget/source/CommListener.mc garmin-widget/source/ClimbListView.mc
git commit -m "feat(garmin-widget): show specialized starred segments as a list section"
```

---

### Task 9: Documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Update ARCHITECTURE.md**

Document the new `StoredStarredSegment` entity, the < 3% flat-starred matching during Strava sync, the specialization rule (surface assigned), and the two-surface data flow (`fss` → widget list, `surfSec` → surface datafield; non-specialized phone-only). Add `fss` to any wire-format section.

- [ ] **Step 2: Update README.md**

Add a short feature description: starred Strava segments too flat to be climbs can be tagged with a surface in the app; tagged ones appear in the watch widget and are sent to the surface datafield when the route is active.

- [ ] **Step 3: Full test sweep**

Run: `cd android && ./gradlew test`
Expected: BUILD SUCCESSFUL — the whole suite passes.

- [ ] **Step 4: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: document starred flat segments with surface tagging"
```

---

## Self-Review

**Spec coverage:**
- Stop dropping flat starred segments → Task 3. ✓
- Dedicated `StoredStarredSegment` + resync preservation by `stravaId` → Task 2. ✓
- Specialized = surface assigned; name alone insufficient → enforced in Tasks 6 (`surfSec`), 7 (`fss`). ✓
- App shows all (specialized + non-specialized), tag via dialog → Task 5. ✓
- Surface datafield gets specialized (auto on active — handler already wired at `WatchRequestHandler:112`) → Task 6. ✓
- Widget separate list section → Tasks 7 (`fss`) + 8 (Monkey C). ✓
- Protocol bookkeeping (schema + examples + round-trip) → Task 7. ✓
- Docs → Task 9. ✓

**Type/name consistency:** `StoredStarredSegment` fields, `saveRoute(...,List<StoredStarredSegment>)`, `updateStarredSegment(String,long,int,String)`, `StarredSegmentLocator.Span`/`locateSpan`, wire key `fss` with `FlatStarredSection` (s/e/t/n) — used identically across Tasks 1–8.

**Placeholder scan:** none — every code step contains concrete code; the only deliberately review-only work is Monkey C (Task 8), per CLAUDE.md.

**Verification notes for the implementer:** confirm the `RoutePoint` constructor arg order (Task 1) and the exact `ObjectMapper`/import style of `SurfaceSectionPayloadTest` (Task 6) before running those tasks; both are read-then-adjust, not guesses about behaviour.
