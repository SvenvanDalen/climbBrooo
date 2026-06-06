# Climb Map Segment Gradient Colors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Color each segment of the climb polyline on the OSM map based on its gradient, using the canonical CLAUDE.md color table (light yellow → yellow → dark yellow → orange → dark orange → red).

**Architecture:** Create `SegmentColorPalette` as the single Android-layer source of truth for gradient index → color int. Refactor `ClimbDetailActivity.tryDrawMap()` to draw one `Polyline` per `StoredSegment` instead of one flat orange line. Also fix `ClimbProfileView`'s wrong green-based colors to use the same palette.

**Tech Stack:** Java, Android, OSMDroid (`Polyline`), existing `StoredSegment.colorIndex` and `StoredClimb.startDistance` fields.

---

## File structure

| Action | File |
|--------|------|
| Create | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/SegmentColorPalette.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` |
| Modify | `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbProfileView.java` |

---

### Task 1: Create `SegmentColorPalette`

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/SegmentColorPalette.java`

This is the Android-layer counterpart of `GradientColor` (domain layer). `GradientColor` owns the index mapping; `SegmentColorPalette` owns the color ints. Both `ClimbDetailActivity` and `ClimbProfileView` will use it so colors stay in sync.

- [ ] **Step 1: Create `SegmentColorPalette.java`**

```java
package nl.paree.climbpro.ui.climbs;

import android.graphics.Color;

/**
 * Canonical Android color ints for gradient color indices 0–5.
 * Index mapping mirrors GradientColor and protocol/colors.md:
 *   0  light yellow  ( 0–  2%)
 *   1  yellow        ( 2–  4%)
 *   2  dark yellow   ( 4–  6%)
 *   3  orange        ( 6–  8%)
 *   4  dark orange   ( 8– 10%)
 *   5  red           (10%+   )
 */
public final class SegmentColorPalette {

    private SegmentColorPalette() {}

    public static final int[] COLORS = {
            Color.parseColor("#FFF176"), // 0 light yellow
            Color.parseColor("#FFEE58"), // 1 yellow
            Color.parseColor("#FFC107"), // 2 dark yellow
            Color.parseColor("#FF9800"), // 3 orange
            Color.parseColor("#FF5722"), // 4 dark orange
            Color.parseColor("#F44336"), // 5 red
    };

    /**
     * @param colorIndex 0–5 from {@link nl.paree.climbpro.domain.segment.GradientColor}
     * @return Android color int, clamped to valid range
     */
    public static int toColor(int colorIndex) {
        return COLORS[Math.max(0, Math.min(5, colorIndex))];
    }
}
```

- [ ] **Step 2: Build to verify no compile errors**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/SegmentColorPalette.java
git commit -m "feat: add SegmentColorPalette with canonical gradient colors"
```

---

### Task 2: Update `ClimbProfileView` to use `SegmentColorPalette`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbProfileView.java` (lines 28–35)

The existing `SEGMENT_COLORS` array uses green/light-green for indices 0 and 1. The canonical spec (CLAUDE.md) says light yellow and yellow. Replace the local array with a delegation to `SegmentColorPalette.COLORS`.

- [ ] **Step 1: Replace `SEGMENT_COLORS` in `ClimbProfileView.java`**

Remove the existing array (lines 28–35):
```java
    private static final int[] SEGMENT_COLORS = {
            Color.parseColor("#4CAF50"),  // 0 green (0-2%)
            Color.parseColor("#8BC34A"),  // 1 light green (2-4%)
            Color.parseColor("#FFEB3B"),  // 2 yellow (4-6%)
            Color.parseColor("#FF9800"),  // 3 orange (6-8%)
            Color.parseColor("#FF5722"),  // 4 dark orange (8-10%)
            Color.parseColor("#F44336"),  // 5 red (10%+)
    };
```

Replace with:
```java
    private static final int[] SEGMENT_COLORS = SegmentColorPalette.COLORS;
```

Both classes are in the same package (`nl.paree.climbpro.ui.climbs`), so no import is needed.

