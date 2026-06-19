# Trim False-Flat Climb Ends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trim the *vals plat* (false-flat) lead-in and lead-out off each detected climb, so a climb starts and ends where the real climbing is, not on a gentle ramp.

**Architecture:** A new pure domain class `ClimbTrimmer` takes a climb's route points and returns a trimmed sub-list, removing leading/trailing stretches whose local gradient is below 2% — but only when the trimmed-off stretch is ≥ 200 m AND the remaining climb stays ≥ 800 m (so we never trim a climb below the domain minimum). `ClimbDetector` calls `ClimbTrimmer` after it has validated a climb, then recomputes start/end/length/gain/gradient/segments from the trimmed points. Nothing on the wire changes — `startDistance`/`endDistance`/`startLat`/`startLon` simply get tighter values.

**Tech Stack:** Java (Android module), JUnit 4. Pure functions in `domain/`, no I/O. Build/test with Gradle: `./gradlew test`.

**Why this design:** `ClimbDetector` already skips lead-ins whose *local* gradient is below 1.5% when searching for a climb start, but (a) lead-ins in the 1.5–2% band still get included, and (b) once a climb starts the extension loop runs all the way to the highest point, so a gentle trailing rise (e.g. 1.5% still climbing toward the peak) gets absorbed. Both are *vals plat* and are exactly what this trimmer removes. Keeping the trimmer as a separate pure function (not inline detector edits) keeps it trivially unit-testable and leaves the detector's core scan untouched.

---

## File Structure

- `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java` — **modify**: add two constants for the false-flat threshold.
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbTrimmer.java` — **create**: the pure trimming function.
- `android/app/src/test/java/nl/paree/climbpro/domain/ClimbTrimmerTest.java` — **create**: unit tests for the trimmer in isolation.
- `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbDetector.java` — **modify**: call the trimmer and recompute climb fields from trimmed points.
- `android/app/src/test/java/nl/paree/climbpro/domain/ClimbDetectorTest.java` — **modify**: add detector-level tests proving lead-in and lead-out get trimmed.
- `Documentation/ARCHITECTURE.md` — **modify**: add the trim step to the data-flow diagram and an invariant.
- `CLAUDE.md` — **modify**: record the false-flat trim as a domain rule.

> **Naming note (use these exact names in every task):** class `ClimbTrimmer`, method `ClimbTrimmer.trim(List<RoutePoint>)`, constants `ClimbConstants.FALSE_FLAT_MAX_GRADIENT` and `ClimbConstants.FALSE_FLAT_MIN_LENGTH_M`. `RoutePoint` fields are `lat`, `lon`, `elevation`, `distance` (constructor order: `new RoutePoint(lat, lon, elevation, distance)`).

---

### Task 1: Add false-flat threshold constants

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java` (exists)

- [ ] **Step 1: Add the constants**

In `ClimbConstants.java`, add these two fields after `ROUTE_MATCHING_HYSTERESIS_M` (keep the existing fields unchanged):

```java
    /** A stretch averaging below this gradient (fraction) counts as "vals plat" (false flat). */
    public static final double FALSE_FLAT_MAX_GRADIENT      = 0.02;
    /** Minimum length (metres) of a leading/trailing false flat before it is trimmed off a climb. */
    public static final int    FALSE_FLAT_MIN_LENGTH_M      = 200;
```

- [ ] **Step 2: Add a test asserting the constant values**

Open `ClimbConstantsTest.java` and add these two test methods inside the existing test class (match the existing import of `org.junit.Test` and `static org.junit.Assert.*`; if a constant-value test already exists, add alongside it):

```java
    @Test
    public void falseFlatMaxGradientIsTwoPercent() {
        assertEquals(0.02, ClimbConstants.FALSE_FLAT_MAX_GRADIENT, 1e-9);
    }

    @Test
    public void falseFlatMinLengthIs200m() {
        assertEquals(200, ClimbConstants.FALSE_FLAT_MIN_LENGTH_M);
    }
```

