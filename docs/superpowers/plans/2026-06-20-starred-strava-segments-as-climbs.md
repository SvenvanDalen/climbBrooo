# Starred Strava Segments as Climbs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When syncing a route from Strava, any *starred* Strava segment that lies on the route and has an average gradient ≥ 3% is always shown as a climb — even if it is shorter than the normal 800 m minimum.

**Architecture:** During `StravaRoutesRepository.processRoute`, after the normal `ClimbDetector` run, fetch the route's segment list (`GET /routes/{id}`) and the athlete's starred-segment IDs (`GET /segments/starred`). For each starred segment on the route with `average_grade ≥ 3%`, locate its start/end on the already-simplified route geometry and build a `Climb` (no false-flat trim, named after the segment). Merge those into the detected climbs — where a starred segment overlaps a detected climb, the **starred segment's bounds win**. The merged list is then saved exactly as today.

**Tech Stack:** Java, Retrofit + Jackson (Strava API), JUnit + Mockito + Robolectric (tests). No wire-format / protocol change — starred climbs are ordinary `Climb` objects with the usual 8%-fraction segments.

---

## Design decisions (resolved with the user, 2026-06-20)

- **Criterion:** keep the **≥ 3% average-gradient** rule, **drop** the 800 m minimum. A starred segment becomes a climb whenever its Strava `average_grade ≥ 3%`, regardless of length. The 3% gate is checked against Strava's `average_grade` (the segment's authoritative grade), not the route-derived gradient.
- **Overlap:** when a starred segment overlaps a normally-detected climb, **the starred segment's bounds replace** the detected climb (no duplicates).
- **Naming:** the climb takes the Strava segment's name (`Climb.name`). This sets the base `name` only — a user's manual rename (`userDisplayName`) is still preserved across resync by the existing `mergePreviousClimbUserData`.
- **Trimming:** **no** false-flat trim on starred-segment climbs — use the exact matched start/end.
- **Scope:** Strava-synced routes only. Manual GPX-file imports have no segment data and are untouched.
- **Offline-first:** if either new API call fails or returns nothing, fall back silently to the normally-detected climbs. Sync must never break because the segment endpoints are unreachable.

## File structure

- **Create** `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaSegmentDto.java` — Jackson DTO for a Strava segment (id, name, average_grade, start/end latlng).
- **Create** `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDetailDto.java` — Jackson DTO for `GET /routes/{id}`, carrying the `segments` list.
- **Modify** `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java` — add `getRoute` and `listStarredSegments` endpoints.
- **Modify** `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java` — add `STARRED_SEGMENT_MATCH_MAX_M`.
- **Create** `android/app/src/main/java/nl/paree/climbpro/domain/climb/StarredSegmentLocator.java` — pure: build a `Climb` from a route + a segment's start/end coordinates.
- **Create** `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbMerger.java` — pure: merge starred climbs into detected climbs, starred bounds win on overlap.
- **Modify** `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java` — fetch starred segments, build + merge starred climbs into `processRoute`.
- **Create** `android/app/src/test/java/nl/paree/climbpro/domain/climb/StarredSegmentLocatorTest.java`
- **Create** `android/app/src/test/java/nl/paree/climbpro/domain/climb/ClimbMergerTest.java`
- **Modify** `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java` — end-to-end promotion + naming.
- **Modify** `Documentation/ARCHITECTURE.md` — document starred-segment promotion in the climb-detection flow.

**No change** to `protocol/schema.json`, `protocol/examples/`, `ClimbPayloadBuilder`, or the Monkey C parsers: a promoted climb is an ordinary `Climb` and serialises through the existing path. (Verified: nothing downstream filters climbs by `MIN_CLIMB_LENGTH_M`; the payload path is byte-budget driven only.)

---

## Task 1: `ClimbMerger` — merge starred climbs into detected climbs

