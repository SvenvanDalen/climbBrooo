---
name: flat-segment-surface-type
description: Surface type annotation for flat segments (non-climb stretches) between climbs — phone-only, manual override per segment, auto-detected at import
metadata:
  type: project
---

# Surface Type for Flat Segments — Design

## Summary

Add surface type to the flat stretches between climbs on a route. A "flat segment" is every non-climb section of the route (including descents) — so a route becomes an alternating sequence of flat segments and climbs. Surface type is auto-detected from GPX/Strava metadata at route level (same logic already used for climbs). Users can override surface type per flat segment in RouteDetailActivity. No changes to the wire protocol or Garmin watch — this feature is phone-only.

---

## 1. What is a Flat Segment

A flat segment is every contiguous stretch of a route that is not covered by a detected climb. This includes:
- Stretches before the first climb
- Stretches between two consecutive climbs
- Stretches after the last climb

Each stretch becomes exactly one `FlatSegment` object. There is no minimum-length filter — even a short connecting section gets a flat segment entry (with surface type UNKNOWN as default until set).

A route with no climbs has one flat segment covering the entire route.

---

## 2. Data Model

### New domain class: `FlatSegment.java`

```java
package nl.paree.climbpro.domain.segment;

public final class FlatSegment {
    public final int startDistance; // metres from route start
    public final int endDistance;
    public final int length;        // endDistance - startDistance
    public final int surfaceType;   // SurfaceType constant

    public FlatSegment(int startDistance, int endDistance, int length, int surfaceType) {
        this.startDistance = startDistance;
        this.endDistance = endDistance;
        this.length = length;
        this.surfaceType = surfaceType;
    }
}
```

### New data class: `StoredFlatSegment.java`

```java
package nl.paree.climbpro.data.route;

public class StoredFlatSegment {
    public int startDistance;
    public int endDistance;
    public int length;
    public int surfaceType = 5; // SurfaceType.UNKNOWN
    public double startLat = Double.NaN;
    public double startLon = Double.NaN;
    public double endLat   = Double.NaN;
    public double endLon   = Double.NaN;
}
```

Coordinates are extracted from the route point arrays when the route is saved (`RouteRepository.saveRoute()`). They are always set — NaN only when the route has no point data, which should not happen in practice.

### StoredRoute changes

Add `public List<StoredFlatSegment> flatSegments = new ArrayList<>();` to `StoredRoute.java`. Serialised/deserialised by the existing Gson-based `RouteRepository` automatically.

### catalog.json

No new field. The existing `surfaceTypes` int-set on each catalog entry already represents all surface types across the whole route. `RouteRepository` extends the collection logic to also include flat segment surface types when writing the catalog. No schema change needed.

---

## 3. Flat Segment Detection

New utility: `FlatSegmentDetector.java` in `nl.paree.climbpro.domain.segment`.

```java
public static List<FlatSegment> detect(int routeLengthMetres, List<Climb> climbs, int defaultSurfaceType)
```

Algorithm:
1. Sort climbs by startDistance.
2. Walk the gaps: `[0 → climb1.start]`, `[climb1.end → climb2.start]`, ..., `[lastClimb.end → routeLength]`.
3. Each gap with length > 0 becomes a `FlatSegment` with `surfaceType = defaultSurfaceType`.
4. Gaps of length 0 are skipped (climbs are perfectly adjacent — rare but possible).

Called from `RouteRepository` when a route is first imported or re-synced, after `ClimbDetector.detect()`.

**Resync protection**: on re-import, flat segments are re-detected from scratch. Existing `StoredFlatSegment` surface type overrides are preserved by matching on `(startDistance, endDistance)`. If a gap has an existing stored flat segment at the same position, its `surfaceType` is carried over (unless it is UNKNOWN, in which case the new auto-detected value is used — same rule as climbs).

---

## 4. Auto-Detection of Surface Type

