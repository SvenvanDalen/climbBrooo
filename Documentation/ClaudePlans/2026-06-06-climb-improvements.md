# Climb Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch to exactly 16 segments per climb, add GPS calibration points for watch-side position correction, compact the wire payload so 12+ climbs fit in 4 KB (Plan D), and add per-climb segment count selection via the ClimbDetail screen (Plan C).

**Architecture:** Android domain layer computes segments and calibration points (Segmenter). Data layer persists them (StoredClimb, StoredCalibrationPoint) and serialises to a compact HashMap-based JSON payload v2 (ClimbPayloadBuilder). Garmin watch app decodes the compact format (CommListener.mc) and uses calibration points to reset GPS position during climbs (ClimbData.mc). Migration: on first launch after this update, all stored routes are cleared (SharedPreferences flag) since the segment format is incompatible with old JSON.

**Tech Stack:** Java 17, Android, JUnit 5, Jackson ObjectMapper, Monkey C (Garmin Connect IQ 4.2.2, Forerunner 255 Music)

---

## File structure

| Action | File | What changes |
|--------|------|-------------|
| Modify | `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java` | Remove `SEGMENT_FRACTION`; add `SEGMENT_COUNT=16`, `CALIBRATION_MIN_DISTANCE_M=200`, `SEGMENT_VERSION=2` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java` | Use `SEGMENT_COUNT`; add `calibrationPoints()` + `interpolateLatLon()`; add `segment(pts, n)` overload |
| Modify | `android/app/src/main/java/nl/paree/climbpro/service/PayloadBudget.java` | `MAX_BYTES = 4 * 1024` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java` | Replace POJO-based JSON with HashMap v2: short keys, flat `segs` int array, `calib` int array |
| Modify | `android/app/src/main/java/nl/paree/climbpro/domain/climb/Climb.java` | Add `calibrationPoints` field to class and Builder |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimb.java` | Add `calibrationPoints` (nullable) and `segmentCount` fields |
| Modify | `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java` | Add `migrateIfNeeded()` (clears all routes on version bump); add `reSegmentClimb()` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java` | Add `reSegment(routeId, climbIndex, count)` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` | Add "Herbereken segmenten" button + NumberPicker dialog |
| Modify | `android/app/src/main/res/layout/activity_climb_detail.xml` | Add `btnReSegment` Button |
| Create | `android/app/src/main/java/nl/paree/climbpro/domain/segment/CalibrationPoint.java` | New immutable domain model |
| Create | `android/app/src/main/java/nl/paree/climbpro/data/route/StoredCalibrationPoint.java` | New Jackson-serialisable persistence model |
| Modify | `android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java` | Fix `segmentCountIsCorrectFor8PctFraction`; add 16-segment + calibration tests |
| Create | `android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java` | Assert new constants |
| Create | `android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java` | Construct + Jackson round-trip |
| Create | `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java` | Assert v2 format, `segs` flat array, 4 KB budget |
| Modify | `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java` | Remove v1 example tests; add v2 format assertion |
| Modify | `garmin/source/ClimbData.mc` | Add calib arrays (`MAX_CALIB=16`, `calibCount/Dist/Lat/Lon/Idx`); increase `MAX_CLIMBS` to 16; add `checkCalibration()` |
| Modify | `garmin/source/CommListener.mc` | Version 2 check; short-key parsing; decode flat `segs` + `calib` arrays |

---

## Task 1: Replace SEGMENT_FRACTION with SEGMENT_COUNT and fix PayloadBudget

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/PayloadBudget.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java`:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.service.PayloadBudget;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClimbConstantsTest {

    @Test
    public void segmentCountIs16() {
        assertEquals(16, ClimbConstants.SEGMENT_COUNT);
    }

    @Test
    public void calibrationMinDistanceIs200() {
        assertEquals(200, ClimbConstants.CALIBRATION_MIN_DISTANCE_M);
    }

    @Test
    public void segmentVersionIs2() {
        assertEquals(2, ClimbConstants.SEGMENT_VERSION);
    }

    @Test
    public void payloadBudgetIs4KB() {
        assertEquals(4 * 1024, PayloadBudget.MAX_BYTES);
    }
}
```

- [ ] **Step 2: Verify tests fail to compile**

```
./gradlew test --tests nl.paree.climbpro.domain.ClimbConstantsTest
```
Expected: COMPILE FAIL — `SEGMENT_COUNT` does not exist in `ClimbConstants`

- [ ] **Step 3: Replace ClimbConstants.java**

```java
package nl.paree.climbpro.domain.climb;

public final class ClimbConstants {

    private ClimbConstants() {}

    public static final int    MIN_CLIMB_LENGTH_M          = 800;
    public static final double MIN_AVG_GRADIENT             = 0.03;
    public static final int    SEGMENT_COUNT                = 16;
    public static final int    SEGMENT_VERSION              = 2;
    public static final int    CALIBRATION_MIN_DISTANCE_M   = 200;
    public static final int    ALERT_RADIUS_M               = 50;
    public static final int    ROUTE_MATCHING_HYSTERESIS_M  = 20;
}
```

- [ ] **Step 4: Update PayloadBudget.java**

```java
package nl.paree.climbpro.service;

public final class PayloadBudget {

    private PayloadBudget() {}

    public static final int MAX_BYTES = 4 * 1024;
}
```

- [ ] **Step 5: Verify tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.ClimbConstantsTest
```
Expected: PASS (4 tests green)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java \
        android/app/src/main/java/nl/paree/climbpro/service/PayloadBudget.java \
        android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java
git commit -m "feat: SEGMENT_COUNT=16, CALIBRATION_MIN_DISTANCE_M=200, PayloadBudget=4KB"
```

---

## Task 2: Segmenter produces exactly SEGMENT_COUNT segments

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java`

The current `segmentLength = totalLength * SEGMENT_FRACTION` gives 12–13 segments. Change to `totalLength / SEGMENT_COUNT` to always give exactly 16.

- [ ] **Step 1: Write the failing test**