If `ClimbConstantsTest` does not already import `ClimbConstants`, add `import nl.paree.climbpro.domain.climb.ClimbConstants;` (the test lives in package `nl.paree.climbpro.domain`).

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./gradlew test --tests nl.paree.climbpro.domain.ClimbConstantsTest`
Expected: PASS (build is green; both new methods pass).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbConstants.java android/app/src/test/java/nl/paree/climbpro/domain/ClimbConstantsTest.java
git commit -m "feat(climb): add false-flat trim threshold constants"
```

---

### Task 2: Create `ClimbTrimmer` (pure trimming function) — TDD

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbTrimmer.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbTrimmerTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/domain/ClimbTrimmerTest.java` with this exact content:

```java
package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.climb.ClimbTrimmer;
import nl.paree.climbpro.domain.route.RoutePoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ClimbTrimmerTest {

    /**
     * Build route points from segments. Each entry is [lengthMetres, gradientFraction].
     * Segments are joined continuously; elevation starts at 100 m, distance at 0.
     * 10 points per segment (so the step length is lengthMetres / 10).
     */
    private static List<RoutePoint> route(double[][] segs) {
        List<RoutePoint> pts = new ArrayList<>();
        double dist = 0.0;
        double ele = 100.0;
        pts.add(new RoutePoint(51.0, 5.0, ele, dist));
        for (double[] s : segs) {
            double len = s[0];
            double grad = s[1];
            int steps = 10;
            for (int i = 0; i < steps; i++) {
                dist += len / steps;
                ele  += (len / steps) * grad;
                pts.add(new RoutePoint(51.0, 5.0, ele, dist));
            }
        }
        return pts;
    }

    private static double len(List<RoutePoint> pts) {
        return pts.get(pts.size() - 1).distance - pts.get(0).distance;
    }

    @Test
    public void pureClimbIsNotTrimmed() {
        List<RoutePoint> in = route(new double[][]{{1200, 0.05}});
        List<RoutePoint> out = ClimbTrimmer.trim(in);
        assertEquals("no trim on a pure climb", in.size(), out.size());
        assertEquals(0.0, out.get(0).distance, 1e-6);
    }

    @Test
    public void trimsLeadingFalseFlat() {
        // 300 m at 1% (vals plat) then 1000 m at 6%
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{300, 0.01}, {1000, 0.06}}));
        assertTrue("leading flat removed", out.get(0).distance >= 250);
        assertTrue("remaining length ~1000 m", len(out) >= 950 && len(out) <= 1050);
    }

    @Test
    public void trimsTrailingFalseFlat() {
        // 1000 m at 6% then 300 m at 1.5% (vals plat)
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{1000, 0.06}, {300, 0.015}}));
        assertTrue("trailing flat removed", out.get(out.size() - 1).distance <= 1050);
        assertTrue("remaining length ~1000 m", len(out) >= 950 && len(out) <= 1050);
    }

    @Test
    public void shortLeadInBelowMinLengthIsKept() {
        // only 100 m of flat (< 200 m) — must NOT be trimmed
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{100, 0.01}, {1000, 0.06}}));
        assertEquals("short lead-in kept", 0.0, out.get(0).distance, 1e-6);
    }

    @Test
    public void trimIsSkippedWhenItWouldDropBelow800m() {
        // 300 m flat + only 700 m of climb: trimming the flat leaves 700 m < 800 m, so keep everything
        List<RoutePoint> out = ClimbTrimmer.trim(route(new double[][]{{300, 0.01}, {700, 0.06}}));
        assertEquals("no trim — would fall below 800 m", 0.0, out.get(0).distance, 1e-6);
        assertTrue("full length kept", len(out) >= 950);
    }

    @Test
    public void tooFewPointsReturnedUnchanged() {
        List<RoutePoint> in = new ArrayList<>();
        in.add(new RoutePoint(51.0, 5.0, 100.0, 0.0));
        assertEquals(in.size(), ClimbTrimmer.trim(in).size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests nl.paree.climbpro.domain.ClimbTrimmerTest`
Expected: FAIL — compilation error, `ClimbTrimmer` does not exist (`cannot find symbol: class ClimbTrimmer`).

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbTrimmer.java` with this exact content:

```java
package nl.paree.climbpro.domain.climb;

import nl.paree.climbpro.domain.route.RoutePoint;

import java.util.List;

/**
 * Trims leading and trailing "vals plat" (false-flat) stretches off a detected climb.
 *
 * <p>A false flat is a contiguous low-gradient run at the start or end of the climb.
 * The lead-in is trimmed while the local point-to-point gradient stays below
 * {@link ClimbConstants#FALSE_FLAT_MAX_GRADIENT}; the lead-out symmetrically.
 *
 * <p>A trim is only committed when the stretch being removed is at least
 * {@link ClimbConstants#FALSE_FLAT_MIN_LENGTH_M} long AND the climb that remains
 * is still at least {@link ClimbConstants#MIN_CLIMB_LENGTH_M} — the trimmer never
 * shrinks a climb below the domain minimum.
 *
 * <p><b>Precondition:</b> elevation is already smoothed (the detector runs on
 * smoothed elevation), so the local gradient is meaningful and not GPS-jittery.
 * Distances are cumulative from the route start; the returned points keep their
 * original distance/lat/lon, so callers recompute climb fields directly from them.
 */
public final class ClimbTrimmer {

    private ClimbTrimmer() {}

    public static List<RoutePoint> trim(List<RoutePoint> pts) {
        int n = pts.size();
        if (n < 3) return pts;

        int last = n - 1;
        if (pts.get(last).distance - pts.get(0).distance <= 0) return pts;

        int start = 0;
        int end = last;

        // Leading false flat: advance while the local gradient stays below the threshold.
        int s = 0;
        while (s < last && gradient(pts, s, s + 1) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            s++;
        }
        double trimmedLeadIn = pts.get(s).distance - pts.get(start).distance;
        double remainingAfterLead = pts.get(end).distance - pts.get(s).distance;
        if (s > start
                && trimmedLeadIn >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M
                && remainingAfterLead >= ClimbConstants.MIN_CLIMB_LENGTH_M) {
            start = s;
        }

        // Trailing false flat: walk back while the local gradient stays below the threshold.
        int e = last;
        while (e > start && gradient(pts, e - 1, e) < ClimbConstants.FALSE_FLAT_MAX_GRADIENT) {
            e--;
        }
        double trimmedLeadOut = pts.get(end).distance - pts.get(e).distance;
        double remainingAfterTail = pts.get(e).distance - pts.get(start).distance;
        if (e < end
                && trimmedLeadOut >= ClimbConstants.FALSE_FLAT_MIN_LENGTH_M
                && remainingAfterTail >= ClimbConstants.MIN_CLIMB_LENGTH_M) {
            end = e;
        }

        if (start == 0 && end == last) return pts;
        return pts.subList(start, end + 1);
    }

    private static double gradient(List<RoutePoint> pts, int a, int b) {
        double dist = pts.get(b).distance - pts.get(a).distance;
        if (dist <= 0) return 0;
        double ele = pts.get(b).elevation - pts.get(a).elevation;
        if (Double.isNaN(ele)) return 0;
        return ele / dist;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests nl.paree.climbpro.domain.ClimbTrimmerTest`
Expected: PASS (all 6 tests green).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbTrimmer.java android/app/src/test/java/nl/paree/climbpro/domain/ClimbTrimmerTest.java
git commit -m "feat(climb): add ClimbTrimmer to remove false-flat lead-in/lead-out"
```

---

### Task 3: Wire `ClimbTrimmer` into `ClimbDetector` — TDD

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbDetector.java:83-98`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/ClimbDetectorTest.java`

- [ ] **Step 1: Write the failing detector tests**

In `ClimbDetectorTest.java`, add these two methods inside the existing test class (the existing `buildRoute(double[][])` helper takes `[startDist, endDist, gradient]` absolute segments — reuse it):

```java
    @Test
    public void trimsFalseFlatLeadIn() {
        // 300 m at 1.7% (above the 1.5% start-skip, below the 2% false-flat line) then 1000 m at 6%.
        List<RoutePoint> route = buildRoute(new double[][]{{0, 300, 0.017}, {300, 1300, 0.06}});
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("one climb expected", 1, climbs.size());
        Climb c = climbs.get(0);
        assertTrue("lead-in trimmed (start pushed forward)", c.startDistance >= 250);
        assertTrue("trimmed climb still >= 800 m", c.length >= 800);
    }

    @Test
    public void trimsFalseFlatLeadOut() {
        // 1000 m at 6% then 400 m at 1.5% still rising toward the peak — must be trimmed off the end.
        List<RoutePoint> route = buildRoute(new double[][]{{0, 1000, 0.06}, {1000, 1400, 0.015}});
        List<Climb> climbs = ClimbDetector.detect(route);
        assertEquals("one climb expected", 1, climbs.size());
        Climb c = climbs.get(0);
        assertTrue("lead-out trimmed (end pulled back)", c.endDistance <= 1100);
        assertTrue("trimmed climb still >= 800 m", c.length >= 800);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests nl.paree.climbpro.domain.ClimbDetectorTest`
Expected: FAIL — `trimsFalseFlatLeadIn` fails on `c.startDistance >= 250` (currently ~0, no trim), and `trimsFalseFlatLeadOut` fails on `c.endDistance <= 1100` (currently ~1400, extends to the peak).

- [ ] **Step 3: Apply the trimmer in the detector**

In `ClimbDetector.java`, replace the block that currently reads (lines 83-98):

```java
            List<RoutePoint> climbPoints = points.subList(startIdx, endIdx + 1);
            List<Segment> segments = Segmenter.segment(climbPoints);

            Climb climb = Climb.builder()
                    .startDistance((int) Math.round(start.distance))
                    .endDistance((int) Math.round(end.distance))
                    .length((int) Math.round(length))
                    .elevationGain((int) Math.round(eleGain))
                    .avgGradient(avgGradient)
                    .startLat(start.lat)
                    .startLon(start.lon)
                    .segments(segments)
                    .build();

            climbs.add(climb);
            i = endIdx + 1;
```

with:

```java
            // Trim leading/trailing false flat, then recompute the climb from the trimmed points.
            List<RoutePoint> climbPoints = ClimbTrimmer.trim(points.subList(startIdx, endIdx + 1));
            RoutePoint trimmedStart = climbPoints.get(0);
            RoutePoint trimmedEnd   = climbPoints.get(climbPoints.size() - 1);
            double trimmedLength   = trimmedEnd.distance - trimmedStart.distance;
            double trimmedEleGain  = trimmedEnd.elevation - trimmedStart.elevation;
            double trimmedGradient = trimmedLength > 0 ? trimmedEleGain / trimmedLength : 0;

            List<Segment> segments = Segmenter.segment(climbPoints);

            Climb climb = Climb.builder()
                    .startDistance((int) Math.round(trimmedStart.distance))
                    .endDistance((int) Math.round(trimmedEnd.distance))
                    .length((int) Math.round(trimmedLength))
                    .elevationGain((int) Math.round(trimmedEleGain))
                    .avgGradient(trimmedGradient)
                    .startLat(trimmedStart.lat)
                    .startLon(trimmedStart.lon)
                    .segments(segments)
                    .build();

            climbs.add(climb);
            // Advance past the ORIGINAL (untrimmed) end so a trailing flat is not rescanned.
            i = endIdx + 1;
```

> Leave the validation block above it (the `length < MIN_CLIMB_LENGTH_M` / `avgGradient < MIN_AVG_GRADIENT` checks at lines 67-81) unchanged: a climb is validated on its untrimmed extent first, then trimming only makes it steeper, and `ClimbTrimmer` guarantees the trimmed length stays ≥ 800 m. The local variables `start`, `end`, `length`, `eleGain`, `avgGradient` from that block are still used by the validation; only the climb that gets *built* uses the trimmed values.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests nl.paree.climbpro.domain.ClimbDetectorTest`
Expected: PASS — all existing detector tests plus the two new ones are green. (`detectsOneClimbAboveThreshold`, `climbLengthAndGradientAreCorrect`, etc. still pass because a pure climb is not trimmed.)

- [ ] **Step 5: Run the full domain test suite for regressions**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — confirms `Segmenter`, `ClimbPayloadBuilder`, protocol round-trip, and calibration tests are unaffected by tighter climb boundaries.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/domain/climb/ClimbDetector.java android/app/src/test/java/nl/paree/climbpro/domain/ClimbDetectorTest.java
git commit -m "feat(climb): trim false-flat ends in ClimbDetector"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md:129` and `Documentation/ARCHITECTURE.md:224-228`
- Modify: `CLAUDE.md` (the "Non-negotiable domain rules" section)

- [ ] **Step 1: Add the trim step to the inbound data-flow diagram**

In `Documentation/ARCHITECTURE.md`, change the pipeline line (line 129) from:

```
GPX file ────┼──► Android Repository ──► Domain: parse → smooth → simplify → detect climbs → segment
```

to:

```
GPX file ────┼──► Android Repository ──► Domain: parse → smooth → simplify → detect climbs → trim false flat → segment
```

- [ ] **Step 2: Add the trim invariant**

In `Documentation/ARCHITECTURE.md`, in the **Invariants** list (lines 224-228), add this bullet after the existing "length ≥ 800 m and avg gradient ≥ 3%" bullet:

```markdown
- A detected `Climb` has its leading/trailing **vals plat** (false flat: a contiguous stretch averaging < 2% over ≥ 200 m) trimmed off, but is never trimmed below the 800 m minimum. After trimming, start/end distance and `startLat`/`startLon` reflect the tighter boundaries. See `domain/climb/ClimbTrimmer.java`.
```

- [ ] **Step 3: Record the domain rule in CLAUDE.md**

In `CLAUDE.md`, under **## Non-negotiable domain rules**, add this bullet after the **Climb definition** bullet:

```markdown
- **False-flat trim**: after a climb is detected, leading and trailing *vals plat* — a contiguous stretch averaging **< 2 %** gradient over **≥ 200 m** — is trimmed off so the climb starts/ends on real climbing. Never trim a climb below the 800 m minimum. Threshold lives in `ClimbConstants` (`FALSE_FLAT_MAX_GRADIENT`, `FALSE_FLAT_MIN_LENGTH_M`); logic in `domain/climb/ClimbTrimmer.java`.
```

- [ ] **Step 4: Commit**

```bash
git add Documentation/ARCHITECTURE.md CLAUDE.md
git commit -m "docs: describe false-flat trimming of climb ends"
```

---

## Self-Review

**Spec coverage:**
- "Trim begin/eind vals plat" → Tasks 2 (trimmer) + 3 (detector integration). ✅
- Threshold "< 2% over ≥ 200 m" → Task 1 constants, used in `ClimbTrimmer`. ✅
- Domain rule "never below 800 m" preserved → guard in `ClimbTrimmer.trim`, tested by `trimIsSkippedWhenItWouldDropBelow800m`. ✅
- Segments/payload stay consistent → `Segmenter.segment(climbPoints)` re-runs on trimmed points; `./gradlew test` (Task 3 Step 5) covers payload/round-trip regressions. ✅
- Wire format unchanged → no `schema.json` / Monkey C edits needed (only the *values* of existing fields change). ✅
- Docs updated in the same change → Task 4. ✅

**Placeholder scan:** No TODOs, no "add error handling", every code step shows full code. ✅

**Type consistency:** `ClimbTrimmer.trim(List<RoutePoint>)` returns `List<RoutePoint>`; detector consumes it via `.get(0)` / `.get(size-1)` and `Segmenter.segment(List<RoutePoint>)`. Constants `FALSE_FLAT_MAX_GRADIENT` (double) and `FALSE_FLAT_MIN_LENGTH_M` (int) match `ClimbConstants` field style. `RoutePoint(lat, lon, elevation, distance)` constructor order matches existing test usage. ✅

**Note on existing detector behavior verified:** the initial start-skip uses `MIN_AVG_GRADIENT * 0.5` = 1.5%, which is why the lead-in test uses 1.7% (gets included by the detector, then trimmed) and the lead-out test relies on the extension-to-peak loop. ✅