Reuses the existing `SurfaceTypeDetector`:
- `SurfaceTypeDetector.detectFromGpxBytes(byte[])` → one surface type for the whole route
- `SurfaceTypeDetector.detectFromStravaSubType(int)` → same

This route-level type is passed as `defaultSurfaceType` to `FlatSegmentDetector.detect()`. All flat segments on the route start with this value. If both GPX and Strava are available and disagree, Strava wins (same rule as climbs).

---

## 5. Android UI

### RouteDetailActivity — interleaved list

The route detail screen currently shows a list of climbs. Replace it with an interleaved RecyclerView that alternates flat segment rows and climb rows:

```
┌─────────────────────────────────────┐
│ ━━ 12.3 km vlak  [A]           ⋮   │  ← flat segment row
│ ▲  Klim 1 · 2.1 km · 8%        →   │  ← existing climb row (unchanged)
│ ━━ 4.7 km vlak   [G]           ⋮   │  ← flat segment row
│ ▲  Klim 2 · 1.4 km · 5%        →   │  ← existing climb row
│ ━━ 8.1 km vlak   [A]           ⋮   │  ← flat segment row
└─────────────────────────────────────┘
```

**Flat segment row** shows:
- Distance (km, one decimal)
- Surface badge: `[A]`, `[G]`, `[D]`, `[K]`, `[M]` — or no badge if UNKNOWN

**Interactions:**
- **Short tap** → zooms the route map to that flat segment's bounding box (startLat/startLon to endLat/endLon), highlighting it in green on the map.
- **Long press** → surface type picker dialog.

**Surface type picker**: same AlertDialog with single-choice list used in ClimbDetail:
```
Oppervlaktype voor vlak segment
○ Asfalt
● Gravel        ← currently selected
○ Onverhard
○ Kasseien
○ Mixed
[ Opslaan ]  [ Annuleer ]
```

After saving: `RouteRepository` updates the stored flat segment, updates `catalog.json` `surfaceTypes`, `RouteDetailViewModel` reloads, and sync is triggered.

### RouteListActivity — filter chips (no change)

The existing filter chips already read `surfaceTypes` from the catalog entry. Because flat segment surface types are now included in that set, routes with gravel flat sections will appear under the Gravel chip automatically — no UI change needed.

---

## 6. RecyclerView Adapter

A single `RouteDetailAdapter` using multiple view types:
- `VIEW_TYPE_FLAT = 0` → inflates a new `item_flat_segment.xml`
- `VIEW_TYPE_CLIMB = 1` → inflates existing `item_climb.xml`

The adapter is backed by a `List<Object>` (mixed `FlatSegment` / `Climb` objects) assembled by `RouteDetailViewModel` in the correct order.

---

## 7. ViewModel Changes

`RouteDetailViewModel` currently exposes a `LiveData<List<StoredClimb>>`. Change to expose `LiveData<List<Object>>` (or a sealed type) that interleaves `StoredFlatSegment` and `StoredClimb` objects in route order.

Add a new action method:
```java
void setFlatSegmentSurface(StoredFlatSegment segment, int surfaceType)
```
This calls `RouteRepository` to persist the change, then triggers a sync.

---

## 8. No Protocol/Watch Changes

This feature is phone-only. The wire payload, `protocol/schema.json`, Monkey C source, and all Garmin widget code are untouched. Flat segments are not sent to the watch.

---

## 9. Edge Cases

- **Route with no climbs**: one flat segment covering the entire route length. Displays as a single flat row in RouteDetail.
- **Adjacent climbs (zero gap)**: no flat segment inserted between them — gaps of length 0 are skipped.
- **Re-import after user set override**: override preserved by (startDistance, endDistance) match; only UNKNOWN values are overwritten by new auto-detection.
- **Very short flat sections** (< 100 m): shown as a flat row but distance displayed as "< 0.1 km". No minimum-length filter.
- **Route with only climbs and no flat sections**: no flat rows shown. RecyclerView only shows climb rows.