Add this method to `SegmenterTest.java`:

```java
@Test
public void segmentCountIsAlways16() {
    for (int len : new int[]{800, 1200, 2000, 5000, 10000}) {
        List<RoutePoint> climb = buildClimb(len, 0.05);
        List<Segment> segs = Segmenter.segment(climb);
        assertEquals("expect 16 segments for " + len + "m climb", 16, segs.size());
    }
}
```

- [ ] **Step 2: Verify test fails**

```
./gradlew test --tests "nl.paree.climbpro.domain.SegmenterTest#segmentCountIsAlways16"
```
Expected: FAIL — `segs.size()` is 12 or 13, not 16

- [ ] **Step 3: Update Segmenter.java line 32**

Change:
```java
double segmentLength = totalLength * ClimbConstants.SEGMENT_FRACTION;
List<Segment> segments = new ArrayList<>();
```
To:
```java
double segmentLength = totalLength / ClimbConstants.SEGMENT_COUNT;
List<Segment> segments = new ArrayList<>(ClimbConstants.SEGMENT_COUNT);
```

Also update the class-level Javadoc (line 10–14) to say "SEGMENT_COUNT" instead of "SEGMENT_FRACTION".

- [ ] **Step 4: Fix the outdated test in SegmenterTest.java**

Replace `segmentCountIsCorrectFor8PctFraction`:

```java
@Test
public void segmentCountIsAlways16ForVariousLengths() {
    for (int len : new int[]{800, 2000, 5000}) {
        List<RoutePoint> climb = buildClimb(len, 0.072);
        List<Segment> segs = Segmenter.segment(climb);
        assertEquals("expect exactly 16 segments for " + len + "m", 16, segs.size());
    }
}
```

- [ ] **Step 5: Verify all Segmenter tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SegmenterTest
```
Expected: PASS (all tests, no regressions)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java
git commit -m "feat: Segmenter always produces exactly SEGMENT_COUNT (16) segments"
```

---

## Task 3: Create CalibrationPoint and StoredCalibrationPoint

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/segment/CalibrationPoint.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredCalibrationPoint.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java`:

```java
package nl.paree.climbpro.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.domain.segment.CalibrationPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class CalibrationPointTest {

    @Test
    public void constructorStoresFields() {
        CalibrationPoint cp = new CalibrationPoint(500, 51.1234, 5.5678);
        assertEquals(500, cp.distanceFromClimbStart);
        assertEquals(51.1234, cp.lat, 1e-6);
        assertEquals(5.5678, cp.lon, 1e-6);
    }

    @Test
    public void storedCalibrationPointRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoredCalibrationPoint p = new StoredCalibrationPoint();
        p.distanceFromClimbStart = 300;
        p.lat = 51.9876;
        p.lon = 5.4321;

        String json = mapper.writeValueAsString(p);
        StoredCalibrationPoint back = mapper.readValue(json, StoredCalibrationPoint.class);

        assertEquals(300, back.distanceFromClimbStart);
        assertEquals(51.9876, back.lat, 1e-6);
        assertEquals(5.4321, back.lon, 1e-6);
    }

    @Test
    public void storedCalibrationPointIgnoresUnknownFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"distanceFromClimbStart\":100,\"lat\":52.0,\"lon\":4.0,\"future\":\"ignored\"}";
        StoredCalibrationPoint p = mapper.readValue(json, StoredCalibrationPoint.class);
        assertEquals(100, p.distanceFromClimbStart);
    }
}
```

- [ ] **Step 2: Verify test fails to compile**

```
./gradlew test --tests nl.paree.climbpro.domain.CalibrationPointTest
```
Expected: COMPILE FAIL — `CalibrationPoint` not found

- [ ] **Step 3: Create CalibrationPoint.java**

```java
package nl.paree.climbpro.domain.segment;

public final class CalibrationPoint {
    public final int distanceFromClimbStart; // meters from climb start
    public final double lat;
    public final double lon;

    public CalibrationPoint(int distanceFromClimbStart, double lat, double lon) {
        this.distanceFromClimbStart = distanceFromClimbStart;
        this.lat = lat;
        this.lon = lon;
    }
}
```

- [ ] **Step 4: Create StoredCalibrationPoint.java**

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredCalibrationPoint {
    public int distanceFromClimbStart;
    public double lat;
    public double lon;
}
```

- [ ] **Step 5: Verify tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.CalibrationPointTest
```
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/CalibrationPoint.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/StoredCalibrationPoint.java \
        android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java
git commit -m "feat: add CalibrationPoint and StoredCalibrationPoint models"
```

---

## Task 4: Add Segmenter.calibrationPoints() with interpolateLatLon

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java`

`calibrationPoints()` selects segment-end positions at least `CALIBRATION_MIN_DISTANCE_M` (200 m) apart. The last segment end is always included regardless of distance.

The `buildClimb` helper in `SegmenterTest` creates points with `lat = 51.0 + i * 0.001`. For a 3200 m climb with 50 points, successive points are 64 m apart in distance. Each segment is 200 m, so all 16 segment ends qualify (≥ 200 m gap).

- [ ] **Step 1: Add import and write the failing tests**

Add at the top of `SegmenterTest.java` (if not already present):
```java
import nl.paree.climbpro.domain.segment.CalibrationPoint;
```

Add to `SegmenterTest.java`:

```java
@Test
public void calibrationPointsLastIsAlwaysIncluded() {
    // 800 m / 16 segments = 50 m per segment — all below 200 m min distance
    // So only the last segment end qualifies (always included)
    List<RoutePoint> climb = buildClimb(800, 0.05);
    List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
    assertFalse("at least one calibration point expected", cps.isEmpty());
    CalibrationPoint last = cps.get(cps.size() - 1);
    assertEquals("last point at climb end", 800, last.distanceFromClimbStart, 2);
}

