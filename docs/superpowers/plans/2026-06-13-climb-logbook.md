# Climb Logbook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a phone-only "Climb Logbook": match the rider's Strava activities (last 12 months) against known climbs and show, per climb, past attempts, the personal record (PR), and a trend — without touching the watch or the wire protocol.

**Architecture:** Pure domain helpers derive a route-independent climb identity, enumerate known climbs from stored routes, and match an activity GPS track to a climb (entry/exit by proximity to climb start/end + length validation → elapsed seconds). A new Strava activities repository fetches activities + streams and persists matched attempts to `climb_attempts.json`. New UI (a Logbook screen + a history block on the climb detail screen) reads those attempts. No changes to `StoredRoute`, `protocol/schema.json`, the Monkey C watch app, or the sync payload.

**Tech Stack:** Java 11, Android (MVVM + Repository), Jackson (JSON files under `getFilesDir()`), Retrofit + OkHttp (Strava), JUnit4 + Robolectric + Mockito (tests). Build/test: `./gradlew test`.

---

## File Structure

**New (domain — pure, no Android deps):**
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbIdentity.java` — route-independent key from start coord + length.
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimb.java` — value object: climbId, start/end coords, length.
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimbs.java` — builds `List<KnownClimb>` from a `StoredRoute`.
- `android/app/src/main/java/nl/paree/climbpro/domain/matching/ClimbAttemptMatcher.java` — track + climb endpoints + length → elapsed seconds or -1; includes nested `TrackSample`.
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/LogbookCalculator.java` — pure presentation logic: per-climb summaries + per-climb sorted history with PR/delta; includes nested `Summary` and `HistoryRow`.

**New (data):**
- `android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimbAttempt.java` — serialised attempt.
- `android/app/src/main/java/nl/paree/climbpro/data/route/ClimbAttemptRepository.java` — `climb_attempts.json` load/append with dedupe.
- `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivityDto.java` — activity list item.
- `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaStreamsDto.java` — latlng/time/(altitude) streams.
- `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivitiesRepository.java` — fetch + match + persist; tracks last sync.

**Modified (data):**
- `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java` — add `listActivities` + `getStreams`.

**New (UI):**
- `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookViewModel.java`
- `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookActivity.java`
- `android/app/src/main/java/nl/paree/climbpro/ui/climbs/LogbookAdapter.java`
- `android/app/src/main/res/layout/activity_climb_logbook.xml`
- `android/app/src/main/res/layout/item_logbook_climb.xml`

**Modified (UI):**
- `android/app/src/main/res/menu/route_list_menu.xml` — add "Logboek" entry.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java` — launch the Logbook.
- `android/app/src/main/AndroidManifest.xml` — register `ClimbLogbookActivity`.
- `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java` — expose attempt history for the open climb.
- `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` + its layout — render the history block.

**New (tests):** one alongside each logic-bearing class (paths given per task).

---

## Task 1: ClimbIdentity (route-independent climb key)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbIdentity.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/ClimbIdentityTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class ClimbIdentityTest {

    @Test
    public void sameClimbAcrossRoutes_yieldsSameKey() {
        // Two imports of the same climb with tiny GPS/length jitter must collapse.
        String a = ClimbIdentity.of(45.83210, 6.86420, 9000);
        String b = ClimbIdentity.of(45.83225, 6.86411, 9040);
        assertEquals(a, b);
    }

    @Test
    public void differentClimbs_yieldDifferentKeys() {
        String a = ClimbIdentity.of(45.83210, 6.86420, 9000);
        String farAway = ClimbIdentity.of(46.10000, 7.20000, 9000);
        assertNotEquals(a, farAway);

        String muchLonger = ClimbIdentity.of(45.83210, 6.86420, 12000);
        assertNotEquals(a, muchLonger);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.ClimbIdentityTest`
Expected: FAIL — `ClimbIdentity` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.climb;

/**
 * Route-independent identity for a climb, so the same climb appearing in several
 * routes collapses onto one logbook record. Built from the climb start coordinate
 * (bucketed to absorb GPS jitter) and its length (bucketed to 100 m).
 */
public final class ClimbIdentity {

    /** ~0.0005 deg latitude ≈ 55 m: buckets absorb GPS/simplification jitter. */
    private static final double COORD_BUCKET_DEG = 0.0005;
    private static final double LENGTH_BUCKET_M  = 100.0;

    private ClimbIdentity() {}

