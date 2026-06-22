# Starred Flat Segments with Surface Tagging — Design

**Date:** 2026-06-22
**Branch context:** `fix/starred-segment-geometry`
**Status:** Approved for planning

## Goal

Stop discarding Strava starred segments that are too flat to be climbs. Keep them as a
new, user-curated entity the rider can tag with a surface type. Untagged
("non-specialized") starred segments are visible only in the phone app; once the rider
assigns a surface ("specialized") they also appear as their own list section in the
Garmin **widget** and are auto-pushed to the **surface datafield** when the route is set
active.

## Background (current behaviour)

- `StravaRoutesRepository.matchStarredClimbs` (around line 219) keeps a starred Strava
  segment **only** if `averageGrade/100.0 >= ClimbConstants.MIN_AVG_GRADIENT` (≥ 3%),
  promoting it to a `Climb`. The 800 m climb-minimum was already dropped for starred
  segments (product decision 2026-06-20). **Flat starred segments (< 3%) are silently
  dropped today.**
- `StravaSegmentDto` exposes a stable `long id`, `name`, `averageGrade`, `distance`,
  `startLatlng`, `endLatlng`.
- Storage: `StoredRoute.flatSegments` is **regenerated every import** by
  `FlatSegmentDetector` (auto-detected fillers between climbs) — a poor home for curated
  data. `StoredRoute.surfaceSections` is user-curated but carries no geometry and no
  Strava identity. Neither fits cleanly → we add a dedicated entity.
- Watch surfaces:
  - **Surface datafield** (`garmin-surface`): fed by `buildSurfaceSectionPayload` →
    `surfSec[]` of `{s,e,t,n?,cp}`. Auto-pushed on `SET_ACTIVE_ROUTE`
    (`WatchRequestHandler` line 112). A flat segment currently qualifies for `surfSec`
    when **named OR has a known surface**.
  - **Widget** (`garmin-widget`): `CommListener.mc` parses only `climbs[]` from the v3
    route payload (`buildRoutePayload`). It has **no** concept of standalone sections.
  - **Climb datafield** (`garmin/`): parses `climbs[]`; ignores unknown top-level keys.

## Definitions

- **Starred flat segment**: a Strava starred segment with `averageGrade < 3%` whose
  start/end can be located on the route geometry.
- **Specialized**: `surfaceType != SurfaceType.UNKNOWN` (the rider picked a surface).
  A name alone does **not** specialize it.
- **Non-specialized**: `surfaceType == UNKNOWN`. App-only; never sent to the watch.

## Data model

New POJO `nl.paree.climbpro.data.route.StoredStarredSegment`
(`@JsonIgnoreProperties(ignoreUnknown = true)`, mirroring sibling POJOs):

```java
public final class StoredStarredSegment {
    public long   stravaId;                       // stable identity across resync
    public int    startDistance;                  // m from route start
    public int    endDistance;                    // m from route start
    public int    length;                         // m
    public double startLat = Double.NaN, startLon = Double.NaN;
    public double endLat   = Double.NaN, endLon   = Double.NaN;
    public double avgGradient;                     // fraction, e.g. 0.018 = 1.8%
    public int    surfaceType = SurfaceType.UNKNOWN;
    public String name;                            // Strava segment name (re-derived each sync)
    public String userDisplayName;                 // rename; survives resync
}
```

`StoredRoute` gains:

```java
/** Strava starred segments too flat to be climbs (< 3%); user-curated, surface-taggable. */
public List<StoredStarredSegment> starredSegments;
```

## Detection (phone, sync time — offline thereafter)

`StravaRoutesRepository.matchStarredClimbs` is split into two outputs from one pass over
the starred list:

- `averageGrade ≥ 3%` and locatable → `Climb` (unchanged path; still merged via
  `ClimbMerger`).
- `averageGrade < 3%` and locatable → `StoredStarredSegment`.

Locating the span reuses the existing matching logic. `StarredSegmentLocator` today
returns a full `Climb`; we add a sibling that returns just the located span so the flat
path does not pay for segmentation/calibration it will not use:

```java
// StarredSegmentLocator
public static Span locateSpan(List<RoutePoint> route,
                              double startLat, double startLon,
                              double endLat, double endLon,
                              double maxMatchM);   // null if not on route / reversed
// Span: startDistance, endDistance, length, startLat/Lon, endLat/Lon, avgGradient
```

The existing `locate(...)` is refactored to call `locateSpan` internally so the two paths
share the nearest-index + direction checks (no behaviour change for the climb path).

`processRoute` builds `route.starredSegments` (Strava `id`, `name`, span fields,
`surfaceType = UNKNOWN`) and sets it on the `StoredRoute` before `saveRoute`.

## Persistence & resync preservation

Starred segments are re-derived from Strava on every sync, so user edits must be carried
forward. In `RouteRepository.saveRoute` (which already loads `prev` once), add
`mergePreviousStarredSegmentUserData(fresh, prev)` keyed by **`stravaId`**: copy
`surfaceType` and `userDisplayName` from the previous stored segment onto the freshly
matched one. This mirrors `mergePreviousClimbUserData` / the flat-segment preservation in
`toStoredFlatSegments`. If a segment is un-starred on Strava it disappears next sync (by
design).

New `RouteRepository` editing methods (used by the UI), keyed by `stravaId`:

```java
void setStarredSegmentSurface(String routeId, long stravaId, int surfaceType);
void updateStarredSegment(String routeId, long stravaId, int surfaceType, String name);
```

`updateStarredSegment` stores a blank/whitespace name as `null` (clears the rename),
`SurfaceType.fromInt`-clamps the surface, writes atomically, and updates the catalog
surface index. The catalog surface-set builder also folds in specialized starred-segment
surfaces so route-list surface filtering stays correct.