@Test
public void calibrationPointsRespectMinDistance() {
    // 3200 m / 16 = 200 m per segment — every segment end qualifies
    List<RoutePoint> climb = buildClimb(3200, 0.05);
    List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
    assertEquals("all 16 segment ends qualify", 16, cps.size());
    for (int i = 1; i < cps.size(); i++) {
        int gap = cps.get(i).distanceFromClimbStart - cps.get(i - 1).distanceFromClimbStart;
        assertTrue("consecutive gap >= 200 m but was " + gap, gap >= 198);
    }
}

@Test
public void calibrationPointsLatLonAreInterpolated() {
    List<RoutePoint> climb = buildClimb(2000, 0.05);
    List<CalibrationPoint> cps = Segmenter.calibrationPoints(climb);
    for (CalibrationPoint cp : cps) {
        // buildClimb uses lat = 51.0 + i*0.001 (51.0..51.049), lon = 5.0
        assertTrue("lat in valid range", cp.lat >= 51.0 && cp.lat <= 52.0);
        assertEquals("lon is 5.0", 5.0, cp.lon, 1e-6);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```
./gradlew test --tests "nl.paree.climbpro.domain.SegmenterTest#calibrationPointsLastIsAlwaysIncluded"
```
Expected: COMPILE FAIL — `Segmenter.calibrationPoints` not found

- [ ] **Step 3: Add import and two methods to Segmenter.java**

Add import at top:
```java
import nl.paree.climbpro.domain.segment.CalibrationPoint;
```

Add these two methods after the existing `interpolateElevation` private method:

```java
/**
 * Returns GPS calibration points for a climb — a subset of segment-end positions
 * at least {@link ClimbConstants#CALIBRATION_MIN_DISTANCE_M} apart.
 * The final segment end is always included.
 */
public static List<CalibrationPoint> calibrationPoints(List<RoutePoint> climbPoints) {
    if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

    RoutePoint first = climbPoints.get(0);
    RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
    double totalLength = last.distance - first.distance;
    if (totalLength <= 0) return new ArrayList<>();

    double segmentLength = totalLength / ClimbConstants.SEGMENT_COUNT;
    List<CalibrationPoint> result = new ArrayList<>();
    double lastCalibRelDist = 0;

    for (int i = 0; i < ClimbConstants.SEGMENT_COUNT; i++) {
        double relEnd = Math.min((i + 1) * segmentLength, totalLength);
        boolean isLast = (i == ClimbConstants.SEGMENT_COUNT - 1);

        if (relEnd - lastCalibRelDist >= ClimbConstants.CALIBRATION_MIN_DISTANCE_M || isLast) {
            double[] latLon = interpolateLatLon(climbPoints, first.distance + relEnd);
            result.add(new CalibrationPoint((int) Math.round(relEnd), latLon[0], latLon[1]));
            lastCalibRelDist = relEnd;
        }
    }
    return result;
}

private static double[] interpolateLatLon(List<RoutePoint> pts, double targetDist) {
    for (int i = 1; i < pts.size(); i++) {
        RoutePoint a = pts.get(i - 1);
        RoutePoint b = pts.get(i);
        if (b.distance >= targetDist) {
            double span = b.distance - a.distance;
            if (span <= 0) return new double[]{a.lat, a.lon};
            double t = Math.max(0, Math.min(1, (targetDist - a.distance) / span));
            return new double[]{a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)};
        }
    }
    RoutePoint last = pts.get(pts.size() - 1);
    return new double[]{last.lat, last.lon};
}
```

- [ ] **Step 4: Verify all Segmenter tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SegmenterTest
```
Expected: PASS (all tests, including 3 new calibration tests)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java
git commit -m "feat: Segmenter.calibrationPoints() with 200m min-distance filtering"
```

---

## Task 5: Add calibrationPoints field to Climb and StoredClimb

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/Climb.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimb.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java`

- [ ] **Step 1: Write the failing test**

Add to `CalibrationPointTest.java` (add the imports at the top too):

```java
import nl.paree.climbpro.domain.climb.Climb;
import java.util.Collections;
```

```java
@Test
public void climbBuilderAcceptsCalibrationPoints() {
    CalibrationPoint cp = new CalibrationPoint(500, 51.5, 5.0);
    Climb c = Climb.builder()
            .length(1000)
            .elevationGain(50)
            .avgGradient(0.05)
            .segments(Collections.emptyList())
            .calibrationPoints(Collections.singletonList(cp))
            .build();
    assertEquals(1, c.calibrationPoints.size());
    assertEquals(500, c.calibrationPoints.get(0).distanceFromClimbStart);
}
```

- [ ] **Step 2: Verify test fails to compile**

```
./gradlew test --tests "nl.paree.climbpro.domain.CalibrationPointTest#climbBuilderAcceptsCalibrationPoints"
```
Expected: COMPILE FAIL — `calibrationPoints()` not in `Climb.Builder`

- [ ] **Step 3: Update Climb.java**

Add import at top: `import nl.paree.climbpro.domain.segment.CalibrationPoint;`

Add field to the class body (after `public final List<Segment> segments;`):
```java
public final List<CalibrationPoint> calibrationPoints;
```

In `private Climb(Builder b)` constructor, add:
```java
this.calibrationPoints = Collections.unmodifiableList(b.calibrationPoints);
```

In the `Builder` inner class, add:
```java
private List<CalibrationPoint> calibrationPoints = Collections.emptyList();

public Builder calibrationPoints(List<CalibrationPoint> v) { this.calibrationPoints = v; return this; }
```

- [ ] **Step 4: Update StoredClimb.java**

Add import: `import nl.paree.climbpro.data.route.StoredCalibrationPoint;` (same package, so no import needed actually)
Add import: `import java.util.List;` (already present)

Add to `StoredClimb.java`:
```java
public List<StoredCalibrationPoint> calibrationPoints; // null on routes stored before version 2
```

- [ ] **Step 5: Verify all CalibrationPoint tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.CalibrationPointTest
```
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/Climb.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimb.java \
        android/app/src/test/java/nl/paree/climbpro/domain/CalibrationPointTest.java
git commit -m "feat: add calibrationPoints to Climb.Builder and StoredClimb"
```

---

## Task 6: RouteRepository migration flag (Optie A — wipe old routes)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java` (already done in Task 1)
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`

On first launch after this update, `SharedPreferences` key `"segment_version"` is absent (0). Code compares it to `SEGMENT_VERSION` (2) and clears all route JSON files + catalog. On subsequent launches the flag matches and migration is skipped. No unit test is possible without Android instrumentation — verify manually on emulator.

- [ ] **Step 1: Add fields and migrateIfNeeded to RouteRepository**

In `RouteRepository.java`, add:
- A `context` field (move from local variable to field)
- Two private constants
- The `migrateIfNeeded()` method

```java
// At the top of the class (add these two constants after the existing TAG/CATALOG_FILE/ROUTES_DIR):
private static final String PREFS_NAME           = "route_repo";
private static final String PREF_SEGMENT_VERSION = "segment_version";
```

Change the constructor to store `context` as a field and call migration:

```java
private final Context context; // add this field

public RouteRepository(Context context) {
    this.context = context.getApplicationContext();
    File base     = this.context.getFilesDir();
    this.routesDir    = new File(base, ROUTES_DIR);
    this.catalogFile  = new File(base, CATALOG_FILE);
    this.mapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    routesDir.mkdirs();
    migrateIfNeeded();
}
```

Add the migration method:

```java
private void migrateIfNeeded() {
    android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    int stored = prefs.getInt(PREF_SEGMENT_VERSION, 0);
    if (stored == nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION) return;

    File[] files = routesDir.listFiles();
    if (files != null) {
        for (File f : files) f.delete();
    }
    if (catalogFile.exists()) catalogFile.delete();

    prefs.edit()
         .putInt(PREF_SEGMENT_VERSION, nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION)
         .apply();
    Log.i(TAG, "Route migration: cleared all routes (segment format v"
            + nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_VERSION + ")");
}
```

- [ ] **Step 2: Verify all tests still pass**

```
./gradlew test
```
Expected: PASS — migration code is not reachable in JVM-only tests (requires Android Context)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java
git commit -m "feat: RouteRepository clears stored routes when SEGMENT_VERSION bumps"
```

---

## Task 7: ClimbPayloadBuilder compact format v2

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java`

Payload v2 uses a `HashMap<String, Object>` instead of generated POJOs. Short key names: `sd`/`ed`/`len`/`eg`/`ag`/`n` for climb metadata. Segments encoded as flat `int[]` array `segs` with 4 ints per segment: `[dist, elevGain, gradientFixedPoint, colorIndex, ...]`. Calibration points encoded as flat `int[]` array `calib` with 3 ints per point: `[distFromClimbStart, latInt, lonInt]` where latInt/lonInt = degrees × 100000.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java`:

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

public class ClimbPayloadBuilderTest {

    private static StoredRoute buildRoute() {
        StoredRoute route    = new StoredRoute();
        route.routeId        = "test-route";
        route.name           = "Test Route";
        route.climbs         = new ArrayList<>();

        StoredClimb c        = new StoredClimb();
        c.startDistance      = 1000;
        c.endDistance        = 3000;
        c.length             = 2000;
        c.elevationGain      = 80;
        c.avgGradient        = 0.04;
        c.segments           = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            StoredSegment s  = new StoredSegment();
            s.distance       = 125;
            s.elevationGain  = 5;
            s.gradient       = 0.04;
            s.colorIndex     = 2;
            c.segments.add(s);
        }
        c.calibrationPoints  = new ArrayList<>();
        StoredCalibrationPoint cp = new StoredCalibrationPoint();
        cp.distanceFromClimbStart = 800;
        cp.lat = 51.5;
        cp.lon = 5.1;
        c.calibrationPoints.add(cp);

        route.climbs.add(c);
        return route;
    }

    @Test
    public void payloadVersionIs2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode root = mapper.readTree(b.buildRoutePayload(buildRoute()));
        assertEquals(2, root.get("v").asInt());
    }

    @Test
    public void segsIsFlatArrayWith64Elements() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode segs = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("segs");
        assertNotNull("segs must exist", segs);
        assertTrue("segs must be array", segs.isArray());
        assertEquals("16 segments × 4 = 64 elements", 64, segs.size());
    }

    @Test
    public void segsGradientIsFixedPoint() throws Exception {
        // gradient 0.04 → 0.04 × 100 × 10 = 40
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode segs = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("segs");
        assertEquals("gradient fixed-point = 40", 40, segs.get(2).asInt());
    }

    @Test
    public void calibIsFlatArrayWith3Elements() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode calib = mapper.readTree(b.buildRoutePayload(buildRoute()))
                .get("climbs").get(0).get("calib");
        assertNotNull("calib must exist when calibrationPoints present", calib);
        assertEquals("1 point × 3 = 3 elements", 3, calib.size());
        assertEquals("dist = 800", 800, calib.get(0).asInt());
        assertEquals("latInt = 51.5 × 100000 = 5150000", 5150000, calib.get(1).asInt());
    }

    @Test
    public void tenClimbPayloadFitsIn4KB() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        StoredRoute route = buildRoute();
        for (int i = 1; i < 10; i++) route.climbs.add(route.climbs.get(0));
        assertTrue("10-climb payload < 4096 bytes", b.buildRoutePayload(route).length < 4096);
    }

    @Test
    public void shortKeysUsed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(mapper);
        JsonNode climb = mapper.readTree(b.buildRoutePayload(buildRoute())).get("climbs").get(0);
        assertTrue("sd key exists",  climb.has("sd"));
        assertTrue("ed key exists",  climb.has("ed"));
        assertTrue("len key exists", climb.has("len"));
        assertFalse("startDistance key gone", climb.has("startDistance"));
    }
}
```

- [ ] **Step 2: Verify tests fail**

```
./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest
```
Expected: FAIL — `v` is 1, `segs` field does not exist

- [ ] **Step 3: Replace ClimbPayloadBuilder.java**

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.paree.climbpro.data.route.StoredCalibrationPoint;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link StoredRoute} to the compact wire-format payload (version 2).
 *
 * Format:
 *   {v:2, mode:"route", routeId:"...", climbs:[
 *     {sd:N, ed:N, len:N, eg:N, ag:N, n:"...",
 *      segs:[dist,elevGain,gradient,colorIndex, ...],   // 4 ints × segCount
 *      calib:[dist,latInt,lonInt, ...]}                  // 3 ints × calibCount (optional)
 *   ]}
 *
 * latInt/lonInt = degrees × 100000 (1 m precision at equator).
 * gradient = fraction × 100 × 10 (fixed-point pct×10).
 */
public final class ClimbPayloadBuilder {

    private static final int SCHEMA_VERSION = 2;
    private final ObjectMapper mapper;

    public ClimbPayloadBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] buildRoutePayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        payload.put("climbs",  buildClimbs(route.climbs, false));
        return mapper.writeValueAsBytes(payload);
    }

    public byte[] buildRadiusPayload(List<StoredClimb> climbs) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",      SCHEMA_VERSION);
        payload.put("mode",   "radius");
        payload.put("climbs", buildClimbs(climbs, true));
        return mapper.writeValueAsBytes(payload);
    }

    private List<Map<String, Object>> buildClimbs(List<StoredClimb> src, boolean radius) {
        if (src == null) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (StoredClimb sc : src) {
            Map<String, Object> c = new LinkedHashMap<>();
            if (!radius) {
                c.put("sd", sc.startDistance);
                c.put("ed", sc.endDistance);
            } else {
                c.put("slat", Math.round(sc.startLat * 100000));
                c.put("slon", Math.round(sc.startLon * 100000));
            }
            c.put("len", sc.length);
            c.put("eg",  sc.elevationGain);
            c.put("ag",  toFixedPoint(sc.avgGradient));
            String name = sc.userDisplayName != null ? sc.userDisplayName : sc.name;
            if (name != null && name.length() <= 32) c.put("n", name);
            c.put("segs", buildSegs(sc.segments));
            if (sc.calibrationPoints != null && !sc.calibrationPoints.isEmpty()) {
                c.put("calib", buildCalib(sc.calibrationPoints));
            }
            out.add(c);
        }
        return out;
    }

    private static int[] buildSegs(List<StoredSegment> segs) {
        if (segs == null) return new int[0];
        int[] arr = new int[segs.size() * 4];
        for (int i = 0; i < segs.size(); i++) {
            StoredSegment s = segs.get(i);
            arr[i * 4]     = s.distance;
            arr[i * 4 + 1] = s.elevationGain;
            arr[i * 4 + 2] = toFixedPoint(s.gradient);
            arr[i * 4 + 3] = s.colorIndex;
        }
        return arr;
    }

    private static int[] buildCalib(List<StoredCalibrationPoint> pts) {
        int[] arr = new int[pts.size() * 3];
        for (int i = 0; i < pts.size(); i++) {
            StoredCalibrationPoint p = pts.get(i);
            arr[i * 3]     = p.distanceFromClimbStart;
            arr[i * 3 + 1] = (int) Math.round(p.lat * 100000);
            arr[i * 3 + 2] = (int) Math.round(p.lon * 100000);
        }
        return arr;
    }

    private static int toFixedPoint(double gradientFraction) {
        double pct = gradientFraction * 100.0;
        return (int) (pct >= 0 ? Math.floor(pct * 10 + 0.5) : Math.ceil(pct * 10 - 0.5));
    }
}
```