- [ ] **Step 2: Build to verify no compile errors**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbProfileView.java
git commit -m "refactor: ClimbProfileView uses SegmentColorPalette (fixes incorrect green colors)"
```

---

### Task 3: Draw per-segment colored polylines in `tryDrawMap()`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java` (method `tryDrawMap()`, lines 122–160)

**Current behavior:** lines 139–154 collect all climb points into one list, draw one orange `Polyline`.

**New behavior:** iterate over `loadedClimb.segments`, calculate each segment's distance window, collect route points in that window, draw one `Polyline` per segment colored by `SegmentColorPalette.toColor(seg.colorIndex)`.

**Key details:**
- `loadedRoute.distances` is `double[]`; `loadedClimb.startDistance` and `StoredSegment.distance` are `int` — widening cast happens automatically.
- To avoid visual gaps between adjacent segment polylines, include the last point of the previous segment as the first point of the next segment's polyline (`prevSegLastPoint`).
- Guard `loadedClimb.segments != null` (null on very old stored routes).
- Keep tracking `allClimbPoints` for the zoom-to-bounding-box at the end.
- The `Color` import stays because `Color.GRAY` is still used on line 135 for the route polyline.

- [ ] **Step 1: Add `StoredSegment` import to `ClimbDetailActivity.java`**

Find the existing imports block and add:
```java
import nl.paree.climbpro.data.route.StoredSegment;
```

- [ ] **Step 2: Replace the single orange climb polyline block in `tryDrawMap()`**

Find and remove these lines (current lines 139–155):
```java
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
```

Replace with:
```java
        binding.mapView.getOverlays().clear();
        binding.mapView.getOverlays().add(routeLine);

        // Per-segment colored polylines on the climb portion
        List<GeoPoint> allClimbPoints = new ArrayList<>();
        GeoPoint prevSegLastPoint = null;
        double segBoundary = loadedClimb.startDistance;

        if (loadedClimb.segments != null) {
            for (StoredSegment seg : loadedClimb.segments) {
                double segStart = segBoundary;
                double segEnd   = segBoundary + seg.distance;

                List<GeoPoint> segPoints = new ArrayList<>();
                if (prevSegLastPoint != null) segPoints.add(prevSegLastPoint);

                for (int i = 0; i < loadedRoute.distances.length; i++) {
                    if (loadedRoute.distances[i] >= segStart
                            && loadedRoute.distances[i] <= segEnd) {
                        GeoPoint p = new GeoPoint(loadedRoute.lats[i], loadedRoute.lons[i]);
                        segPoints.add(p);
                        allClimbPoints.add(p);
                    }
                }

                if (segPoints.size() >= 2) {
                    Polyline segLine = new Polyline();
                    segLine.setColor(SegmentColorPalette.toColor(seg.colorIndex));
                    segLine.setWidth(7f);
                    segLine.setPoints(segPoints);
                    binding.mapView.getOverlays().add(segLine);
                    prevSegLastPoint = segPoints.get(segPoints.size() - 1);
                }

                segBoundary = segEnd;
            }
        }

        List<GeoPoint> zoomTarget = allClimbPoints.isEmpty() ? allPoints : allClimbPoints;
```

- [ ] **Step 3: Build to verify no compile errors**

```
./gradlew assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run existing tests to verify no regressions**

```
./gradlew test
```
Expected: All tests pass (no domain logic changed).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/climbs/ClimbDetailActivity.java
git commit -m "feat: color climb map segments by gradient on OSM map"
```

---

## Verification checklist (manual, on device/emulator)

After running `./gradlew assembleDebug` and installing on device:

- [ ] Open a route → tap a climb → climb detail screen appears
- [ ] OSM map shows full route in gray
- [ ] Climb portion shows multiple colored segments (light yellow through red) — NOT one flat orange line
- [ ] Steeper segments appear in warmer colors (orange/red)
- [ ] Shallower segments appear in cooler colors (yellow)
- [ ] Segments visually connect without gaps
- [ ] Map zooms to the climb bounds on load
- [ ] ClimbProfileView (bar chart above map) also shows the updated yellow-based gradient colors
- [ ] Tapping the back button works normally