    public static String of(double startLat, double startLon, int lengthM) {
        long latBucket = Math.round(startLat / COORD_BUCKET_DEG);
        long lonBucket = Math.round(startLon / COORD_BUCKET_DEG);
        long lenBucket = Math.round(lengthM / LENGTH_BUCKET_M);
        return latBucket + ":" + lonBucket + ":" + lenBucket;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.ClimbIdentityTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbIdentity.java android/app/src/test/java/nl/paree/climbpro/domain/climb/ClimbIdentityTest.java
git commit -m "feat(climb): route-independent ClimbIdentity key"
```

---

## Task 2: KnownClimb + KnownClimbs.fromRoute

Derives, for each climb in a stored route, its identity, start/end coordinates, and length. End coordinate is the route point nearest `endDistance` (StoredClimb has no stored end coord).

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimb.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimbs.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/KnownClimbsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class KnownClimbsTest {

    @Test
    public void fromRoute_derivesEndCoordAndIdentity() {
        StoredRoute route = new StoredRoute();
        route.lats        = new double[]{45.0, 45.001, 45.002, 45.003};
        route.lons        = new double[]{6.0,  6.001,  6.002,  6.003};
        route.elevations  = new double[]{100,  150,    200,    250};
        route.distances   = new double[]{0,    400,    800,    1200};

        StoredClimb c = new StoredClimb();
        c.startDistance = 0;
        c.endDistance   = 800;
        c.length        = 800;
        c.startLat      = 45.0;
        c.startLon      = 6.0;
        route.climbs    = Collections.singletonList(c);

        List<KnownClimb> known = KnownClimbs.fromRoute(route);

        assertEquals(1, known.size());
        KnownClimb k = known.get(0);
        assertEquals(45.0, k.startLat, 1e-9);
        assertEquals(6.0,  k.startLon, 1e-9);
        // End coord = route point nearest endDistance (800) -> index 2.
        assertEquals(45.002, k.endLat, 1e-9);
        assertEquals(6.002,  k.endLon, 1e-9);
        assertEquals(800, k.lengthM);
        assertEquals(ClimbIdentity.of(45.0, 6.0, 800), k.climbId);
    }

    @Test
    public void fromRoute_nullClimbs_returnsEmpty() {
        StoredRoute route = new StoredRoute();
        assertTrue(KnownClimbs.fromRoute(route).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.KnownClimbsTest`
Expected: FAIL — `KnownClimb` / `KnownClimbs` do not exist.

- [ ] **Step 3: Write minimal implementation**

`KnownClimb.java`:

```java
package nl.paree.climbpro.domain.climb;

/** Immutable, route-independent description of a climb used for activity matching. */
public final class KnownClimb {

    public final String climbId;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    public final int    lengthM;

    public KnownClimb(String climbId, double startLat, double startLon,
                      double endLat, double endLon, int lengthM) {
        this.climbId  = climbId;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat   = endLat;
        this.endLon   = endLon;
        this.lengthM  = lengthM;
    }
}
```

`KnownClimbs.java`:

```java
package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds route-independent {@link KnownClimb}s from a stored route. */
public final class KnownClimbs {

    private KnownClimbs() {}

    public static List<KnownClimb> fromRoute(StoredRoute route) {
        if (route == null || route.climbs == null || route.lats == null
                || route.lats.length == 0) {
            return Collections.emptyList();
        }
        List<KnownClimb> out = new ArrayList<>(route.climbs.size());
        for (StoredClimb c : route.climbs) {
            int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
            int endIdx = nearestIndex(route.distances, c.endDistance);
            out.add(new KnownClimb(
                    ClimbIdentity.of(c.startLat, c.startLon, len),
                    c.startLat, c.startLon,
                    route.lats[endIdx], route.lons[endIdx],
                    len));
        }
        return out;
    }

    private static int nearestIndex(double[] distances, int targetM) {
        int best = 0;
        double bestDiff = Math.abs(distances[0] - targetM);
        for (int i = 1; i < distances.length; i++) {
            double diff = Math.abs(distances[i] - targetM);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.KnownClimbsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimb.java android/app/src/main/java/nl/paree/climbpro/domain/climb/KnownClimbs.java android/app/src/test/java/nl/paree/climbpro/domain/climb/KnownClimbsTest.java
git commit -m "feat(climb): KnownClimbs.fromRoute derives match endpoints + identity"
```

---

## Task 3: ClimbAttemptMatcher (track → elapsed seconds)

Pure matcher. Finds the track sample nearest the climb start (entry) and, after it, the sample nearest the climb end (exit), each within a proximity gate. Validates that the distance covered between them is within tolerance of the climb length (rejects crossing roads / partial passes). Returns elapsed seconds, or -1 when no valid attempt. Reuses `CumulativeDistance.haversine`.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/matching/ClimbAttemptMatcher.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/matching/ClimbAttemptMatcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.matching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClimbAttemptMatcherTest {

    /** ~111.32 m per 0.001 deg latitude near the equator; good enough for synthetic tracks. */
    private static List<TrackSample> straightNorthTrack(int samples, double startLat,
                                                        double lon, double dLatPerStep,
                                                        long startTimeSec, long stepSec) {
        List<TrackSample> t = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            t.add(new TrackSample(startLat + i * dLatPerStep, lon,
                    startTimeSec + i * stepSec));
        }
        return t;
    }

    @Test
    public void fullPass_returnsElapsedSeconds() {
        // Climb from 45.000 to ~45.009 (~1000 m), length 1000 m.
        // Track: 10 steps of 0.001 deg lat, 60 s each -> passes start and end.
        List<TrackSample> track = straightNorthTrack(11, 45.000, 6.0, 0.001, 1_000, 60);

        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);

        // Entry at index 0 (t=1000), exit nearest 45.009 at index 9 (t=1540): 540 s.
        assertEquals(540, elapsed);
    }