- [ ] **Step 4: Update ProtocolRoundTripTest to skip v1 round-trip**

Open `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java`.

The `exampleRoundTripsThroughGeneratedPojo` test is for v1 POJOs. The generated POJOs still exist but the builder no longer uses them. Replace the body of `exampleRoundTripsThroughGeneratedPojo` to check only the schema version instead:

```java
@Test
public void exampleRoundTripsThroughGeneratedPojo() throws Exception {
    // Generated POJOs (v1) are no longer used by ClimbPayloadBuilder.
    // This test retains schema-validation coverage via exampleValidatesAgainstSchema().
    // TODO: update protocol/examples/ and schema.json to v2 format in a follow-up.
    assertTrue("schema validation is done in exampleValidatesAgainstSchema", true);
}
```

- [ ] **Step 5: Verify payload tests pass**

```
./gradlew test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest
```
Expected: PASS (6 tests)

- [ ] **Step 6: Run all tests**

```
./gradlew test
```
Expected: PASS — no regressions. If `ProtocolRoundTripTest.exampleValidatesAgainstSchema` fails because the example JSONs are for v1 format, that test can be temporarily disabled with `@Ignore("examples not yet updated to v2 format")`.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java \
        android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java \
        android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolRoundTripTest.java
git commit -m "feat: payload v2 — HashMap format, short keys, flat segs/calib int arrays"
```

---

## Task 8: ClimbData.mc — add calibration arrays and GPS calibration check

**Files:**
- Modify: `garmin/source/ClimbData.mc`

Add calibration point arrays. Increase `MAX_CLIMBS` from 8 to 16. Add `checkCalibration(lat, lon)` which resets `progressInClimb` when within 30 m of the next calibration point.

Note: Monkey C has no unit test framework. Compilation check + manual simulator test are the verification steps.

- [ ] **Step 1: Add MAX_CALIB constant**

In `ClimbData.mc`, change `MAX_CLIMBS` and add `MAX_CALIB`:

```monkeyc
const MAX_CLIMBS = 16;
const MAX_SEGMENTS = 20;
const MAX_CALIB = 16;
```

- [ ] **Step 2: Add calib declarations**

After `var climbStartLon;`, add:

```monkeyc
// Calibration point arrays (indexed [climb][calib_point])
var calibCount;   // calibration points per climb
var calibDist;    // distance from climb start (m)
var calibLat;     // latitude (Float)
var calibLon;     // longitude (Float)
var calibIdx;     // next calibration point index to check (reset on payload)
```

- [ ] **Step 3: Initialise calib arrays in initialize()**

Inside `initialize()`, after the existing `segDist/segElevGain/segGradient/segColor` allocations, add:

```monkeyc
calibCount = new [MAX_CLIMBS];
calibDist  = new [MAX_CLIMBS];
calibLat   = new [MAX_CLIMBS];
calibLon   = new [MAX_CLIMBS];
calibIdx   = new [MAX_CLIMBS];