## App UI

`RouteDetailAdapter` gets a third view type `VIEW_TYPE_STARRED` rendering a
`StoredStarredSegment` with:
- a **★** marker (the affordance distinguishing it from auto flat segments),
- name (`userDisplayName ?? name`), length, avg gradient,
- a surface badge (`INVISIBLE` when `UNKNOWN`, matching the existing flat/segment style).

`RouteDetailViewModel.buildRouteItems` includes **all** starred segments (specialized
*and* non-specialized) interleaved by `startDistance` with climbs and flats, so every
starred segment is visible and editable in the app. This is the only surface where
non-specialized segments appear.

Tapping a starred row opens a dialog modelled on `showFlatSurfaceDialog` (name field +
surface spinner over `SURFACE_LABELS_NL`), saving via
`viewModel.updateStarredSegment(routeId, stravaId, surface, name)`.

## Wire protocol

### Surface datafield — `surfSec` (existing array, no schema change)

`buildSurfaceSectionPayload` additionally appends **specialized** starred segments
(`surfaceType != UNKNOWN`) into the merged `ranges`/`names` collection that becomes
`surfSec[]`. Their `cp` checkpoints are generated by the existing `buildCheckpoints`
using their distance span. Sorting by start distance is unchanged. Non-specialized
starred segments are skipped. (Auto flat segments keep their existing "named OR surface"
rule; this change is additive.)

### Widget — new top-level `fss` array (schema change)

`buildRoutePayload` emits a new optional top-level array of **specialized** starred
segments so the widget can list them:

```
fss:[{s:<startDist>, e:<endDist>, t:<surfaceType 0-4>, n?:<name ≤24>}, ...]
```

- Included only when `surfaceType != UNKNOWN`. Omitted entirely when none qualify.
- `n` is `userDisplayName ?? name`, emitted only when ≤ 24 chars.
- No `cp` — the widget only lists these; matching/rendering geometry is not needed there.

`protocol/schema.json`:
- add top-level optional `fss` (array, `maxItems` 32, `items` → new
  `FlatStarredSection` definition with `additionalProperties:false`, required `[s,e,t]`,
  optional `n`),
- update the file-level and `surfSec` descriptions to mention starred segments.

`protocol/examples/`: add `fss` to `route_mode_surface.json` (or a dedicated
`route_mode_starred.json`) so `ProtocolRoundTripTest` covers it; the test already
validates both examples and the **live `buildRoutePayload`/`buildSurfaceSectionPayload`
output** against the schema, so the schema and builder must land together.

## Watch (Monkey C — hand-written, updated in the same change per CLAUDE.md)

- `garmin-widget/source/CommListener.mc`: parse top-level `fss` into new `ClimbData`
  fields (count + parallel arrays for start/end/surface/name, bounded by a `MAX_*`
  constant). Reset to zero when absent so a resync clears stale entries (same discipline
  as the `surf` reset).
- `garmin-widget/source/ClimbData.mc`: add the storage fields + cap constant.
- `garmin-widget/source/RouteView.mc`: render a **separate list section** for starred
  segments (distinct from the climbs list), per the approved design.
- `garmin-surface/source/SurfaceData.mc`: no format change (starred segments arrive as
  ordinary `surfSec` entries); verify by review that the added entries parse.
- `garmin/source/CommListener.mc` (climb datafield): no change — it ignores unknown
  top-level keys.

## Edge cases

- Starred segment endpoints off the route, or traversed in reverse → `locateSpan`
  returns null → skipped (existing locator semantics).
- Zero/!negative length span → skipped.
- Strava starred-list fetch fails → existing behaviour: skip promotion this sync; no
  starred segments added. Previously stored ones persist until the next successful sync.
- Un-starred on Strava → drops out next sync (user edits lost — acceptable, by design).
- Payload budget: `fss` and the added `surfSec` entries are bounded by `maxItems` 32 and
  the existing checkpoint caps; specialized-only filtering keeps counts small.
- Resync with fewer/more starred segments → widget arrays reset before fill; phone merge
  keys by `stravaId`, so a dropped segment simply has no fresh match.

## Testing strategy

- **Detection**: `StarredSegmentLocator.locateSpan` unit tests (on-route flat segment,
  off-route, reversed, zero-length); `matchStarredClimbs` split test (≥3% → climb,
  <3% → starred segment) — extend `StravaRoutesRepositoryTest`.
- **Persistence**: `RouteRepositoryReimportTest`-style test that a resync preserves
  `surfaceType`/`userDisplayName` by `stravaId`, and that `updateStarredSegment` clears a
  blank name and clamps surface.
- **Protocol**: extend `ProtocolRoundTripTest`/`SurfaceSectionPayloadTest` and add a
  `ClimbPayloadBuilder` test asserting `fss` contains only specialized segments, omits
  when none, and that `surfSec` includes specialized starred segments with checkpoints.
- **UI**: `RouteDetailViewModel` test that `buildRouteItems` includes both specialized
  and non-specialized starred segments, ordered by `startDistance`.
- **Monkey C**: review-only (no JVM harness), per CLAUDE.md.

## Documentation

Update `Documentation/ARCHITECTURE.md` (new entity + two-surface data flow) and
`README.md` (feature description) in the same change, per the user's global instructions
and CLAUDE.md.

## Out of scope

- Showing non-specialized segments anywhere on the watch.
- Manual creation of starred flat segments (these come only from Strava stars).
- Per-segment manual "send to watch" toggle (specialization via surface is the gate).
- Radius mode (`buildRadiusPayload`) — unaffected.
```