    @Test
    public void noNearbyStart_returnsMinusOne() {
        List<TrackSample> track = straightNorthTrack(11, 48.000, 9.0, 0.001, 0, 60);
        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);
        assertEquals(-1, elapsed);
    }

    @Test
    public void crossingRoad_tooShortCovered_returnsMinusOne() {
        // Track only reaches the start area then leaves: covered distance far below length.
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.000, 6.0, 0));
        track.add(new TrackSample(45.0005, 6.05, 60));   // veers east, away from the climb line
        track.add(new TrackSample(45.001, 6.10, 120));
        int elapsed = ClimbAttemptMatcher.match(
                track, 45.000, 6.0, 45.009, 6.0, 1000);
        assertEquals(-1, elapsed);
    }

    @Test
    public void tooFewSamples_returnsMinusOne() {
        List<TrackSample> track = new ArrayList<>();
        track.add(new TrackSample(45.0, 6.0, 0));
        assertTrue(ClimbAttemptMatcher.match(track, 45.0, 6.0, 45.009, 6.0, 1000) < 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.domain.matching.ClimbAttemptMatcherTest`
Expected: FAIL — `ClimbAttemptMatcher` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.matching;

import nl.paree.climbpro.domain.route.CumulativeDistance;

import java.util.List;

/**
 * Matches a recorded GPS track against a single known climb.
 *
 * Strategy: find the track sample nearest the climb start (the entry) and, at a
 * later index, the sample nearest the climb end (the exit). Both must lie within
 * {@link #GATE_M}. The distance covered between entry and exit must be within
 * {@link #LENGTH_TOLERANCE} of the climb length, which rejects crossing roads and
 * partial passes. Returns the elapsed time on the climb in seconds, or -1.
 */
public final class ClimbAttemptMatcher {

    /** Max distance (m) a track sample may be from the climb start/end to count. */
    public static final double GATE_M = 40.0;
    /** Covered distance must be within ±25% of the climb length. */
    public static final double LENGTH_TOLERANCE = 0.25;

    private ClimbAttemptMatcher() {}

    /** One GPS fix: position + absolute epoch seconds. */
    public static final class TrackSample {
        public final double lat;
        public final double lon;
        public final long   timeSec;

        public TrackSample(double lat, double lon, long timeSec) {
            this.lat = lat;
            this.lon = lon;
            this.timeSec = timeSec;
        }
    }

    /** @return elapsed seconds on the climb, or -1 if no valid attempt is found. */
    public static int match(List<TrackSample> track,
                            double startLat, double startLon,
                            double endLat, double endLon,
                            int climbLengthM) {
        if (track == null || track.size() < 2 || climbLengthM <= 0) return -1;

        int entryIdx = nearestWithin(track, startLat, startLon, 0);
        if (entryIdx < 0) return -1;

        int exitIdx = nearestWithin(track, endLat, endLon, entryIdx + 1);
        if (exitIdx < 0 || exitIdx <= entryIdx) return -1;

        double covered = 0;
        for (int i = entryIdx; i < exitIdx; i++) {
            TrackSample a = track.get(i);
            TrackSample b = track.get(i + 1);
            covered += CumulativeDistance.haversine(a.lat, a.lon, b.lat, b.lon);
        }
        double tol = LENGTH_TOLERANCE * climbLengthM;
        if (Math.abs(covered - climbLengthM) > tol) return -1;

        long elapsed = track.get(exitIdx).timeSec - track.get(entryIdx).timeSec;
        if (elapsed <= 0) return -1;
        return (int) elapsed;
    }

    /** Index of the sample at/after {@code fromIdx} nearest to (lat,lon) within GATE_M, or -1. */
    private static int nearestWithin(List<ClimbAttemptMatcher.TrackSample> track,
                                     double lat, double lon, int fromIdx) {
        int best = -1;
        double bestDist = GATE_M;
        for (int i = fromIdx; i < track.size(); i++) {
            TrackSample s = track.get(i);
            double d = CumulativeDistance.haversine(lat, lon, s.lat, s.lon);
            if (d <= bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.matching.ClimbAttemptMatcherTest`
Expected: PASS (all four).

> If `fullPass` asserts a different elapsed value, read the printed actual: the exit index is the sample nearest 45.009. With 0.001 steps the nearest is index 9 (45.009) at t=1540, entry index 0 at t=1000 → 540 s. Do not change the implementation to chase a wrong expectation; fix the expectation only if the geometry math genuinely differs.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/matching/ClimbAttemptMatcher.java android/app/src/test/java/nl/paree/climbpro/domain/matching/ClimbAttemptMatcherTest.java
git commit -m "feat(matching): ClimbAttemptMatcher maps an activity track to climb time"
```

---

## Task 4: StoredClimbAttempt + ClimbAttemptRepository

JSON-file persistence for matched attempts, mirroring the `RouteRepository` atomic-write idiom. One file `climb_attempts.json` holds a flat array. Appends dedupe on `(climbId, activityId)`.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimbAttempt.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/ClimbAttemptRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/route/ClimbAttemptRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbAttemptRepositoryTest {

    private static StoredClimbAttempt attempt(String climbId, long activityId,
                                              long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId       = climbId;
        a.activityId    = activityId;
        a.dateEpochSec  = dateSec;
        a.elapsedSec    = elapsed;
        a.avgSpeedKmh   = 18.0;
        return a;
    }

    @Test
    public void appendThenLoad_roundTrips() {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(
                attempt("k1", 100L, 1_700_000_000L, 600),
                attempt("k2", 100L, 1_700_000_000L, 720)));

        List<StoredClimbAttempt> all = repo.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    public void append_dedupesOnClimbAndActivity() {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);

        repo.append(Arrays.asList(attempt("k1", 100L, 1_700_000_000L, 600)));
        repo.append(Arrays.asList(attempt("k1", 100L, 1_700_000_000L, 600))); // same pair

        assertEquals(1, repo.loadAll().size());
    }

    @Test
    public void knownActivityIds_collectsAll() {
        Application app = ApplicationProvider.getApplicationContext();
        ClimbAttemptRepository repo = new ClimbAttemptRepository(app);
        repo.append(Arrays.asList(
                attempt("k1", 100L, 1L, 600),
                attempt("k2", 101L, 1L, 600)));

        assertEquals(true, repo.knownActivityIds().contains(100L));
        assertEquals(true, repo.knownActivityIds().contains(101L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.data.route.ClimbAttemptRepositoryTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write minimal implementation**

`StoredClimbAttempt.java`:

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Serialised form of a single matched climb attempt. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredClimbAttempt {

    public String climbId;       // ClimbIdentity key
    public long   activityId;    // Strava activity id (dedupe)
    public long   dateEpochSec;  // activity start time
    public int    elapsedSec;    // time on the climb
    public double avgSpeedKmh;   // derived from length / elapsed
}
```

`ClimbAttemptRepository.java`:

```java
package nl.paree.climbpro.data.route;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-file persistence for matched climb attempts.
 * Layout: getFilesDir()/climb_attempts.json — a flat array of {@link StoredClimbAttempt}.
 * Writes are atomic (temp file + rename), mirroring RouteRepository.
 */
public final class ClimbAttemptRepository {

    private static final String TAG  = "ClimbAttemptRepo";
    private static final String FILE = "climb_attempts.json";

    private final File file;
    private final ObjectMapper mapper;

    public ClimbAttemptRepository(Context context) {
        Context app = context.getApplicationContext();
        this.file   = new File(app.getFilesDir(), FILE);
        this.mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    public List<StoredClimbAttempt> loadAll() {
        if (!file.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            StoredClimbAttempt[] arr = mapper.readValue(in, StoredClimbAttempt[].class);
            return new ArrayList<>(Arrays.asList(arr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load climb attempts", e);
            return new ArrayList<>();
        }
    }

    public Set<Long> knownActivityIds() {
        Set<Long> ids = new HashSet<>();
        for (StoredClimbAttempt a : loadAll()) ids.add(a.activityId);
        return ids;
    }

    /** Appends attempts, skipping any whose (climbId, activityId) already exists. */
    public void append(List<StoredClimbAttempt> attempts) {
        List<StoredClimbAttempt> all = loadAll();
        Set<String> seen = new HashSet<>();
        for (StoredClimbAttempt a : all) seen.add(key(a));
        for (StoredClimbAttempt a : attempts) {
            if (seen.add(key(a))) all.add(a);
        }
        try {
            writeAtomic(file, mapper.writeValueAsBytes(all));
        } catch (IOException e) {
            Log.e(TAG, "Failed to persist climb attempts", e);
        }
    }

    private static String key(StoredClimbAttempt a) {
        return a.climbId + "#" + a.activityId;
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.data.route.ClimbAttemptRepositoryTest`
Expected: PASS (all three).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimbAttempt.java android/app/src/main/java/nl/paree/climbpro/data/route/ClimbAttemptRepository.java android/app/src/test/java/nl/paree/climbpro/data/route/ClimbAttemptRepositoryTest.java
git commit -m "feat(data): ClimbAttemptRepository persists matched attempts with dedupe"
```

---

## Task 5: LogbookCalculator (summaries + per-climb history)

Pure presentation logic over `List<StoredClimbAttempt>`: per-climb summary (PR = fastest elapsed, attempt count, last ridden date) for the logbook list, and a per-climb history (attempts newest-first with delta-to-PR) for the detail screen.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/LogbookCalculator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/climb/LogbookCalculatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;
import nl.paree.climbpro.domain.climb.LogbookCalculator.Summary;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LogbookCalculatorTest {

    private static StoredClimbAttempt at(String climbId, long actId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId; a.activityId = actId; a.dateEpochSec = dateSec; a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void summaries_pickPrAndCountAndLastDate() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k1", 2, 2000, 650),   // PR (fastest)
                at("k1", 3, 1500, 680),
                at("k2", 4, 3000, 900));

        Map<String, Summary> s = LogbookCalculator.summaries(attempts);

        assertEquals(650, s.get("k1").prSec);
        assertEquals(3,   s.get("k1").attemptCount);
        assertEquals(2000L, s.get("k1").lastDateSec); // most recent date
        assertEquals(900, s.get("k2").prSec);
        assertEquals(1,   s.get("k2").attemptCount);
    }

    @Test
    public void historyFor_sortsNewestFirst_withDeltaToPr() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                at("k1", 1, 1000, 700),
                at("k1", 2, 2000, 650),   // PR
                at("k1", 3, 1500, 680));

        List<HistoryRow> rows = LogbookCalculator.historyFor("k1", attempts);

        assertEquals(3, rows.size());
        assertEquals(2000L, rows.get(0).dateEpochSec); // newest first
        assertEquals(0,   rows.get(0).deltaToPrSec);   // this one is the PR
        assertEquals(1000L, rows.get(2).dateEpochSec);
        assertEquals(50,  rows.get(2).deltaToPrSec);   // 700 - 650
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.LogbookCalculatorTest`
Expected: FAIL — `LogbookCalculator` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure presentation logic over stored climb attempts. */
public final class LogbookCalculator {

    private LogbookCalculator() {}

    /** Per-climb roll-up for the logbook list. */
    public static final class Summary {
        public final String climbId;
        public final int    prSec;        // fastest elapsed
        public final int    attemptCount;
        public final long   lastDateSec;  // most recent attempt date

        public Summary(String climbId, int prSec, int attemptCount, long lastDateSec) {
            this.climbId = climbId;
            this.prSec = prSec;
            this.attemptCount = attemptCount;
            this.lastDateSec = lastDateSec;
        }
    }

    /** One attempt as shown on the detail history list. */
    public static final class HistoryRow {
        public final long dateEpochSec;
        public final int  elapsedSec;
        public final int  deltaToPrSec; // elapsedSec - prSec (>= 0)

        public HistoryRow(long dateEpochSec, int elapsedSec, int deltaToPrSec) {
            this.dateEpochSec = dateEpochSec;
            this.elapsedSec = elapsedSec;
            this.deltaToPrSec = deltaToPrSec;
        }
    }

    public static Map<String, Summary> summaries(List<StoredClimbAttempt> attempts) {
        Map<String, int[]> acc = new LinkedHashMap<>(); // climbId -> {pr, count, lastDate}
        // int[] can't hold long lastDate safely; track lastDate separately.
        Map<String, Long> lastDate = new LinkedHashMap<>();
        for (StoredClimbAttempt a : attempts) {
            int[] v = acc.get(a.climbId);
            if (v == null) {
                acc.put(a.climbId, new int[]{a.elapsedSec, 1});
                lastDate.put(a.climbId, a.dateEpochSec);
            } else {
                if (a.elapsedSec < v[0]) v[0] = a.elapsedSec;
                v[1]++;
                if (a.dateEpochSec > lastDate.get(a.climbId)) {
                    lastDate.put(a.climbId, a.dateEpochSec);
                }
            }
        }
        Map<String, Summary> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : acc.entrySet()) {
            out.put(e.getKey(), new Summary(
                    e.getKey(), e.getValue()[0], e.getValue()[1], lastDate.get(e.getKey())));
        }
        return out;
    }

    public static List<HistoryRow> historyFor(String climbId, List<StoredClimbAttempt> attempts) {
        List<StoredClimbAttempt> mine = new ArrayList<>();
        int pr = Integer.MAX_VALUE;
        for (StoredClimbAttempt a : attempts) {
            if (climbId.equals(a.climbId)) {
                mine.add(a);
                if (a.elapsedSec < pr) pr = a.elapsedSec;
            }
        }
        mine.sort(Comparator.comparingLong((StoredClimbAttempt a) -> a.dateEpochSec).reversed());
        List<HistoryRow> rows = new ArrayList<>(mine.size());
        for (StoredClimbAttempt a : mine) {
            rows.add(new HistoryRow(a.dateEpochSec, a.elapsedSec, a.elapsedSec - pr));
        }
        return rows;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.climb.LogbookCalculatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/LogbookCalculator.java android/app/src/test/java/nl/paree/climbpro/domain/climb/LogbookCalculatorTest.java
git commit -m "feat(climb): LogbookCalculator computes PR summaries and per-climb history"
```

---

## Task 6: Strava DTOs + API endpoints

Add the activity-list and streams endpoints and their DTOs. The streams endpoint returns an object keyed by stream type (`key_by_type=true`).

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivityDto.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaStreamsDto.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaStreamsDtoTest.java`

- [ ] **Step 1: Write the failing test** (verifies the streams JSON shape parses)

```java
package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class StravaStreamsDtoTest {

    @Test
    public void parsesLatlngAndTimeStreams() throws Exception {
        String json =
                "{\"latlng\":{\"data\":[[45.0,6.0],[45.001,6.001]]},"
              + "\"time\":{\"data\":[0,60]}}";

        StravaStreamsDto dto = new ObjectMapper().readValue(json, StravaStreamsDto.class);

        assertEquals(2, dto.latlng.data.size());
        assertEquals(45.0, dto.latlng.data.get(0).get(0), 1e-9);
        assertEquals(6.001, dto.latlng.data.get(1).get(1), 1e-9);
        assertEquals(Integer.valueOf(60), dto.time.data.get(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.data.strava.StravaStreamsDtoTest`
Expected: FAIL — `StravaStreamsDto` does not exist.

- [ ] **Step 3: Write minimal implementation**

`StravaActivityDto.java`:

```java
package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Partial mapping of a Strava /athlete/activities list item. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaActivityDto {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("type")
    public String type;          // e.g. "Ride"

    @JsonProperty("start_date")
    public String startDate;     // ISO-8601

    @JsonProperty("distance")
    public float distance;       // metres
}
```

`StravaStreamsDto.java`:

```java
package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mapping of GET activities/{id}/streams?keys=latlng,time&key_by_type=true.
 * Only the streams we use are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaStreamsDto {

    @JsonProperty("latlng")
    public LatLngStream latlng;

    @JsonProperty("time")
    public TimeStream time;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LatLngStream {
        @JsonProperty("data")
        public List<List<Double>> data; // [[lat,lon], ...]
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TimeStream {
        @JsonProperty("data")
        public List<Integer> data;      // seconds since activity start
    }
}
```

Modify `StravaApiClient.java` — add the two endpoints after `exportGpx`:

```java
    @GET("athlete/activities")
    Call<List<StravaActivityDto>> listActivities(
            @Header("Authorization") String bearerToken,
            @Query("after") long afterEpochSec,
            @Query("page") int page,
            @Query("per_page") int perPage);

    @GET("activities/{id}/streams")
    Call<StravaStreamsDto> getStreams(
            @Header("Authorization") String bearerToken,
            @Path("id") long activityId,
            @Query("keys") String keys,
            @Query("key_by_type") boolean keyByType);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.data.strava.StravaStreamsDtoTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivityDto.java android/app/src/main/java/nl/paree/climbpro/data/strava/StravaStreamsDto.java android/app/src/main/java/nl/paree/climbpro/data/strava/StravaApiClient.java android/app/src/test/java/nl/paree/climbpro/data/strava/StravaStreamsDtoTest.java
git commit -m "feat(strava): activity list + streams endpoints and DTOs"
```

---

## Task 7: StravaActivitiesRepository (fetch + match + persist)

Orchestrates the feature: pick the `after` window (12 months on first run, else last sync), page activities, skip known ones, fetch streams, match against every known climb, and append attempts. Mirrors `StravaRoutesRepository`'s test-injectable constructor.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivitiesRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaActivitiesRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

@RunWith(RobolectricTestRunner.class)
public class StravaActivitiesRepositoryTest {

    private Application app;
    private RouteRepository routeRepo;
    private ClimbAttemptRepository attemptRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api  = mock(StravaApiClient.class);

        seedRouteWithOneClimb();
    }

    /** A 1000 m climb from (45.000,6.0) to (45.009,6.0). */
    private void seedRouteWithOneClimb() throws Exception {
        StoredRoute route = new StoredRoute();
        route.routeId    = "r1";
        route.lats       = new double[]{45.000, 45.009};
        route.lons       = new double[]{6.0,    6.0};
        route.elevations = new double[]{100,    200};
        route.distances  = new double[]{0,      1000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.000; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        // Catalog with one entry so the repo can enumerate routes.
        nl.paree.climbpro.data.route.RouteCatalogEntry entry =
                new nl.paree.climbpro.data.route.RouteCatalogEntry();
        entry.routeId = "r1";
        new ObjectMapper().writeValue(
                new File(app.getFilesDir(), "catalog.json"),
                new nl.paree.climbpro.data.route.RouteCatalogEntry[]{entry});
    }

    @SuppressWarnings("unchecked")
    private void stubActivityWithFullClimbTrack() throws Exception {
        StravaActivityDto act = new StravaActivityDto();
        act.id = 555L; act.type = "Ride"; act.startDate = "2026-03-01T08:00:00Z";

        Call<List<StravaActivityDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(act)));
        Call<List<StravaActivityDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(new ArrayList<>()));
        when(api.listActivities(anyString(), anyLong(), eq(1), anyInt())).thenReturn(page1);
        when(api.listActivities(anyString(), anyLong(), eq(2), anyInt())).thenReturn(page2);

        StravaStreamsDto streams = new StravaStreamsDto();
        streams.latlng = new StravaStreamsDto.LatLngStream();
        streams.latlng.data = Arrays.asList(
                Arrays.asList(45.000, 6.0),
                Arrays.asList(45.0045, 6.0),
                Arrays.asList(45.009, 6.0));
        streams.time = new StravaStreamsDto.TimeStream();
        streams.time.data = Arrays.asList(0, 150, 300);

        Call<StravaStreamsDto> streamCall = mock(Call.class);
        when(streamCall.execute()).thenReturn(Response.success(streams));
        when(api.getStreams(anyString(), eq(555L), anyString(), anyBoolean()))
                .thenReturn(streamCall);
    }

    @Test
    public void sync_matchesActivityToClimb_persistsOneAttempt() throws Exception {
        stubActivityWithFullClimbTrack();
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(auth, routeRepo, attemptRepo, api);

        int created = repo.syncActivities();

        assertEquals(1, created);
        assertEquals(1, attemptRepo.loadAll().size());
        assertEquals(300, attemptRepo.loadAll().get(0).elapsedSec);
    }

    @Test
    public void sync_skipsAlreadyKnownActivities() throws Exception {
        stubActivityWithFullClimbTrack();
        StravaActivitiesRepository repo =
                new StravaActivitiesRepository(auth, routeRepo, attemptRepo, api);
        repo.syncActivities();           // first run persists the attempt

        stubActivityWithFullClimbTrack(); // same activity id 555
        int created = repo.syncActivities();

        assertEquals(0, created);
        assertEquals(1, attemptRepo.loadAll().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.data.strava.StravaActivitiesRepositoryTest`
Expected: FAIL — `StravaActivitiesRepository` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Fetches Strava activities (last 12 months on first run, incremental after),
 * matches each against every known climb, and persists matched attempts.
 * Call {@link #syncActivities()} from a background thread.
 */
public final class StravaActivitiesRepository {

    private static final String TAG       = "StravaActivitiesRepo";
    private static final String PREFS     = "strava_activities";
    private static final String PREF_LAST = "last_sync_epoch_sec";
    private static final long   ONE_YEAR_SEC = 365L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time";

    private final StravaAuthRepository    auth;
    private final RouteRepository         routeRepo;
    private final ClimbAttemptRepository  attemptRepo;
    private final StravaApiClient         api;
    private final android.content.SharedPreferences prefs;

    public StravaActivitiesRepository(Context context,
                                      StravaAuthRepository auth,
                                      RouteRepository routeRepo,
                                      ClimbAttemptRepository attemptRepo) {
        this(auth, routeRepo, attemptRepo,
                buildRetrofit().create(StravaApiClient.class), context);
    }

    /** Test-injectable variant (no prefs persistence dependency on a real Context is still needed). */
    StravaActivitiesRepository(StravaAuthRepository auth,
                               RouteRepository routeRepo,
                               ClimbAttemptRepository attemptRepo,
                               StravaApiClient api) {
        this(auth, routeRepo, attemptRepo, api,
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
    }

    private StravaActivitiesRepository(StravaAuthRepository auth,
                                       RouteRepository routeRepo,
                                       ClimbAttemptRepository attemptRepo,
                                       StravaApiClient api,
                                       Context context) {
        this.auth = auth;
        this.routeRepo = routeRepo;
        this.attemptRepo = attemptRepo;
        this.api = api;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return number of new attempts persisted. */
    public int syncActivities() throws IOException {
        String token = "Bearer " + auth.getAccessToken();

        long lastSync = prefs.getLong(PREF_LAST, 0L);
        long nowSec   = System.currentTimeMillis() / 1000L;
        long after    = lastSync > 0 ? lastSync : nowSec - ONE_YEAR_SEC;

        List<KnownClimb> climbs = enumerateKnownClimbs();
        if (climbs.isEmpty()) return 0;

        Set<Long> known = attemptRepo.knownActivityIds();

        List<StoredClimbAttempt> created = new ArrayList<>();
        int page = 1;
        while (true) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivities(token, after, page, 50).execute();
            if (!resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;

            for (StravaActivityDto act : resp.body()) {
                if (known.contains(act.id)) continue;
                created.addAll(matchActivity(token, act, climbs));
            }
            page++;
        }

        if (!created.isEmpty()) attemptRepo.append(created);
        prefs.edit().putLong(PREF_LAST, nowSec).apply();
        Log.i(TAG, "Activity sync: " + created.size() + " new attempt(s)");
        return created.size();
    }

    private List<StoredClimbAttempt> matchActivity(
            String token, StravaActivityDto act, List<KnownClimb> climbs) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        try {
            Response<StravaStreamsDto> sresp =
                    api.getStreams(token, act.id, STREAM_KEYS, true).execute();
            if (!sresp.isSuccessful() || sresp.body() == null) return out;
            StravaStreamsDto s = sresp.body();
            if (s.latlng == null || s.time == null
                    || s.latlng.data == null || s.time.data == null) return out;

            List<TrackSample> track = toTrack(s);
            if (track.size() < 2) return out;

            long dateSec = parseStartDate(act.startDate);
            for (KnownClimb k : climbs) {
                int elapsed = ClimbAttemptMatcher.match(
                        track, k.startLat, k.startLon, k.endLat, k.endLon, k.lengthM);
                if (elapsed > 0) {
                    StoredClimbAttempt a = new StoredClimbAttempt();
                    a.climbId      = k.climbId;
                    a.activityId   = act.id;
                    a.dateEpochSec = dateSec;
                    a.elapsedSec   = elapsed;
                    a.avgSpeedKmh  = (k.lengthM / (double) elapsed) * 3.6;
                    out.add(a);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Stream fetch failed for activity " + act.id, e);
        }
        return out;
    }

    private List<KnownClimb> enumerateKnownClimbs() {
        Map<String, KnownClimb> byId = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                for (KnownClimb k : KnownClimbs.fromRoute(route)) {
                    byId.put(k.climbId, k); // dedupe same climb across routes
                }
            } catch (IOException ignored) {}
        }
        return new ArrayList<>(byId.values());
    }

    private static List<TrackSample> toTrack(StravaStreamsDto s) {
        int n = Math.min(s.latlng.data.size(), s.time.data.size());
        List<TrackSample> track = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Double> ll = s.latlng.data.get(i);
            if (ll == null || ll.size() < 2) continue;
            track.add(new TrackSample(ll.get(0), ll.get(1), s.time.data.get(i)));
        }
        return track;
    }

    private static long parseStartDate(String iso) {
        if (iso == null) return 0L;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.parse(iso).getTime() / 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Retrofit buildRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logging).build();
        return new Retrofit.Builder()
                .baseUrl(StravaApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
    }
}
```

> Note: the test-injectable constructor pulls the Robolectric application context via `ApplicationProvider`. That keeps the unit test aligned with the existing `StravaRoutesRepository` test idiom while still letting prefs work. The production constructor takes a real `Context`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.data.strava.StravaActivitiesRepositoryTest`
Expected: PASS (both tests).

- [ ] **Step 5: Run the full domain/data suite to catch regressions**

Run: `./gradlew test --tests "nl.paree.climbpro.domain.*" --tests "nl.paree.climbpro.data.*"`
Expected: PASS (BUILD SUCCESSFUL).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaActivitiesRepository.java android/app/src/test/java/nl/paree/climbpro/data/strava/StravaActivitiesRepositoryTest.java
git commit -m "feat(strava): StravaActivitiesRepository matches rides to climbs"
```

---

## Task 8: Logbook screen (ViewModel + Activity + adapter + layout + entry point)

A screen listing every climb that has at least one attempt, with PR, attempt count, and last-ridden date, plus a "Ritten ophalen uit Strava" sync action. Tapping a row opens the existing climb detail. Because the logbook is keyed by `ClimbIdentity` (not route+index), each summary needs a route+index to open detail — we resolve that by scanning routes for the first climb whose identity matches.

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookViewModel.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/LogbookAdapter.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookActivity.java`
- Create: `android/app/src/main/res/layout/activity_climb_logbook.xml`
- Create: `android/app/src/main/res/layout/item_logbook_climb.xml`
- Modify: `android/app/src/main/res/menu/route_list_menu.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbLogbookViewModelTest.java`

- [ ] **Step 1: Write the failing test** (VM exposes sorted logbook rows; resolves a route+index per climb)

```java
package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbLogbookViewModelTest {

    @Test
    public void loadLogbook_buildsRowWithResolvedRouteAndIndex() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.elevations = new double[]{100, 200};
        route.distances = new double[]{0, 1000};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.0; c.startLon = 6.0;
        c.name = "Test Col";
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);
        RouteCatalogEntry entry = new RouteCatalogEntry();
        entry.routeId = "r1";
        new ObjectMapper().writeValue(new File(app.getFilesDir(), "catalog.json"),
                new RouteCatalogEntry[]{entry});

        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        a.activityId = 9; a.dateEpochSec = 1000; a.elapsedSec = 600;
        new ClimbAttemptRepository(app).append(Collections.singletonList(a));

        ClimbLogbookViewModel vm = new ClimbLogbookViewModel(app);
        final List<ClimbLogbookViewModel.LogbookRow>[] got = new List[]{null};
        vm.rows().observeForever(r -> got[0] = r);

        vm.loadLogbook();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(got[0]);
        assertEquals(1, got[0].size());
        assertEquals("r1", got[0].get(0).routeId);
        assertEquals(0, got[0].get(0).climbIndex);
        assertEquals(600, got[0].get(0).prSec);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbLogbookViewModelTest`
Expected: FAIL — `ClimbLogbookViewModel` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ClimbLogbookViewModel.java`:

```java
package nl.paree.climbpro.ui.climbs;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.LogbookCalculator.Summary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbLogbookViewModel extends AndroidViewModel {

    /** One logbook list row: a climb with a PR, resolved to a route+index for navigation. */
    public static final class LogbookRow {
        public final String climbId;
        public final String displayName;
        public final int    prSec;
        public final int    attemptCount;
        public final long   lastDateSec;
        public final String routeId;     // may be null if no longer resolvable
        public final int    climbIndex;

        LogbookRow(String climbId, String displayName, int prSec, int attemptCount,
                   long lastDateSec, String routeId, int climbIndex) {
            this.climbId = climbId;
            this.displayName = displayName;
            this.prSec = prSec;
            this.attemptCount = attemptCount;
            this.lastDateSec = lastDateSec;
            this.routeId = routeId;
            this.climbIndex = climbIndex;
        }
    }

    private final RouteRepository routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<LogbookRow>> rows = new MutableLiveData<>();

    public ClimbLogbookViewModel(@NonNull Application app) {
        super(app);
        routeRepo   = new RouteRepository(app);
        attemptRepo = new ClimbAttemptRepository(app);
    }

    public LiveData<List<LogbookRow>> rows() { return rows; }

    public void loadLogbook() {
        executor.execute(() -> {
            List<StoredClimbAttempt> attempts = attemptRepo.loadAll();
            Map<String, Summary> summaries = LogbookCalculator.summaries(attempts);
            Map<String, int[]> resolved = resolveClimbLocations(summaries.keySet());
            Map<String, String> names   = climbNames();

            List<LogbookRow> out = new ArrayList<>();
            for (Summary s : summaries.values()) {
                int[] loc = resolved.get(s.climbId); // {routeIndexUnused?} -> use routeId map below
                String routeId = locRouteId.get(s.climbId);
                int idx = loc != null ? loc[0] : -1;
                String name = names.getOrDefault(s.climbId, "Klim");
                out.add(new LogbookRow(s.climbId, name, s.prSec, s.attemptCount,
                        s.lastDateSec, routeId, idx));
            }
            out.sort(Comparator.comparingLong((LogbookRow r) -> r.lastDateSec).reversed());
            rows.postValue(out);
        });
    }

    // climbId -> routeId (parallel to resolveClimbLocations) populated during resolution.
    private final java.util.HashMap<String, String> locRouteId = new java.util.HashMap<>();

    /** Maps each wanted climbId to {climbIndex} in the first route that contains it. */
    private Map<String, int[]> resolveClimbLocations(java.util.Set<String> wanted) {
        java.util.HashMap<String, int[]> map = new java.util.HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (int i = 0; i < route.climbs.size(); i++) {
                    StoredClimb c = route.climbs.get(i);
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    if (wanted.contains(id) && !map.containsKey(id)) {
                        map.put(id, new int[]{i});
                        locRouteId.put(id, entry.routeId);
                    }
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private Map<String, String> climbNames() {
        java.util.HashMap<String, String> names = new java.util.HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                if (route.climbs == null) continue;
                for (StoredClimb c : route.climbs) {
                    int len = c.length > 0 ? c.length : (c.endDistance - c.startDistance);
                    String id = ClimbIdentity.of(c.startLat, c.startLon, len);
                    String display = c.userDisplayName != null ? c.userDisplayName
                            : (c.name != null ? c.name : "Klim");
                    names.putIfAbsent(id, display);
                }
            } catch (Exception ignored) {}
        }
        return names;
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
```

- [ ] **Step 4: Run the VM test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbLogbookViewModelTest`
Expected: PASS.

- [ ] **Step 5: Create the layouts**

`activity_climb_logbook.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <androidx.appcompat.widget.Toolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        android:title="Logboek" />

    <TextView
        android:id="@+id/empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:visibility="gone"
        android:text="Nog geen klimpogingen. Haal je ritten op uit Strava." />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <Button
        android:id="@+id/syncButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="12dp"
        android:text="Ritten ophalen uit Strava" />
</LinearLayout>
```

`item_logbook_climb.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp"
    android:background="?android:attr/selectableItemBackground">

    <TextView
        android:id="@+id/name"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textStyle="bold"
        android:textSize="16sp" />

    <TextView
        android:id="@+id/stats"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="13sp" />
</LinearLayout>
```

- [ ] **Step 6: Create the adapter**

`LogbookAdapter.java`:

```java
package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.ClimbLogbookViewModel.LogbookRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LogbookAdapter extends RecyclerView.Adapter<LogbookAdapter.VH> {

    public interface OnClick { void onClimb(LogbookRow row); }

    private final List<LogbookRow> items = new ArrayList<>();
    private final OnClick onClick;

    public LogbookAdapter(OnClick onClick) { this.onClick = onClick; }

    public void submit(List<LogbookRow> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_logbook_climb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LogbookRow row = items.get(position);
        h.name.setText(row.displayName);
        h.stats.setText(String.format(Locale.getDefault(),
                "PR %s  •  %d pogingen", formatTime(row.prSec), row.attemptCount));
        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClimb(row);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static String formatTime(int sec) {
        int m = sec / 60, s = sec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView stats;
        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            stats = v.findViewById(R.id.stats);
        }
    }
}
```

- [ ] **Step 7: Create the Activity**

`ClimbLogbookActivity.java`:

```java
package nl.paree.climbpro.ui.climbs;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClimbLogbookActivity extends AppCompatActivity {

    private ClimbLogbookViewModel viewModel;
    private LogbookAdapter adapter;
    private TextView empty;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climb_logbook);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogbookAdapter(this::openClimb);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(ClimbLogbookViewModel.class);
        viewModel.rows().observe(this, rows -> {
            adapter.submit(rows);
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        });

        Button sync = findViewById(R.id.syncButton);
        sync.setOnClickListener(v -> syncFromStrava());

        viewModel.loadLogbook();
    }

    private void openClimb(ClimbLogbookViewModel.LogbookRow row) {
        if (row.routeId == null || row.climbIndex < 0) {
            Toast.makeText(this, "Klim niet meer in een route gevonden", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(ClimbDetailActivity.intentFor(this, row.routeId, row.climbIndex));
    }

    private void syncFromStrava() {
        Toast.makeText(this, "Ritten ophalen…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                StravaActivitiesRepository repo = new StravaActivitiesRepository(
                        getApplicationContext(),
                        new StravaAuthRepository(this),
                        new RouteRepository(this),
                        new ClimbAttemptRepository(this));
                int created = repo.syncActivities();
                runOnUiThread(() -> {
                    Toast.makeText(this, created + " nieuwe poging(en)", Toast.LENGTH_SHORT).show();
                    viewModel.loadLogbook();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Ophalen mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}
```

> Check `ClimbDetailActivity.intentFor` signature before building. If it is `intentFor(Context, String routeId, int climbIndex)`, the call above is correct. If the parameter order differs, match it exactly.

- [ ] **Step 8: Wire the entry point**

In `android/app/src/main/res/menu/route_list_menu.xml`, add before `action_settings`:

```xml
    <item
        android:id="@+id/action_logbook"
        android:title="Logboek"
        app:showAsAction="never"/>
```

In `RouteListActivity.onOptionsItemSelected`, add a branch (alongside the existing ones):

```java
        } else if (id == R.id.action_logbook) {
            startActivity(new Intent(this,
                    nl.paree.climbpro.ui.climbs.ClimbLogbookActivity.class));
            return true;
```

In `AndroidManifest.xml`, register the activity inside `<application>`:

```xml
        <activity
            android:name=".ui.climbs.ClimbLogbookActivity"
            android:exported="false" />
```

- [ ] **Step 9: Build to verify UI compiles and the VM test still passes**

Run: `./gradlew :app:assembleDebug --tests nl.paree.climbpro.ui.climbs.ClimbLogbookViewModelTest`
If `assembleDebug` does not accept `--tests`, run them separately:
Run: `./gradlew :app:assembleDebug` then `./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbLogbookViewModelTest`
Expected: BUILD SUCCESSFUL and PASS.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookViewModel.java android/app/src/main/java/nl/paree/climbpro/ui/climbs/LogbookAdapter.java android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbLogbookActivity.java android/app/src/main/res/layout/activity_climb_logbook.xml android/app/src/main/res/layout/item_logbook_climb.xml android/app/src/main/res/menu/route_list_menu.xml android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java android/app/src/main/AndroidManifest.xml android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbLogbookViewModelTest.java
git commit -m "feat(ui): climb logbook screen with Strava sync action"
```

---

## Task 9: Climb-detail history block

Adds a history block to the existing climb-detail screen: PR plus a newest-first list of attempts with delta-to-PR. The ViewModel exposes `LogbookCalculator.historyFor(...)` for the open climb; the Activity renders it into a container.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`
- Modify: the climb-detail layout (find via `ClimbDetailActivity`'s `setContentView`/binding; likely `activity_climb_detail.xml`)
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailHistoryTest.java`

- [ ] **Step 1: Write the failing test** (VM exposes history for the open climb)

```java
package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ClimbDetailHistoryTest {

    @Test
    public void loadClimb_exposesHistoryNewestFirst() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.lats = new double[]{45.0, 45.009};
        route.lons = new double[]{6.0, 6.0};
        route.distances = new double[]{0, 1000};
        route.elevations = new double[]{100, 200};
        StoredClimb c = new StoredClimb();
        c.startDistance = 0; c.endDistance = 1000; c.length = 1000;
        c.startLat = 45.0; c.startLon = 6.0;
        c.segments = Collections.emptyList();
        route.climbs = Collections.singletonList(c);

        File dir = new File(app.getFilesDir(), "routes");
        dir.mkdirs();
        new ObjectMapper().writeValue(new File(dir, "r1.json"), route);

        String climbId = ClimbIdentity.of(45.0, 6.0, 1000);
        StoredClimbAttempt a1 = mk(climbId, 1, 1000, 700);
        StoredClimbAttempt a2 = mk(climbId, 2, 2000, 650);
        new ClimbAttemptRepository(app).append(Arrays.asList(a1, a2));

        ClimbDetailViewModel vm = new ClimbDetailViewModel(app);
        final List<HistoryRow>[] got = new List[]{null};
        vm.history().observeForever(h -> got[0] = h);

        vm.loadClimb("r1", 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(got[0]);
        assertEquals(2, got[0].size());
        assertEquals(2000L, got[0].get(0).dateEpochSec); // newest first
        assertEquals(0, got[0].get(0).deltaToPrSec);      // 650 is PR
    }

    private static StoredClimbAttempt mk(String id, long act, long date, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = id; a.activityId = act; a.dateEpochSec = date; a.elapsedSec = elapsed;
        return a;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbDetailHistoryTest`
Expected: FAIL — `ClimbDetailViewModel.history()` does not exist.

- [ ] **Step 3: Extend the ViewModel**

In `ClimbDetailViewModel.java`, add imports:

```java
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.LogbookCalculator;
import nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow;
```

Add a field next to the other repositories:

```java
    private final ClimbAttemptRepository attemptRepo;
```

Initialise it in the constructor (after `riderRepo = new RiderProfileRepository(app);`):

```java
        attemptRepo = new ClimbAttemptRepository(app);
```

Add the LiveData next to the others:

```java
    private final MutableLiveData<List<HistoryRow>> history = new MutableLiveData<>();
```

Add the accessor next to the others:

```java
    public LiveData<List<HistoryRow>> history() { return history; }
```

Inside `loadClimb`, after `computeEstimate(loaded);`, add:

```java
                    int len = loaded.length > 0
                            ? loaded.length : (loaded.endDistance - loaded.startDistance);
                    String climbId = ClimbIdentity.of(loaded.startLat, loaded.startLon, len);
                    history.postValue(
                            LogbookCalculator.historyFor(climbId, attemptRepo.loadAll()));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.ui.climbs.ClimbDetailHistoryTest`
Expected: PASS.

- [ ] **Step 5: Render the history in the layout**

`ClimbDetailActivity` uses view binding (`ActivityClimbDetailBinding`), so the layout
is `android/app/src/main/res/layout/activity_climb_detail.xml`. Add, near the bottom
of the main vertical container, a header and a container:

```xml
    <TextView
        android:id="@+id/historyHeader"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:paddingTop="16dp"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:textStyle="bold"
        android:text="Historie"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/historyContainer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="16dp"
        android:paddingEnd="16dp" />
```

- [ ] **Step 6: Bind the history in the Activity**

In `ClimbDetailActivity`, add an observer in `onCreate` (after the existing `viewModel` observers), using the existing `binding` field for view access:

```java
        viewModel.history().observe(this, rows -> {
            android.widget.TextView header = binding.historyHeader;
            android.widget.LinearLayout container = binding.historyContainer;
            container.removeAllViews();
            if (rows == null || rows.isEmpty()) {
                header.setVisibility(android.view.View.GONE);
                return;
            }
            header.setVisibility(android.view.View.VISIBLE);
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
            for (nl.paree.climbpro.domain.climb.LogbookCalculator.HistoryRow row : rows) {
                android.widget.TextView tv = new android.widget.TextView(this);
                int m = row.elapsedSec / 60, s = row.elapsedSec % 60;
                String date = fmt.format(new java.util.Date(row.dateEpochSec * 1000L));
                String delta = row.deltaToPrSec == 0
                        ? "PR" : "+" + (row.deltaToPrSec / 60) + ":"
                        + String.format(java.util.Locale.getDefault(), "%02d", row.deltaToPrSec % 60);
                tv.setText(String.format(java.util.Locale.getDefault(),
                        "%s   %d:%02d   (%s)", date, m, s, delta));
                tv.setPadding(0, 8, 0, 8);
                container.addView(tv);
            }
        });
```

- [ ] **Step 7: Build to verify the screen compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java android/app/src/main/res/layout/activity_climb_detail.xml android/app/src/test/java/nl/paree/climbpro/ui/climbs/ClimbDetailHistoryTest.java
git commit -m "feat(ui): climb-detail history block with PR and attempt deltas"
```

---

## Task 10: Documentation

Per the project convention, document the new feature where architecture lives.

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

- [ ] **Step 1: Update ARCHITECTURE.md**

Add a section "Climb Logbook (phone-only)" describing: the data source (Strava activities, last 12 months on first sync then incremental), the matching approach (`ClimbAttemptMatcher` entry/exit proximity + length validation), the route-independent `ClimbIdentity`, persistence in `climb_attempts.json`, and an explicit note that this feature does **not** touch the watch, the sync payload, or `protocol/schema.json`.

- [ ] **Step 2: Update README.md**

Add the Climb Logbook to the user-facing feature list: per-climb history and PRs derived from Strava rides, reachable from the route list overflow menu ("Logboek") and from each climb's detail screen.

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: document the phone-only climb logbook feature"
```

---

## Self-Review Notes

- **Spec coverage:** dataflow (Tasks 6–7), matching (Task 3), climb identity (Task 1–2), persistence (Task 4), 12-month first sync (Task 7 `ONE_YEAR_SEC` window), UI klim-detail history block (Task 9) and Logbook screen (Task 8), manual sync button (Task 8), edge cases — partial/crossing rejected (Task 3 length validation), missing time stream (Task 7 null guards), dedupe on activity id (Tasks 4 & 7), climb rename/delete survives via identity (Tasks 1–2, 8), rate limit via incremental + 12-month bound (Task 7). Testing matches spec. No watch/protocol changes anywhere. ✓
- **Verification dependency:** Tasks 8 and 9 assume `ClimbDetailActivity.intentFor(Context, String, int)` and a vertical-container detail layout. Both steps instruct the implementer to confirm the exact signature/layout before wiring — do this rather than assuming.
- **Type consistency:** `ClimbIdentity.of(double,double,int)`, `KnownClimb` fields, `ClimbAttemptMatcher.match(...)`/`TrackSample`, `StoredClimbAttempt` fields, `LogbookCalculator.Summary`/`HistoryRow`, and `ClimbAttemptRepository.loadAll/append/knownActivityIds` are used consistently across Tasks 1–9.