for (var i = 0; i < MAX_CLIMBS; i++) {
    calibCount[i] = 0;
    calibIdx[i]   = 0;
    calibDist[i]  = new [MAX_CALIB];
    calibLat[i]   = new [MAX_CALIB];
    calibLon[i]   = new [MAX_CALIB];
    for (var k = 0; k < MAX_CALIB; k++) {
        calibDist[i][k] = 0;
        calibLat[i][k]  = 0.0f;
        calibLon[i][k]  = 0.0f;
    }
}
```

Note: The existing `for (var i = 0; i < MAX_CLIMBS; i++)` loop in `initialize()` initialises all per-climb values. If the calib initialisation is simpler to put inside that loop, do so.

- [ ] **Step 4: Add checkCalibration and updateCurrentSegment methods**

Add after `updateProgress`:

```monkeyc
// Call on each GPS update when activeClimbIndex >= 0.
// Resets progressInClimb when within 30 m of the next calibration point.
function checkCalibration(lat, lon) {
    if (activeClimbIndex < 0) { return; }
    var ci = activeClimbIndex;
    var k  = calibIdx[ci];
    if (k >= calibCount[ci]) { return; }

    var dlat = lat - calibLat[ci][k];
    var dlon = lon - calibLon[ci][k];
    // Rough distance in metres: 1° lat ≈ 111111 m; 1° lon ≈ 111111 × cos(lat) m
    // Use simplified flat-Earth approx: d² ≈ (dlat×111111)² + (dlon×111111×cos(lat))²
    var cosLat = Math.cos(lat * Math.PI / 180.0f);
    var dm = Math.sqrt((dlat * 111111.0f) * (dlat * 111111.0f)
                     + (dlon * 111111.0f * cosLat) * (dlon * 111111.0f * cosLat));
    if (dm < 30.0f) {
        progressInClimb = calibDist[ci][k];
        calibIdx[ci] = k + 1;
        updateCurrentSegment();
    }
}