Pure domain logic, no Strava/Android dependencies. Starred bounds win on overlap; result sorted by `startDistance`.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbMerger.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/ClimbMergerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ClimbMergerTest {

    private static Climb climb(int start, int end, String name) {
        return Climb.builder()
                .startDistance(start)
                .endDistance(end)
                .length(end - start)
                .name(name)
                .build();
    }

    @Test
    public void noStarred_returnsDetectedSortedByStart() {
        List<Climb> detected = Arrays.asList(climb(1000, 2000, "b"), climb(0, 500, "a"));
        List<Climb> result = ClimbMerger.merge(detected, Collections.<Climb>emptyList());

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).name);
        assertEquals("b", result.get(1).name);
    }

    @Test
    public void overlappingStarred_replacesDetectedClimb() {
        List<Climb> detected = Collections.singletonList(climb(1000, 2500, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1200, 1600, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(1, result.size());
        assertEquals("starred", result.get(0).name);
        assertEquals(1200, result.get(0).startDistance);
        assertEquals(1600, result.get(0).endDistance);
    }

    @Test
    public void nonOverlappingStarred_isAddedAndSorted() {
        List<Climb> detected = Collections.singletonList(climb(0, 500, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1000, 1300, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(2, result.size());
        assertEquals("detected", result.get(0).name);
        assertEquals("starred", result.get(1).name);
    }

    @Test
    public void touchingBoundsDoNotCountAsOverlap() {
        // detected [0,1000), starred [1000,1400) share only the endpoint -> both kept.
        List<Climb> detected = Collections.singletonList(climb(0, 1000, "detected"));
        List<Climb> starred  = Collections.singletonList(climb(1000, 1400, "starred"));

        List<Climb> result = ClimbMerger.merge(detected, starred);

        assertEquals(2, result.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.climb.ClimbMergerTest`
Expected: FAIL with compile error "cannot find symbol: ClimbMerger".

- [ ] **Step 3: Write the implementation**

```java
package nl.paree.climbpro.domain.climb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Merges starred-segment climbs into the normally-detected climbs.
 * Where a starred climb overlaps a detected one, the starred climb's bounds win
 * (the detected climb is dropped). The result is sorted by start distance.
 *
 * Two climbs overlap iff their [startDistance, endDistance) ranges intersect;
 * sharing only an endpoint does NOT count as overlap.
 */
public final class ClimbMerger {

    private ClimbMerger() {}

    public static List<Climb> merge(List<Climb> detected, List<Climb> starred) {
        List<Climb> result = new ArrayList<>(detected != null ? detected : Collections.<Climb>emptyList());
        if (starred != null) {
            for (Climb s : starred) {
                result.removeIf(c -> overlaps(c, s));
                result.add(s);
            }
        }
        result.sort(Comparator.comparingInt(c -> c.startDistance));
        return result;
    }

    private static boolean overlaps(Climb a, Climb b) {
        return a.startDistance < b.endDistance && b.startDistance < a.endDistance;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.climb.ClimbMergerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbMerger.java \
        android/app/src/test/java/nl/paree/climbpro/domain/climb/ClimbMergerTest.java
git commit -m "feat(android): add ClimbMerger for starred-segment override"
```

---

## Task 2: `STARRED_SEGMENT_MATCH_MAX_M` constant

The maximum allowed distance between a Strava segment endpoint and the nearest route point for the segment to be considered "on this route".

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java:18`

- [ ] **Step 1: Add the constant**

In `ClimbConstants.java`, immediately after the `ROUTE_MATCHING_HYSTERESIS_M` line (currently line 20), add:

```java
    /**
     * Max distance (metres) between a Strava starred-segment endpoint and the nearest
     * route point for the segment to count as lying on the route. Generous enough to
     * absorb Douglas-Peucker simplification (~5 m epsilon) plus Strava/GPX rounding.
     */
    public static final int    STARRED_SEGMENT_MATCH_MAX_M  = 50;
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java
git commit -m "feat(android): add STARRED_SEGMENT_MATCH_MAX_M constant"
```

---

## Task 3: `StarredSegmentLocator` — build a Climb from segment coordinates

Pure domain. Given the simplified route and a segment's start/end lat-lng, find the nearest route points and build a `Climb` (no trim) named after the segment. Returns `null` if either endpoint is farther than `maxMatchM` from the route, or if the matched span is empty/backwards.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/StarredSegmentLocator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/StarredSegmentLocatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;

public class StarredSegmentLocatorTest {

    /** A straight south-to-north route, ~89 m per step, climbing 6 m per step (~6.7%). */
    private static List<RoutePoint> straightClimb(int points) {
        List<RoutePoint> raw = new ArrayList<>();
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < points; i++) {
            raw.add(new RoutePoint(lat, 5.0, ele, 0));
            lat += 0.0008;
            ele += 6.0;
        }
        return CumulativeDistance.compute(raw);
    }

    @Test
    public void locatesSegmentSpanningMiddleOfRoute() {
        List<RoutePoint> route = straightClimb(12);
        // Segment from route point 3 to point 8 (exact coordinates).
        RoutePoint s = route.get(3);
        RoutePoint e = route.get(8);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Test Berg", 50.0);

        assertTrue(c != null);
        assertEquals("Test Berg", c.name);
        assertEquals((int) Math.round(s.distance), c.startDistance);
        assertEquals((int) Math.round(e.distance), c.endDistance);
        assertTrue("climb must have segments", c.segments.size() > 0);
        assertTrue("startLat must be set for the matched point", c.hasCoordinates());
    }

    @Test
    public void returnsNullWhenStartIsFarFromRoute() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint e = route.get(8);

        // Start ~1.5 km west of the route (0.02 deg lon at 51N ~ 1.4 km).
        Climb c = StarredSegmentLocator.locate(
                route, 51.004, 5.02, e.lat, e.lon, "Off Route", 50.0);

        assertNull(c);
    }

    @Test
    public void returnsNullWhenSpanIsBackwards() {
        List<RoutePoint> route = straightClimb(12);
        RoutePoint s = route.get(8);
        RoutePoint e = route.get(3);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Reversed", 50.0);

        assertNull(c);
    }

    @Test
    public void shortSegmentBelow800mStillProducesClimb() {
        List<RoutePoint> route = straightClimb(12);
        // Points 2..6 span ~4 steps ~356 m -> below the 800 m detector minimum.
        RoutePoint s = route.get(2);
        RoutePoint e = route.get(6);

        Climb c = StarredSegmentLocator.locate(
                route, s.lat, s.lon, e.lat, e.lon, "Kort Klimmetje", 50.0);

        assertTrue(c != null);
        assertFalse("span is intentionally short",
                c.length >= ClimbConstants.MIN_CLIMB_LENGTH_M);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.climb.StarredSegmentLocatorTest`
Expected: FAIL with compile error "cannot find symbol: StarredSegmentLocator".

- [ ] **Step 3: Write the implementation**

```java
package nl.paree.climbpro.domain.climb;

import java.util.List;

import nl.paree.climbpro.domain.route.CumulativeDistance;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import nl.paree.climbpro.domain.segment.Segment;
import nl.paree.climbpro.domain.segment.Segmenter;

/**
 * Builds a {@link Climb} for a Strava starred segment by locating its start/end
 * coordinates on an already-simplified route (distances cumulative from the route
 * start). No false-flat trim is applied — the exact matched span is used.
 *
 * Returns {@code null} when the segment does not lie on the route: either endpoint
 * farther than {@code maxMatchM} from the nearest route point, or the matched end
 * is at/before the matched start (segment traversed in the opposite direction).
 */
public final class StarredSegmentLocator {

    private StarredSegmentLocator() {}

    public static Climb locate(List<RoutePoint> route,
                               double startLat, double startLon,
                               double endLat, double endLon,
                               String name, double maxMatchM) {
        if (route == null || route.size() < 2) return null;

        int startIdx = nearestIndex(route, startLat, startLon, maxMatchM);
        int endIdx   = nearestIndex(route, endLat, endLon, maxMatchM);
        if (startIdx < 0 || endIdx < 0) return null;
        if (endIdx <= startIdx) return null;

        List<RoutePoint> climbPoints = route.subList(startIdx, endIdx + 1);
        RoutePoint first = climbPoints.get(0);
        RoutePoint last  = climbPoints.get(climbPoints.size() - 1);

        double length  = last.distance - first.distance;
        if (length <= 0) return null;
        double eleGain = last.elevation - first.elevation;
        double grad    = eleGain / length;

        List<Segment> segments = Segmenter.segment(climbPoints);
        List<CalibrationPoint> calib = Segmenter.calibrationPoints(climbPoints);

        return Climb.builder()
                .startDistance((int) Math.round(first.distance))
                .endDistance((int) Math.round(last.distance))
                .length((int) Math.round(length))
                .elevationGain((int) Math.round(eleGain))
                .avgGradient(grad)
                .startLat(first.lat)
                .startLon(first.lon)
                .name(name)
                .segments(segments)
                .calibrationPoints(calib)
                .build();
    }

    /**
     * Index of the route point nearest to (lat, lon), or -1 if the nearest point
     * is farther than maxMatchM metres.
     */
    private static int nearestIndex(List<RoutePoint> route, double lat, double lon, double maxMatchM) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            RoutePoint p = route.get(i);
            double d = CumulativeDistance.haversine(lat, lon, p.lat, p.lon);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return bestDist <= maxMatchM ? best : -1;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.domain.climb.StarredSegmentLocatorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/StarredSegmentLocator.java \
        android/app/src/test/java/nl/paree/climbpro/domain/climb/StarredSegmentLocatorTest.java
git commit -m "feat(android): add StarredSegmentLocator to build climbs from segment coords"
```

---

## Task 4: Strava DTOs for segments and route detail

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaSegmentDto.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDetailDto.java`

- [ ] **Step 1: Create `StravaSegmentDto.java`**

```java
package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Partial mapping of a Strava segment, as it appears both inside a route's
 * {@code segments} list ({@code GET /routes/{id}}) and in the starred-segment
 * list ({@code GET /segments/starred}).
 *
 * {@code averageGrade} is a percentage (e.g. 7.2 means 7.2%).
 * {@code startLatlng} / {@code endLatlng} are [lat, lng] pairs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaSegmentDto {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("average_grade")
    public float averageGrade;

    @JsonProperty("distance")
    public float distance;

    @JsonProperty("start_latlng")
    public double[] startLatlng;

    @JsonProperty("end_latlng")
    public double[] endLatlng;
}
```

- [ ] **Step 2: Create `StravaRouteDetailDto.java`**

```java
package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Partial mapping of the Strava {@code GET /routes/{id}} response.
 * Only the {@code segments} list is consumed (to find starred segments on the route).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaRouteDetailDto {

    @JsonProperty("segments")
    public List<StravaSegmentDto> segments;
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaSegmentDto.java \
        android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRouteDetailDto.java
git commit -m "feat(android): add Strava segment + route-detail DTOs"
```

---

## Task 5: Strava API endpoints for route detail + starred segments

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java`

- [ ] **Step 1: Add the two endpoints**

In `StravaApiClient.java`, add these methods inside the interface, after the existing `exportGpx` method (currently ends at line 25):

```java
    @GET("routes/{id}")
    Call<StravaRouteDetailDto> getRoute(
            @Header("Authorization") String bearerToken,
            @Path("id") long routeId);

    @GET("segments/starred")
    Call<List<StravaSegmentDto>> listStarredSegments(
            @Header("Authorization") String bearerToken,
            @Query("page") int page,
            @Query("per_page") int perPage);
```

(The `Call`, `GET`, `Header`, `Path`, `Query`, and `List` imports already exist in this file.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java
git commit -m "feat(android): add Strava getRoute + listStarredSegments endpoints"
```

---

## Task 6: Wire starred-segment promotion into `processRoute`

After the normal climb detection, fetch starred segments on the route, build climbs for the qualifying ones, and merge them in. All network failures degrade gracefully to "no starred climbs" (offline-first). Unstubbed mocks in existing tests return `null` Calls — the null guards below keep those tests green.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java`

- [ ] **Step 1: Add imports**

In `StravaRoutesRepository.java`, add to the import block (after the existing `okhttp3`/`retrofit2` imports, before the `java.io` imports):

```java
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbMerger;
import nl.paree.climbpro.domain.climb.StarredSegmentLocator;

import retrofit2.Call;
```

And in the `java.util` import group add:

```java
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
```

(`java.util.ArrayList`, `java.util.List`, and `retrofit2.Response` are already imported.)

- [ ] **Step 2: Merge starred climbs in `processRoute`**

In `processRoute`, the climbs are detected at (currently) line 111:

```java
            List<Climb>      climbs     = ClimbDetector.detect(simplified);
```

Immediately after that line, insert:

```java
            List<Climb> starredClimbs = fetchStarredClimbs(token, dto.id, simplified);
            if (!starredClimbs.isEmpty()) {
                climbs = ClimbMerger.merge(climbs, starredClimbs);
                Log.i(TAG, "Promoted " + starredClimbs.size()
                        + " starred segment(s) to climbs on " + routeId);
            }
```

(`climbs` is a local `List<Climb>`, so reassigning it is fine.)

- [ ] **Step 3: Add the two private helper methods**

Add these methods to `StravaRoutesRepository`, just before the `private static Retrofit buildRetrofit()` method (currently line 158):

```java
    /**
     * Fetches the route's segment list and the athlete's starred segments, then builds a
     * {@link Climb} for every starred segment that lies on the route and has an average
     * gradient >= {@link ClimbConstants#MIN_AVG_GRADIENT}. Returns an empty list (never null)
     * on any failure or when nothing qualifies — sync must not break when Strava is
     * unreachable or the endpoints change.
     */
    private List<Climb> fetchStarredClimbs(String token, long routeId, List<RoutePoint> route) {
        try {
            Call<StravaRouteDetailDto> detailCall = api.getRoute(token, routeId);
            if (detailCall == null) return Collections.emptyList();
            Response<StravaRouteDetailDto> detailResp = detailCall.execute();
            if (!detailResp.isSuccessful() || detailResp.body() == null
                    || detailResp.body().segments == null) {
                return Collections.emptyList();
            }

            Set<Long> starredIds = fetchStarredSegmentIds(token);
            if (starredIds.isEmpty()) return Collections.emptyList();

            List<Climb> result = new ArrayList<>();
            for (StravaSegmentDto seg : detailResp.body().segments) {
                if (seg == null || !starredIds.contains(seg.id)) continue;
                // Gate on Strava's authoritative segment grade: keep the >= 3% rule,
                // drop the 800 m minimum (per product decision 2026-06-20).
                if (seg.averageGrade / 100.0 < ClimbConstants.MIN_AVG_GRADIENT) continue;
                if (seg.startLatlng == null || seg.startLatlng.length < 2
                        || seg.endLatlng == null || seg.endLatlng.length < 2) continue;

                Climb c = StarredSegmentLocator.locate(
                        route,
                        seg.startLatlng[0], seg.startLatlng[1],
                        seg.endLatlng[0], seg.endLatlng[1],
                        seg.name,
                        ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M);
                if (c != null) result.add(c);
            }
            return result;
        } catch (IOException e) {
            Log.w(TAG, "Starred-segment fetch failed for route " + routeId + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Collects the IDs of all the athlete's starred segments (paginated). */
    private Set<Long> fetchStarredSegmentIds(String token) throws IOException {
        Set<Long> ids = new HashSet<>();
        int page = 1;
        while (true) {
            Call<List<StravaSegmentDto>> call = api.listStarredSegments(token, page, 50);
            if (call == null) break;
            Response<List<StravaSegmentDto>> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;
            for (StravaSegmentDto s : resp.body()) {
                if (s != null) ids.add(s.id);
            }
            page++;
        }
        return ids;
    }
```

- [ ] **Step 4: Verify existing tests still pass (graceful degradation)**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: PASS — the existing tests don't stub `getRoute`/`listStarredSegments`, so the mocks return `null` Calls, the null guards return an empty list, and behaviour is unchanged.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java
git commit -m "feat(android): promote starred Strava segments to climbs on route sync"
```

---

## Task 7: End-to-end test — short starred segment becomes a named climb

Proves the full path: a starred segment shorter than 800 m, on a route that the normal detector would yield nothing for, is promoted to a named climb after sync.

**Files:**
- Modify: `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java`

- [ ] **Step 1: Add the test fixtures and test**

Add these imports to the test's import block (near the existing `nl.paree.climbpro` imports):

```java
import nl.paree.climbpro.data.route.StoredClimb;
```

Add these helper methods and the test inside the `StravaRoutesRepositoryTest` class (e.g. after `stubClimbRoute`):

```java
    /**
     * A short, steep GPX: ~5 steps of ~89 m at ~6.7% -> ~445 m total.
     * Below the 800 m detector minimum, so ClimbDetector yields zero climbs.
     */
    private static String shortClimbGpx() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?><gpx><trk><trkseg>");
        double lat = 51.0;
        double ele = 0.0;
        for (int i = 0; i < 6; i++) {
            sb.append(String.format(java.util.Locale.US,
                    "<trkpt lat=\"%.6f\" lon=\"5.0\"><ele>%.1f</ele></trkpt>", lat, ele));
            lat += 0.0008;
            ele += 6.0;
        }
        sb.append("</trkseg></trk></gpx>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void stubStarredOnShortRoute() throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Short Route";
        dto.distance = 445f;
        dto.updatedAt = "2026-03-03T00:00:00Z";

        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(shortClimbGpx(), MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);

        // Route detail: one segment spanning the whole short route, ~6.7% grade, starred id 555.
        StravaSegmentDto seg = new StravaSegmentDto();
        seg.id = 555L;
        seg.name = "Kort Sterklimmetje";
        seg.averageGrade = 6.7f;
        seg.startLatlng = new double[]{51.0, 5.0};
        seg.endLatlng = new double[]{51.0040, 5.0}; // 51.0 + 5*0.0008
        StravaRouteDetailDto detail = new StravaRouteDetailDto();
        detail.segments = Collections.singletonList(seg);
        Call<StravaRouteDetailDto> detailCall = mock(Call.class);
        when(detailCall.execute()).thenReturn(Response.success(detail));
        when(api.getRoute(anyString(), eq(123L))).thenReturn(detailCall);

        // Starred list: page 1 has the segment, page 2 empty.
        Call<List<StravaSegmentDto>> starred1 = mock(Call.class);
        when(starred1.execute()).thenReturn(Response.success(Collections.singletonList(seg)));
        Call<List<StravaSegmentDto>> starred2 = mock(Call.class);
        when(starred2.execute()).thenReturn(Response.success(Collections.<StravaSegmentDto>emptyList()));
        when(api.listStarredSegments(anyString(), eq(1), anyInt())).thenReturn(starred1);
        when(api.listStarredSegments(anyString(), eq(2), anyInt())).thenReturn(starred2);
    }

    @Test
    public void syncRoutes_shortStarredSegment_isPromotedToNamedClimb() throws Exception {
        stubStarredOnShortRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);

        repo.syncRoutes();

        StoredRoute stored = routeRepo.loadRoute("strava_123");
        assertEquals("the short starred segment must become the only climb",
                1, stored.climbs.size());
        StoredClimb climb = stored.climbs.get(0);
        assertEquals("Kort Sterklimmetje", climb.name);
        assertEquals("climb is below the 800 m detector minimum -> proves promotion",
                true, climb.length < ClimbConstants.MIN_CLIMB_LENGTH_M);
    }
```

- [ ] **Step 2: Run the new test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: PASS — all existing tests plus `syncRoutes_shortStarredSegment_isPromotedToNamedClimb`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java
git commit -m "test(android): starred segment below 800 m is promoted to named climb"
```

---

## Task 8: Full test suite + documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Document the promotion rule in ARCHITECTURE.md**

In `Documentation/ARCHITECTURE.md`, find the section describing climb detection (search for `ClimbDetector` or "800"). Add a paragraph:

```markdown
### Starred Strava segments as climbs

When a route is synced from Strava, any **starred** Strava segment that lies on
the route and has an average gradient ≥ 3% is promoted to a climb, even if it is
shorter than the normal 800 m minimum. The phone fetches the route's segment list
(`GET /routes/{id}`) and the athlete's starred-segment IDs (`GET /segments/starred`),
matches each qualifying segment's start/end onto the simplified route geometry
(`StarredSegmentLocator`, within `STARRED_SEGMENT_MATCH_MAX_M` of a route point),
and merges the result into the detected climbs (`ClimbMerger`). On overlap, the
starred segment's bounds replace the detected climb. The 3% gate uses Strava's
authoritative `average_grade`; no false-flat trim is applied; the climb is named
after the segment (a user's manual rename still survives resync). The fetch is
best-effort — any network failure falls back to the normally-detected climbs.
This applies to Strava-synced routes only; manual GPX imports have no segment data.
```

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md
git commit -m "docs: describe starred-segment climb promotion"
```

---

## Self-review notes

- **Spec coverage:** ≥3% gate kept / 800 m dropped (Task 6 gate + Task 3/7 short-climb proof); overlap = starred wins (Task 1 `ClimbMerger`); naming from segment (Task 3 builder + Task 7 assertion, `userDisplayName` rename preserved by existing `mergePreviousClimbUserData`); no trim (Task 3 uses exact span); Strava-only + offline-first (Task 6 null/exception guards).
- **Type consistency:** `StarredSegmentLocator.locate(...)` signature is identical in Task 3 and its caller in Task 6. `ClimbMerger.merge(List<Climb>, List<Climb>)` identical in Tasks 1 and 6. DTO field names (`averageGrade`, `startLatlng`, `endLatlng`, `segments`) consistent across Tasks 4, 6, 7.
- **No protocol change:** promoted climbs are ordinary `Climb` objects; `schema.json`/examples/`ClimbPayloadBuilder`/Monkey C are untouched (verified no downstream `MIN_CLIMB_LENGTH_M` filter).
- **Note on test commands:** `./gradlew` targets are the *intended* toolchain (CLAUDE.md: build files not yet scaffolded). If the Gradle wrapper isn't present when executing, scaffold it first or run via the configured IDE/test runner; the task names (`:app:testDebugUnitTest`, `--tests <FQCN>`) are otherwise correct.