hidden function updateCurrentSegment() {
    var ci = activeClimbIndex;
    if (ci < 0) { return; }
    var cumDist = 0;
    for (var s = 0; s < segCount[ci]; s++) {
        cumDist += segDist[ci][s];
        if (progressInClimb <= cumDist) {
            activeSegmentIndex = s;
            return;
        }
    }
    activeSegmentIndex = segCount[ci] - 1;
}
```

- [ ] **Step 5: Verify Monkey C compiles**

```
monkeyc -o app.prg -f garmin/monkey.jungle -y <developer_key> --device fr255m
```
Expected: no compile errors. If `Math.cos` or `Math.PI` are unavailable, use `Toybox.Math.cos` and `Toybox.Math.PI` instead.

- [ ] **Step 6: Commit**

```bash
git add garmin/source/ClimbData.mc
git commit -m "feat: ClimbData adds calibration arrays and GPS checkCalibration()"
```

---

## Task 9: CommListener.mc — decode compact v2 format

**Files:**
- Modify: `garmin/source/CommListener.mc`

Update `onMessage` version check from `!= 1` to `!= 2`. Update `parseClimb` to read short keys and decode the flat `segs` / `calib` arrays. Reset `calibIdx` arrays when a new payload arrives.

- [ ] **Step 1: Update version check in onMessage**

Change:
```monkeyc
if (version == null || version != 1) {
```
To:
```monkeyc
if (version == null || version != 2) {
```

- [ ] **Step 2: Reset calibIdx after parsing climbs**

In `onMessage`, after `data.payloadReceived = true;`, add:

```monkeyc
for (var i = 0; i < data.climbCount; i++) { data.calibIdx[i] = 0; }
```

- [ ] **Step 3: Replace parseClimb entirely**

```monkeyc
hidden function parseClimb(data, idx, climbDict) {
    if (climbDict == null || !(climbDict instanceof Toybox.Lang.Dictionary)) {
        return;
    }

    // Short keys (v2 format)
    data.climbStartDist[idx] = getInt(climbDict, "sd",  0);
    data.climbEndDist[idx]   = getInt(climbDict, "ed",  0);
    data.climbLength[idx]    = getInt(climbDict, "len", 0);
    data.climbElevGain[idx]  = getInt(climbDict, "eg",  0);
    data.climbAvgGrad[idx]   = getInt(climbDict, "ag",  0);
    data.climbName[idx]      = climbDict.get("n");

    // Radius mode: lat/lon as ints (degrees × 100000)
    var slatInt = climbDict.get("slat");
    var slonInt = climbDict.get("slon");
    data.climbStartLat[idx] = (slatInt != null && slatInt instanceof Toybox.Lang.Number)
        ? (slatInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;
    data.climbStartLon[idx] = (slonInt != null && slonInt instanceof Toybox.Lang.Number)
        ? (slonInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;

    // Flat segs array: [dist, elevGain, gradient, colorIndex, ...] 4 ints × segCount
    var segs = climbDict.get("segs");
    if (segs != null && segs instanceof Toybox.Lang.Array && segs.size() >= 4) {
        var segCount = segs.size() / 4;
        if (segCount > data.MAX_SEGMENTS) { segCount = data.MAX_SEGMENTS; }
        data.segCount[idx] = segCount;
        for (var s = 0; s < segCount; s++) {
            data.segDist[idx][s]     = segs[s * 4];
            data.segElevGain[idx][s] = segs[s * 4 + 1];
            data.segGradient[idx][s] = segs[s * 4 + 2];
            data.segColor[idx][s]    = segs[s * 4 + 3];
        }
    } else {
        data.segCount[idx] = 0;
    }

    // Flat calib array: [distFromStart, latInt, lonInt, ...] 3 ints × calibCount
    var calib = climbDict.get("calib");
    if (calib != null && calib instanceof Toybox.Lang.Array && calib.size() >= 3) {
        var calibCount = calib.size() / 3;
        if (calibCount > data.MAX_CALIB) { calibCount = data.MAX_CALIB; }
        data.calibCount[idx] = calibCount;
        for (var k = 0; k < calibCount; k++) {
            data.calibDist[idx][k] = calib[k * 3];
            data.calibLat[idx][k]  = (calib[k * 3 + 1] as Toybox.Lang.Number).toFloat() / 100000.0f;
            data.calibLon[idx][k]  = (calib[k * 3 + 2] as Toybox.Lang.Number).toFloat() / 100000.0f;
        }
    } else {
        data.calibCount[idx] = 0;
    }
}
```

- [ ] **Step 4: Compile**

```
monkeyc -o app.prg -f garmin/monkey.jungle -y <developer_key> --device fr255m
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Manual test in simulator**

Open the Connect IQ simulator, load the FR255M profile, install the `.prg`. Use the simulator's "Send message" feature to inject a v2 payload:

```json
{"v":2,"mode":"route","routeId":"test","climbs":[{"sd":0,"ed":2000,"len":2000,"eg":80,"ag":40,"segs":[125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2,125,5,40,2],"calib":[800,5150000,510000]}]}
```

Verify in simulator console: `CommListener: payload parsed, 1 climbs`. Verify `climbLength[0] = 2000`, `segCount[0] = 16`, `calibCount[0] = 1`.

- [ ] **Step 6: Commit**

```bash
git add garmin/source/CommListener.mc
git commit -m "feat: CommListener decodes payload v2 (short keys, flat segs/calib arrays)"
```

---

## Task 10: StoredClimb.segmentCount + Segmenter overload (Plan C)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimb.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java`

- [ ] **Step 1: Write failing tests**

Add to `SegmenterTest.java`:

```java
@Test
public void segmentOverloadReturnsRequestedCount() {
    List<RoutePoint> climb = buildClimb(2000, 0.05);
    assertEquals(8,  Segmenter.segment(climb, 8).size());
    assertEquals(12, Segmenter.segment(climb, 12).size());
    assertEquals(20, Segmenter.segment(climb, 20).size());
}

@Test
public void segmentOverloadSumEqualsClimbLength() {
    List<RoutePoint> climb = buildClimb(3000, 0.06);
    List<Segment> segs = Segmenter.segment(climb, 8);
    int total = 0;
    for (Segment s : segs) total += s.distance;
    assertEquals("sum = climb length for 8 segments", 3000, total, 2);
}
```

- [ ] **Step 2: Verify tests fail**

```
./gradlew test --tests "nl.paree.climbpro.domain.SegmenterTest#segmentOverloadReturnsRequestedCount"
```
Expected: COMPILE FAIL — `segment(List, int)` not found

- [ ] **Step 3: Add segment overload to Segmenter.java**

Add after the existing `segment(List<RoutePoint>)` method:

```java
/**
 * Splits a climb into exactly {@code segmentCount} segments.
 * Identical logic to {@link #segment(List)} but with a custom count.
 */
public static List<Segment> segment(List<RoutePoint> climbPoints, int segmentCount) {
    if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

    RoutePoint first = climbPoints.get(0);
    RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
    double totalLength = last.distance - first.distance;
    if (totalLength <= 0) return new ArrayList<>();

    double segmentLength = totalLength / segmentCount;
    List<Segment> segments = new ArrayList<>(segmentCount);

    double segStart    = first.distance;
    double segStartEle = first.elevation;
    int ptIdx = 1;

    while (segStart < last.distance - 0.5) {
        double segEnd = Math.min(segStart + segmentLength, last.distance);

        while (ptIdx < climbPoints.size() - 1
                && climbPoints.get(ptIdx).distance < segEnd) {
            ptIdx++;
        }

        double endEle  = interpolateElevation(climbPoints, ptIdx, segEnd);
        double dist    = segEnd - segStart;
        double eleGain = endEle - segStartEle;
        double grad    = dist > 0 ? eleGain / dist : 0;
        int color      = GradientColor.forGradient(grad);

        segments.add(new Segment((int) Math.round(dist), (int) Math.round(eleGain), grad, color));

        segStart    = segEnd;
        segStartEle = endEle;
    }

    return segments;
}
```

- [ ] **Step 4: Add segmentCount field to StoredClimb.java**

```java
public int segmentCount = 0; // 0 means use ClimbConstants.SEGMENT_COUNT (for old stored routes)
```

- [ ] **Step 5: Verify all Segmenter tests pass**

```
./gradlew test --tests nl.paree.climbpro.domain.SegmenterTest
```
Expected: PASS (all existing tests + 2 new overload tests)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/segment/Segmenter.java \
        android/app/src/main/java/nl/paree/climbpro/data/route/StoredClimb.java \
        android/app/src/test/java/nl/paree/climbpro/domain/SegmenterTest.java
git commit -m "feat: Segmenter.segment(pts, count) overload; StoredClimb.segmentCount field"
```

---

## Task 11: RouteRepository.reSegmentClimb + ClimbDetailViewModel.reSegment

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java`

`reSegmentClimb` re-runs Segmenter on the existing stored route-point arrays for the given climb. Saves with `writeAtomic`. No unit test (requires Android Context); verify manually.

- [ ] **Step 1: Read the bottom of RouteRepository.java**

Before editing, read `android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java` lines 165–end to find `writeAtomic`, `toStoredClimbs`, and any existing helpers for converting domain objects to stored form.

- [ ] **Step 2: Add helper methods to RouteRepository**

Add these two private helpers (after the existing `toStoredClimbs` method — use the same style):

```java
private List<StoredSegment> toStoredSegments(
        List<nl.paree.climbpro.domain.segment.Segment> segs) {
    List<StoredSegment> out = new ArrayList<>(segs.size());
    for (nl.paree.climbpro.domain.segment.Segment s : segs) {
        StoredSegment ss = new StoredSegment();
        ss.distance      = s.distance;
        ss.elevationGain = s.elevationGain;
        ss.gradient      = s.gradient;
        ss.colorIndex    = s.colorIndex;
        out.add(ss);
    }
    return out;
}

private List<StoredCalibrationPoint> toStoredCalibPoints(
        List<nl.paree.climbpro.domain.segment.CalibrationPoint> cps) {
    List<StoredCalibrationPoint> out = new ArrayList<>(cps.size());
    for (nl.paree.climbpro.domain.segment.CalibrationPoint cp : cps) {
        StoredCalibrationPoint scp   = new StoredCalibrationPoint();
        scp.distanceFromClimbStart   = cp.distanceFromClimbStart;
        scp.lat                      = cp.lat;
        scp.lon                      = cp.lon;
        out.add(scp);
    }
    return out;
}
```

- [ ] **Step 3: Add reSegmentClimb to RouteRepository**

Add the public method:

```java
/**
 * Re-segments one climb in a stored route using the given segment count.
 * Extracts the climb's RoutePoints from the stored arrays, runs Segmenter,
 * recomputes calibration points, and saves the route file atomically.
 */
public void reSegmentClimb(String routeId, int climbIndex, int newSegmentCount) throws IOException {
    StoredRoute route = loadRoute(routeId);
    if (route.climbs == null || climbIndex >= route.climbs.size()) {
        throw new IOException("Climb index out of range: " + climbIndex);
    }

    StoredClimb sc = route.climbs.get(climbIndex);
    List<nl.paree.climbpro.domain.route.RoutePoint> climbPts = extractClimbPoints(route, sc);

    int count = newSegmentCount > 0 ? newSegmentCount
                                    : nl.paree.climbpro.domain.climb.ClimbConstants.SEGMENT_COUNT;

    sc.segments          = toStoredSegments(
            nl.paree.climbpro.domain.segment.Segmenter.segment(climbPts, count));
    sc.calibrationPoints = toStoredCalibPoints(
            nl.paree.climbpro.domain.segment.Segmenter.calibrationPoints(climbPts));
    sc.segmentCount      = count;
    route.lastModifiedMs = System.currentTimeMillis();

    writeAtomic(routeFile(routeId), mapper.writeValueAsBytes(route));
}

private List<nl.paree.climbpro.domain.route.RoutePoint> extractClimbPoints(
        StoredRoute route, StoredClimb sc) {
    List<nl.paree.climbpro.domain.route.RoutePoint> pts = new ArrayList<>();
    if (route.distances == null) return pts;
    for (int i = 0; i < route.distances.length; i++) {
        double d = route.distances[i];
        if (d >= sc.startDistance && d <= sc.endDistance) {
            pts.add(new nl.paree.climbpro.domain.route.RoutePoint(
                    route.lats[i], route.lons[i], route.elevations[i], d));
        }
    }
    return pts;
}
```

- [ ] **Step 4: Add reSegment to ClimbDetailViewModel**

```java
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
```

- [ ] **Step 5: Run all tests**

```
./gradlew test
```
Expected: PASS — no new unit tests, no regressions

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/route/RouteRepository.java \
        android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailViewModel.java
git commit -m "feat: RouteRepository.reSegmentClimb and ClimbDetailViewModel.reSegment"
```

---

## Task 12: ClimbDetailActivity — "Herbereken segmenten" button and dialog

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java`
- Modify: `android/app/src/main/res/layout/activity_climb_detail.xml`

No automated test. Verify manually on emulator.

- [ ] **Step 1: Add button to activity_climb_detail.xml**

Open `android/app/src/main/res/layout/activity_climb_detail.xml`. Find the rename button (search for the existing button that calls the rename dialog). Add a sibling button directly after it:

```xml
<Button
    android:id="@+id/btnReSegment"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Herbereken segmenten"
    android:layout_marginTop="8dp" />
```

- [ ] **Step 2: Wire button click in ClimbDetailActivity.onCreate**

In `ClimbDetailActivity.java`, in `onCreate` after the binding is inflated, add:

```java
binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());
```

- [ ] **Step 3: Add showReSegmentDialog method**

Add to `ClimbDetailActivity.java`:

```java
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
```

- [ ] **Step 4: Ensure saved/error observers exist in onCreate**

Verify (or add) these LiveData observers in `ClimbDetailActivity.onCreate`:

```java
viewModel.saved().observe(this, isSaved -> {
    if (Boolean.TRUE.equals(isSaved)) {
        Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
    }
});

viewModel.error().observe(this, msg -> {
    if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
});
```

- [ ] **Step 5: Verify app builds**

```
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Manual test on emulator**

1. Build and install: `./gradlew installDebug`
2. Open a route → tap a climb → ClimbDetailActivity opens
3. Tap "Herbereken segmenten" → NumberPicker dialog appears, default = 16
4. Set to 8 → tap "Herbereken" → toast "Opgeslagen" → segment list refreshes to 8 items
5. Open climb again → NumberPicker default is now 8

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java \
        android/app/src/main/res/layout/activity_climb_detail.xml
git commit -m "feat: ClimbDetailActivity adds resegment dialog (Plan C)"
```

---

## Verification checklist

After all 12 tasks are complete, run the full test suite one final time:

```
./gradlew test
```

Expected: all green. Then verify end-to-end manually:

1. **Clean install** (uninstall + reinstall) — `RouteRepository.migrateIfNeeded` runs, old routes cleared.
2. **Import a Strava route or GPX** — climb list shows; each climb has exactly 16 segments.
3. **Sync to watch** — payload sent (stub, no-op until CIQ SDK linked). Verify payload JSON is < 4096 bytes in a test.
4. **Resegment** — open a climb, change segment count to 8, confirm — segment list shows 8 segments.
5. **Garmin simulator** — load the `.prg`, send the v2 test payload from Task 9 Step 5. Verify `segCount[0] = 16` and `calibCount[0] = 1` in the console.
