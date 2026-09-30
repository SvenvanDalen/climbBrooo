# ARCHITECTURE.md

🤖 **AI ASSISTANT: READ THIS FILE FIRST!**

This document describes the _target_ architecture for the project. Phase 0 scaffolding is in place (see `SETUP.md` for local toolchain steps); the source spec is `../Idea.md` and operating instructions are in `../CLAUDE.md`. Treat this file as the design contract that further scaffolding must satisfy.

---

## Quick Start for AI Assistants

### Golden files

| File                              | Purpose                                                                                                  |
| --------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `../Idea.md`                      | Original product spec — domain rules, payload examples, edge cases. Source of truth for _what_ to build. |
| `ARCHITECTURE.md` (this file)     | Design contract — module boundaries, data flow, key decisions. Source of truth for _how_ to build.       |
| `../CLAUDE.md`                    | Operating instructions for Claude Code sessions — non-negotiable domain rules.                           |
| `../README.md`                    | Human-facing project intro.                                                                              |
| `SETUP.md`                        | Local toolchain installation steps (Android Studio, CIQ SDK, Strava API).                                |
| `ClaudePlans/`                    | Approved implementation plans, dated by topic.                                                           |

### Common tasks (once scaffolded)

| Task                                 | Where to look                                                                                                                |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Change climb-detection rules         | `android/.../domain/climb/ClimbDetector.java`                                                                                |
| Change segment color thresholds      | `protocol/GradientColor.*` (one place — both sides import it)                                                                |
| Change wire payload format           | Edit `protocol/schema.json` → regenerated Java POJOs flow through; update Monkey C classes by hand; bump `protocol/examples/` to match |
| Add a new sync trigger               | `android/.../sync/SyncManager.java` and the associated WorkManager worker                                                    |
| Tune watch rendering                 | `garmin/source/views/ClimbView.mc` and `NextClimbView.mc`                                                                    |
| Add a Strava data source             | `android/.../data/strava/` (repository pattern — keep the API surface narrow)                                                |
| Rename a route/climb                 | `android/.../data/route/RouteRepository.java` — persist user-supplied name separately from source data so it survives resync |
| Add a route mode (follow vs radius)  | `protocol/schema.json` (envelope) → regenerate Java POJOs; update Monkey C classes by hand; touch `android/.../service/SyncManager.java` and `garmin/source/data/` |
| Spatial climb lookup for radius mode | `garmin/source/matching/NearbyClimbs.mc` (and corresponding phone-side `domain/matching/`)                                   |

---

## Project Structure

Three top-level modules, each independently buildable, communicating only through the protocol schema.

```
Android App Sven/
├── Idea.md                        # Original product spec
├── CLAUDE.md                      # AI session instructions (root for tooling)
├── README.md                      # Repo front page (root for GitHub)
├── .gitignore
│
├── Documentation/
│   ├── ARCHITECTURE.md            # This file
│   ├── SETUP.md                   # Local toolchain steps
│   └── ClaudePlans/               # Approved implementation plans
│
├── android/                       # Android companion app (Java, MVVM)
│   ├── app/
│   │   ├── ui/                    # XML views + ViewModels (AndroidX Lifecycle)
│   │   ├── domain/                # Climb detection, segmentation, simplification
│   │   │   ├── climb/             # ClimbDetector, Climb model
│   │   │   ├── segment/           # Segmenter, SegmentColor mapper
│   │   │   ├── route/             # Route parsing (GPX/FIT), smoothing, Douglas-Peucker
│   │   │   └── matching/          # Nearest-point search, hysteresis
│   │   ├── data/                  # Repositories
│   │   │   ├── strava/            # Strava OAuth + route fetch
│   │   │   ├── route/             # JSON-file route cache (no Room)
│   │   │   └── sync/              # SyncRepository
│   │   ├── service/               # Background workers, foreground sync service
│   │   └── connectiq/             # Garmin Connect IQ Communications wrapper
│   └── build.gradle
│
├── garmin/                        # Connect IQ datafield (Monkey C)
│   ├── source/
│   │   ├── App.mc
│   │   ├── views/                 # ClimbView, NextClimbView
│   │   ├── data/                  # Payload decoder, in-memory climb cache
│   │   ├── matching/              # On-watch GPS-to-route matching
│   │   └── audio/                 # Vibration + tone trigger
│   ├── resources/
│   ├── monkey.jungle
│   └── manifest.xml
│
├── garmin-onboard/                # ClimbPro Onboard watch app (Monkey C, on-watch parsing)
│   ├── source/
│   │   ├── App.mc
│   │   ├── RouteParser.mc         # Ports smoothing, detection, trimming, segmentation from Android domain
│   │   ├── RawRouteStore.mc       # Stores raw points from phone, computes distances
│   │   ├── views/                 # OnboardView (5 km terrain window)
│   │   ├── matching/              # Full-polyline nearest-point matching with hysteresis
│   │   └── audio/                 # Climb-start alert
│   ├── resources/
│   ├── test/                      # End-to-end detection + terrain-window tests
│   ├── monkey-test.jungle
│   ├── monkey.jungle
│   └── manifest.xml
│
└── protocol/                      # Shared wire-format spec + codegen sources
    ├── schema.json                # Canonical JSON Schema — source of truth for v3 packed payload format
    ├── schema.md                  # Human-readable notes on schema (rationale, byte budget, change log)
    ├── raw-route.md               # Raw geometry protocol for garmin-onboard (push-only)
    ├── examples/                  # Reference payloads used by round-trip tests on both sides
    └── colors.md                  # Gradient → color table (single source of truth)
```

### Module dependencies (strict)

```
android ──────► protocol ◄────────── garmin
               (v3 packed payload)      garmin-widget
                                        garmin-surface

android ──────► protocol/raw-route.md ◄── garmin-onboard
               (raw geometry, push-only)
```

`android` and `garmin*` both depend on `protocol`. They **never** depend on each other directly. Any cross-side concept (payload shape, color thresholds, climb-detection constants) lives in `protocol`. The onboard module receives raw geometry over a separate push-only channel documented in `protocol/raw-route.md`; the watch never requests or lists routes in this mode.

### Entry points

- **Android**: `MainActivity` → ViewModel → Repository. Background sync entry: `SyncWorker` (WorkManager).
- **Garmin**: `App.mc` `onStart` boots the datafield; `View.mc` `onUpdate` is the rendering hot path.

---

## Operating modes

The watch operates in one of two modes at a time. The phone tells the watch which mode is active via the sync envelope; the watch's UI and matching logic branch accordingly.

### Route-follow mode

User has selected a route. The phone syncs the route's climbs (with distance-along-route anchors); the watch displays current climb and previews next climb based on **progress along the route**. This is the default mode and matches the original `Idea.md` flow.

### Radius mode

No active route. The watch holds a small index of climbs near the user, each anchored by **start coordinate (lat/lon)**, and continuously checks whether the current GPS position is within a configurable radius of any known climb start. When the user enters a climb, that climb becomes active and the same current-climb view renders — but "progress along route" becomes "progress along this climb only" (matched against the climb's own polyline).

This mode is architecturally distinct:

- The payload includes `mode: "radius"` and each climb carries a `startLat`/`startLon` (route-follow climbs don't need these).
- Phone-side, climbs are sourced from a catalog (detected across all imported routes), not from a single selected route.
- Watch-side, matching is "am I near any climb?" not "where am I on the route?"

Both modes share the climb/segment data model — only the envelope and the matching strategy differ.

## Communication & Data Flow

### Inbound (route into system)

```
Strava API ──┐
GPX file ────┼──► Android Repository ──► Domain: parse → smooth → simplify → detect climbs → trim false flat → segment
FIT file ────┘                                                                       │
Garmin course ──► Connect IQ event ──────────────────────────────────────────────────┤
                                                                                     ▼
                                                                          Compact payload (protocol)
                                                                                     │
                                                                                     ▼
                                                            Connect IQ Communications API
                                                                                     │
                                                                                     ▼
                                                                          Garmin datafield cache
```

### Activity-time (on watch, no phone needed)

```
GPS tick ──► axis = chooseAxis(elapsedDistance, rtl − distanceToDestination)
                    (trusted nav distance | odometer)
                    │
                    ├──► nearest-point search ──► hysteresis filter ──► progress update
                    │                                                       │
                    │                                                       ├──► within 30 m of next calib point?
                    │                                                       │      • trusted nav → validate (revoke on disagreement)
                    │                                                       │      • odometer    → snap progress (GPS drift correction)
                    │                                                       │
                    │                                                       ├──► never-entered climb ridden 1 km past end & back on route? → mark skipped, advance
                    │                                                       │
                    │                                                       ├──► within 50 m of climb start? → audio/vibration (once per climb)
                    │                                                       │
                    │                                                       └──► active segment changed? → redraw
```

> **GPS calibration:** while on a climb, the datafield's `compute()` passes `currentLocation` to
> `ClimbData.checkCalibration()`. The phone embeds per-climb `calib` points (distance-from-start +
> lat/lon) in the v3 payload; when the rider passes within 30 m of the next unconsumed point, the
> watch snaps `progressInClimb` to that point's known distance, correcting accumulated `elapsedDistance`
> drift. Each calib point fires at most once (monotonic `calibIdx`).
>
> Calibration points are **a subset of the climb's segment-end positions** — `Segmenter.calibrationPoints`
> and `Segmenter.segment` walk the *same* 8%-fraction boundary grid, so every calib distance lands on a
> real segment end (never an independent equal-division grid). The subset keeps consecutive points
> ≥ `CALIBRATION_MIN_DISTANCE_M` (200 m) apart, with the final (short) segment end always included — so
> the last gap may be under 200 m. The `reSegmentClimb` path divides the climb into equal segments and
> reuses the count overload, which is aligned for the same reason.
>
> **GPS-gated climb start (per tick):** a climb does **not** become active from the odometer
> (`elapsedDistance`) alone. `ClimbData.updateProgress` only activates climb *i* once
> `climbEntered[i]` is set, which happens in `updateRouteMatch` when GPS comes within
> `APPROACH_SNAP_M` (40 m) of the climb's first calibration point — i.e. the rider is physically on
> the climb. Until then the climb is reported as *upcoming* even if the odometer has rolled past its
> start, so an off-route rider whose odometer keeps incrementing never trips the climb. Climbs that
> carry no calibration geometry (`calibCount == 0`) fall back to odometer-only activation. The
> approach/off-route check in `updateRouteMatch` runs whenever `distToNextClimb <= APPROACH_WINDOW_M`
> (including ≤ 0, the unconfirmed-overrun case) so confirmation can still occur after an odometer
> overrun. `climbEntered` is reset to false on every new payload (`CommListener`).

### Navigation-anchored distance & skip resilience (2026-06-30)

The climb datafield can match on Garmin's navigation course distance instead of
the raw odometer. The phone ships `rtl` (route total length); the watch computes
`navDist = rtl - Activity.Info.distanceToDestination` and uses it once a **length
gate** (course length ≈ `rtl`) and ongoing **calibration agreement** (navDist
within `NAV_DISAGREE_M` = 150 m of each calibration point's known absolute
distance) confirm the loaded course is the selected route. Trust is one-way: a
disagreement revokes it (`NAV_REVOKED`) and the watch falls back to the odometer
drift-correction for the rest of the ride. While trusted, calibration points only
*validate* (never shift) the climb anchor — so the watch keeps immutable
`climbStartDist0`/`climbEndDist0` alongside the working (shiftable)
`climbStartDist`/`climbEndDist`. `ClimbProView.compute` picks the axis via
`ClimbData.chooseAxis(elapsed, navDist)`.

A climb that is ridden `SKIP_MARGIN_M` (1 km) past its end without ever being
entered — and with the rider confirmed back on the route (`backOnRoute`: trusted
nav, or a later climb GPS-confirmed) — is flagged `climbSkipped` so progression
continues to the next climb. `climbSkipped` resets on every payload (along with
`climbEntered`, `calibIdx`, and `navTrust`). Radius mode is unaffected (`rtl` is
route-mode only).

### Sync semantics

- **Incremental**: only resync routes that changed (hash the source GPX/FIT, store on phone).
- **Retry**: exponential backoff. Sync failures must not block UI.
- **Resumable**: a half-synced route must be detectable and resumed, not silently treated as complete.

#### Orchestration (offline-first)

`RouteSyncWorker` (WorkManager) is thin glue; the orchestration logic lives in the
pure, unit-tested `service/SyncOrchestrator` so it can be tested without the Garmin SDK.
One sync round runs in this order:

1. **Strava pull first** — fetch + persist routes to disk, **independent of the watch
   connection**. This is the offline-first guarantee: routes download and appear in the
   app even when no watch is connected. (Previously the watch-connection wait gated the
   whole worker, so nothing downloaded without a watch — fixed.)
2. **Report progress** — the orchestrator signals "pull complete" via
   `WorkManager.setProgressAsync` (`RouteSyncWorker.KEY_PULL_DONE` / `KEY_CHANGED`) so the
   UI can refresh immediately.
3. **Opportunistic watch send** — build the mode-specific payload and send it to the watch.
   This step may fail or be skipped (no watch, nothing to send) **without** failing the
   sync. The worker only returns `Result.retry()` for a transient Strava-pull failure, a
   transient payload-build failure, or a failed send to an *available* watch — never merely
   because the watch is absent.

**Strava rate limiting (429):** every Strava call in the pull (`listRoutes`,
`export_gpx`, `segments/starred`) goes through `StravaRoutesRepository.executeWithRetry`,
which on an HTTP 429 honors the `Retry-After` header (delta-seconds), waits, and retries
up to `MAX_RETRY_ATTEMPTS` times. If the server asks for longer than `MAX_RETRY_WAIT_MS`
(60 s) the call gives up and returns the 429 so the next scheduled sync resumes the work,
rather than blocking the worker for a full rate-limit window. The wait is performed via an
injectable `Sleeper` so unit tests verify the backoff timing without actually sleeping.

#### Background message delivery (and the failed binder-service experiment)

Watch → phone requests require a live, registered ClimbPro process: incoming
CIQ messages arrive on a **runtime-registered broadcast receiver** inside the
SDK (`registerForAppEvents(device, app, listener)`), which dies with the
process. Three mechanisms make that as robust as the platform allows:

- **Keep-alive wake-ups** — `CiqRebindWorker` (scheduled by `RebindScheduler`:
  periodic 6 h + one-shot from `BootCompletedReceiver`) starts the app process
  in the background; `Application.onCreate` then reconnects and re-registers,
  and the cached process keeps answering watch requests for as long as Android
  keeps it around. It is deliberately **unconstrained** WorkManager work: the
  "charging + unmetered" rule applies to route *sync* (`RouteSyncWorker`), not
  to this no-network, sub-30-second refresh.
- **Re-prime on every reconnect** — `HELLO` is sent on **every** (re)established
  connection (the send is re-armed whenever the device reports DISCONNECTED),
  so a Bluetooth drop mid-ride ends with a fresh GCM message binding and a
  widget route-list refresh, not just the first connect of the process.
- **Watch-side retry** — the widget's sync screen retransmits `LIST_ROUTES` at
  3 s and 6 s (`SyncRetryPolicy`) inside its 10 s window, covering a phone whose
  process needs a few seconds to (re)connect.

> **Do not resurrect `registerAppToUseBinderService`.** The vendored SDK offers
> binder-service delivery (GCM binds into `IQGarminBindingService`, which would
> wake a dead process). Verified on a real device (GCM 5.26.1, FR255M,
> 2026-07-17): GCM *accepts* the registration but never delivers anything
> through it — no messages, no device-status events — while the SDK
> simultaneously unregisters the broadcast receiver, leaving the app fully
> deaf. The attempt and revert are documented in
> `docs/superpowers/plans/2026-07-17-always-connectable-watch-sync.md`.

#### UI refresh & feedback

The manual "Sync now" action enqueues a uniquely-named work request
(`SyncScheduler.UNIQUE_MANUAL_SYNC`, `ExistingWorkPolicy.REPLACE`). `RouteListActivity`
observes `SyncScheduler.manualSyncInfo(...)` and reloads the catalog as soon as the pull
reports done (and again on terminal success), showing a toast with the number of
new/changed routes. New routes therefore appear without leaving the screen.

#### Route-list ordering

Sort order is user-selectable via the "Sorteer" menu and persisted in
`SharedPreferences` (`route_sort_mode`). The pure `ui/routes/RouteSorting` helper supports
import-time ascending (newest at bottom — default), import-time descending, and name A–Z.
`RouteListViewModel` combines this with the existing surface-type filter.

---

## Critical File Locations

Once scaffolded, these files will be the highest-traffic edits:

| Concern                            | File (planned)                                                                    |
| ---------------------------------- | --------------------------------------------------------------------------------- |
| Climb detection rules (≥800m, ≥3%) | `android/app/src/main/.../domain/climb/ClimbDetector.java`                        |
| Segmentation (8% slices)           | `android/app/src/main/.../domain/segment/Segmenter.java`                          |
| Color mapping                      | `protocol/colors.md` (spec) + generated `GradientColor.java` / `GradientColor.mc` |
| Wire format                        | `protocol/schema.json` (canonical) → generated `ClimbPayload.java` + hand-written `ClimbPayload.mc` |
| Sync orchestration                 | `android/app/src/main/.../service/SyncOrchestrator.java` (logic) + `RouteSyncWorker.java` (WorkManager glue) |
| Watch render loop                  | `garmin/source/views/ClimbView.mc`                                                |
| GPS matching (watch)               | `garmin/source/matching/RouteMatcher.mc`                                          |

---

## Domain Model & Business Logic

Key types — names should match across modules where possible.

| Type           | Purpose                                                                                                                              | Lives in                                                             |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------- |
| `Route`        | Parsed, smoothed, simplified route (points with distance + elevation)                                                                | Android domain only — never sent to watch                            |
| `Climb`        | Detected climb: start/end distance, length, elevation gain, avg gradient, segment list, optional `startLat`/`startLon` (radius mode) | Both (compact form on watch)                                         |
| `Segment`      | 8%-of-climb slice: gradient, elevation gain, distance, color index                                                                   | Both                                                                 |
| `ClimbPayload` | Wire envelope: `mode` (route/radius) + routeId (route mode) + Climb[]                                                                | Protocol                                                             |
| `Position`     | GPS sample matched to route distance (or to active climb in radius mode)                                                             | Both (watch maintains it live)                                       |
| `RouteHash`    | Content hash of source file, used for incremental sync                                                                               | Android only                                                         |
| `UserMetadata` | User-supplied display name + custom notes/tags on a route or climb                                                                   | Android only (name optionally synced to watch within payload budget) |

**Invariants**:

- A `Climb` has length ≥ 800 m and avg gradient ≥ 3%. If either fails, it is not a climb.
- A detected `Climb` has its leading/trailing **vals plat** (false flat: a contiguous stretch averaging < 2% over ≥ 200 m) trimmed off, but is never trimmed below the 800 m minimum. After trimming, start/end distance and `startLat`/`startLon` reflect the tighter boundaries. See `domain/climb/ClimbTrimmer.java`.
- A `Climb`'s segments cover the full climb with no gaps or overlap. `sum(segment.distance) == climb.length` (within rounding).
- Segment count = `ceil(1 / 0.08) = 13` _unless_ the last segment is short — keep the segmenter honest about the tail.
- Calibration points are a **subset of the segment-end positions** (same 8%-fraction grid), spaced ≥ 200 m apart with the final segment end always included. `Segmenter.calibrationPoints(climbPoints)` and `Segmenter.segment(climbPoints)` must walk identical boundaries.

### Route bucket-list status (issue #158)

`StoredRoute.rideStatus` holds a user-set route status: `null` (geen status — also the
value for route files written before the field existed), `"WANT_TO_RIDE"` or `"RIDDEN"`
(constants + Dutch labels in `data/route/RouteRideStatus`). It is mirrored to
`RouteCatalogEntry.rideStatus` because the route list reads only `catalog.json`; every
catalog write path (`toCatalogEntry` in `saveRoute`, the stub in
`rebuildCatalogSurfaceTypes`, and `setRideStatus`) copies it. `saveRoute` carries the
previous value forward when the incoming route shell has none, so a Strava resync or
re-import never resets it. The status is purely manual (no automatic change from climb
attempts), phone-only and never part of the wire payload. The route list filters on it
via the pure `ui/routes/RouteStatusFilter` (Alle / Wil ik rijden / Gereden), applied
alongside the surface filter and before sorting.

### Reverse a route (issue #201)

The route detail button "Omgekeerde richting" creates the opposite-direction variant of a
stored route as a **new** route — the original is never modified. The pure
`domain/route/RouteReverser` flips the point order and recomputes cumulative distances
(elevations kept); `data/route/RouteReverseService` then re-runs `ClimbDetector` (detect →
false-flat trim → 8 % segmentation) on the reversed points and stores the result through the
normal `RouteRepository.saveRoute`, so the descents of the original become the climbs of the
reverse. Stored points are already smoothed/simplified, so smoothing is not re-applied. The
reversed route gets the deterministic id `rev_<id>` and default name `"<name> (omgekeerd)"`;
if that id already exists it is reopened instead of duplicated, and reversing a `rev_` route
maps back to the original. Climb renames, notes, ride status, surface sections and starred
segments do not carry over (they describe different climbs/stretches). Phone-only; the result
is an ordinary route payload, no wire-format change.

### Whole-route elevation profile (issue #207)

The route detail screen shows the elevation profile of the entire route above the pacing
passport, with every climb highlighted in its segment gradient colors. Phone-only, no
wire-format change. `ui/routes/RouteElevationProfile` (pure Java, unit-tested) prepares the
drawable series from the stored route geometry (`StoredRoute.distances`/`elevations`, falling
back to haversine distances from `lats`/`lons`): NaN and zero elevation samples are skipped,
long routes are downsampled to per-bucket min/max pairs (`DEFAULT_MAX_BUCKETS` = 300) so
summits survive, and each climb becomes colored bands — one per `StoredSegment` using its
`colorIndex`, or one band from `GradientColor.forGradient(avgGradient)` when a climb has no
segments — clamped to the route and palette. `RouteDetailViewModel` builds it on its executor;
the thin `RouteElevationProfileView` only scales and paints, coloring bands via
`SegmentColorPalette`. Routes without usable elevation show "Geen hoogtegegevens" instead.

### Joining two routes (issue #204)

"Samenvoegen met…" on the route detail screen saves the current route (A) followed by a
picked route (B) as a **new, ordinary route**; both originals stay untouched. The pure
`domain/route/RouteJoiner` concatenates the stored point lists and recomputes cumulative
distance from A's first point. If B starts within 5 m of A's end the duplicate joint point
is dropped; otherwise the gap is bridged by a straight line that counts towards the
distance, and a gap over 250 m (`LARGE_GAP_WARNING_M`) makes the UI ask for confirmation
first — no road geometry is invented. Missing elevation stays `NaN`. `data/route/RouteJoinService`
then re-runs `ClimbDetector` (detect + trim + segment) over the joined points — not the
smoother/simplifier, because stored geometry was already smoothed and simplified at import —
and saves via `RouteRepository.saveRoute` under a `join_` route id with the default name
"A + B" (renamable like any route). Climbs spanning the joint are detected as one climb.
Phone-only; no wire-format change. Note the joined route's climbs duplicate those of its
source routes in the catalog, like any overlapping import.

### Automatic Strava segment matching (issue #35, phone-only)

Strava sync also matches each new or changed route against public Strava segments, not just
the athlete's starred ones. `segments/explore` returns at most the top 10 segments inside a
bounding box, so `domain/climb/SegmentExploreTiler` splits the route by distance into
~10 km stretches, each with its own box padded by 300 m (at most 8 tiles; longer routes get
longer tiles). `StravaRoutesRepository.exploreSegments` asks each tile (`activity_type=riding`),
de-duplicates by id and maps the response's `avg_grade` onto `StravaSegmentDto`
(`StravaSegmentExploreDto`). These segments go through the same `StarredSegmentLocator` as
starred ones (50 m endpoint match, direction check), but unlike starred segments they must
meet the full climb rule: `≥ 3 %` **and** `≥ 800 m`, since nobody hand-picked them.
`ClimbMerger.longestNonOverlapping` keeps the longest when Strava has a full-climb segment
plus shorter pieces inside it. The result is merged over the detected climbs (the segment's
bounds and name win, with no false-flat trim, like starred segments), and starred segments
are merged after that, so a starred segment still beats a public one; a segment that is
both is matched only as starred.

**Rate-limit budget.** Explore costs up to 8 calls per route, which a full resync on a fresh
phone can't afford next to the GPX downloads. After every explore response,
`StravaRateLimit.nearLimit` checks the rate-limit headers. When the limit is near, or on a
429, exploring stops for the rest of that sync and the route is saved with
`StoredRoute.stravaSegmentsExplored = false`. The skip check reprocesses an unchanged route
whose flag is not `true` (including `null` on routes stored before this feature), but only
while the current sync still has budget. So the backlog drains over later syncs, and
unchanged routes are never downloaded again just to wait for budget. Other explore failures
(network, a 4xx) count as done, so a dead endpoint doesn't trigger a GPX download every sync.
No wire-format change: the matched climbs go through the usual segmentation and payload.

### Flat starred Strava segments with surface tagging (2026-06-22)

A Strava starred segment whose Strava `average_grade` is **< 3%** (too flat to qualify as
a climb) is not promoted to a climb. Instead it is stored as a `StoredStarredSegment` on
`StoredRoute.starredSegments` — a separate, user-curated entity:

```
StoredStarredSegment {
    long   stravaId          // stable Strava ID; re-sync keyed on this
    int    startDistance     // metres from route start (integer)
    int    endDistance
    int    length
    double startLat/Lon, endLat/Lon   // for future use
    double avgGradient       // Strava's value
    int    surfaceType       // SurfaceType.UNKNOWN by default (= non-specialized)
    String name              // Strava segment name; re-derived each sync
    String userDisplayName   // optional user rename; survives resync
}
```

**Matching.** `StarredSegmentLocator.locateSpan` places each starred segment's
`start_latlng`/`end_latlng` onto the simplified route geometry (within
`ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M`). A segment qualifies as "on the route" iff
both endpoints can be placed. Segments with `average_grade >= 3%` are handled by the
existing `StarredSegmentLocator` / `ClimbMerger` path (promoted to climbs); only the
sub-3% remainder is stored as `StoredStarredSegment`.

**Resync preservation.** On every Strava re-sync `RouteRepository.saveRoute` matches
incoming starred segments by `stravaId` and preserves `surfaceType` and `userDisplayName`
from any existing entry, so user edits survive resync.

**Specialization rule.** A starred flat segment is **specialized** when its `surfaceType`
is anything other than `SurfaceType.UNKNOWN`. Only specialized segments cross to the
watch; non-specialized are phone-only.

**Phone UI.** `RouteDetailActivity` lists **all** starred segments (both specialized and
non-specialized) with a ★ indicator. Tapping one opens a surface-tag dialog
(`RouteDetailAdapter.OnStarredClickListener`), calling
`RouteRepository.updateStarredSegment(routeId, stravaId, surfaceType, userDisplayName)`.

**Two watch paths for specialized segments:**

1. **Surface datafield (`surfSec`)** — `ClimbPayloadBuilder.buildSurfaceSectionPayload`
   merges specialized starred segments (where `surfaceType != UNKNOWN`) into the same
   `surfSec` array as `StoredSurfaceSection`s and qualifying `StoredFlatSegment`s, sorted
   by start distance. Each entry carries `{s,e,t,n?,cp:[...]}` with GPS checkpoints. This
   payload is auto-pushed to `ConnectIqAppId.SURFACE_FIELD` by `WatchRequestHandler` on
   every `SET_ACTIVE_ROUTE` (the same path used for all surface sections).

2. **Browse widget (`fss`)** — `ClimbPayloadBuilder.buildRoutePayload` appends a top-level
   `fss` array containing only specialized starred segments:
   ```json
   "fss": [{ "s": 3200, "e": 3600, "t": 1, "n": "Gravel ster" }, ...]
   ```
   Keys: `s` (start distance, m), `e` (end distance, m), `t` (surface type 0–4), `n`
   (optional name ≤ 24 chars). `fss` is **omitted** when no segment is specialized;
   `t = 5` (UNKNOWN) is never emitted. In the browse widget (`garmin-widget/`) the
   `CommListener.mc` parser reads `fss` into `ClimbData.flatStarred*` arrays;
   `ClimbListView.mc` renders them as rows after the climb rows in the route detail list,
   showing name and surface initial (`A/G/D/K/M`). Rows fall back to "Ster N" when
   unnamed.

The key distinctions:
- `fss` (browse widget): specialized starred segments only, no checkpoints, no `t=5`.
- `surfSec` (surface datafield): all surface/flat entities including specialized starred segments, with checkpoints.
- Non-specialized starred segments: phone-only, never in either payload.

`protocol/examples/route_mode_starred.json` is the canonical `fss` wire sample and is
validated by `ProtocolRoundTripTest`.

### Starred Strava segments as climbs (2026-06-20)

When a route is synced from Strava, any **starred** Strava segment that lies on the
route and has an average gradient ≥ 3% is promoted to a climb, even if it is shorter
than the normal 800 m minimum (the ≥ 3% rule is kept; the length floor is dropped).

The phone fetches the athlete's starred segments **once per sync** (`GET
/segments/starred`, paginated). Each starred segment already carries its own
`average_grade`, `start_latlng` and `end_latlng`, so routes are matched **locally with
no per-route network call**: for each route, `matchStarredClimbs` runs every qualifying
starred segment through `domain/climb/StarredSegmentLocator`, which places the segment's
start/end onto the simplified route geometry (within
`ClimbConstants.STARRED_SEGMENT_MATCH_MAX_M` of a route point) — a segment is "on the
route" iff it can be placed there. Matches are merged into the detected climbs
(`domain/climb/ClimbMerger`); on overlap the **starred segment's bounds replace** the
detected climb. The 3% gate uses Strava's authoritative `average_grade`; **no false-flat
trim** is applied; the climb is **named** after the segment (a user's manual rename
still survives resync via the existing climb-user-data merge). The fetch is
**best-effort** — any network failure falls back to the normally-detected climbs
(offline-first). Applies to **Strava-synced routes only**; manual GPX imports have no
segment data. Promoted climbs are ordinary `Climb` objects, so they serialise through
the existing wire path unchanged.

> **Why no `GET /routes/{id}` call:** an earlier version fetched each route's segment
> list per route to find its starred segments. That doubled Strava API calls during a
> full re-sync (e.g. on a fresh phone, where every route is "new"), tripping Strava's
> ~100-req/15-min rate limit and starving the essential `export_gpx` downloads — routes
> silently failed to sync. Matching by geometry from the single starred-list fetch keeps
> sync call volume at the known-good baseline.

### Custom surface sections

Beyond auto-detected per-climb-segment and per-flat-segment surface types, the user can
manually mark an **arbitrary stretch** of a route with a surface type and an optional name.
These live in `StoredRoute.surfaceSections` (`StoredSurfaceSection`: `startDistance`,
`endDistance`, `surfaceType`, `name`) and are managed from the route detail screen
(`RouteDetailActivity` → "Ondergrond-stukken"). Flat segments (`StoredFlatSegment`) also
carry a `name` field; both are set and preserved by `RouteRepository` (atomic write,
re-import keyed by `startDistance`).

Surface sections are now serialised to the watch via the `surfSec` object array
(`{s,e,t,n?,cp:[dist,latInt,lonInt,…]}`). Qualifying flat segments (named or with a
non-UNKNOWN surface) are merged into the same list and sorted by start distance.
Untouched flat segments are still skipped. Sections survive route re-import and
contribute to the catalog `surfaceTypes` index.

**Auto-seeding vs. user edits.** `StravaRoutesRepository` seeds per-climb-segment surface
from the Strava `sub_type` (e.g. road → asphalt) **only on first import** (`existing == null`).
On any re-sync, `RouteRepository.saveRoute` has already preserved the user's manual
per-segment surface edits (non-UNKNOWN values, remapped by `SegmentRemapper` — see below),
so the seeding step is skipped to avoid clobbering them. This upholds the "user
customisation survives resync" rule for surfaces, the same way renames are kept.

**Robust re-mapping when segment boundaries move (issue #87).** A re-import of the same
route can shift segment boundaries: the route start moves (every distance offset), a climb
is trimmed/extended differently (its 8% grid moves), or climbs appear/disappear. All
carry-over rules live in one pure class, `data/route/SegmentRemapper` (phone-only; no wire
change), applied by `RouteRepository.saveRoute`:

- **Climb matching** is one-to-one, greedy by score: exact `startDistance` with a near (or
  unknown) start coordinate first (the legacy rule, so unchanged geometry behaves exactly as
  before), then start coordinates within 150 m (survives a shifted route start); only for
  the leftovers, `[startDistance,endDistance]` overlap ≥ 50% of the longer climb, compared
  after the route-wide shift revealed by the anchored matches (survives a moved climb start).
  An old climb never feeds two fresh climbs. Matched climbs carry rename, suggested name and
  manual reference time.
- **Alignment offset**: when starts coincide geometrically but start distances differ by
  more than the start point moved (+50 m slack), the route start shifted and segment
  positions are compared after subtracting that offset; otherwise on absolute route position.
- **Position-bound segment data** (surface — it describes the road) goes to each fresh
  segment from the previous segment it overlaps most, only if that covers ≥ 50% of the fresh
  segment; else the default stays. On an identical grid this is the old index copy.
- **Grid-bound segment data** (e.g. a per-segment target time) is only meaningful for exactly
  the same boundaries: carry it by index only when `SegmentRemapper.isSameGrid` (same count,
  every aligned boundary within 10 m), otherwise drop it — never interpolate.
  `StoredClimbAttempt.segSplitSec` follows the same principle: `SegmentPrCalculator` ignores
  splits whose length differs from the current grid.
- **Flat stretches** keep name/surface by exact `startDistance`, else by ≥ 50% overlap after
  the route-wide offset (median of the matched climbs' offsets). User-drawn surface sections
  are still copied verbatim (absolute distances).

### Climb of the week (phone-only, issue #40)

Menu entry *Klim van de week* (`ui/climbs/ClimbOfTheWeekActivity`) suggests one known climb for
the current ISO week with the reasons behind it. `ClimbOfTheWeekViewModel` gathers every stored
climb (keyed by `ClimbIdentity`), the last attempt date per climb from `ClimbAttemptRepository`,
the phone's last-known location (`ui/planning/LastKnownLocation`, only when a location
permission is already granted — otherwise the distance factor is skipped) and a 7-day daily
outlook from Open-Meteo (`OpenMeteoClient.fetchDaily` / pure `domain/weather/DailyForecast`,
taken at the location or, without one, at the most recently ridden climb). The pure,
deterministic `domain/climb/ClimbOfTheWeek` skips climbs ridden in the last 14 days and — with a
location — climbs starting more than 80 km away (each filter is dropped again if it would leave
nothing), then scores the rest as a weighted mean of the available factors: freshness (never
ridden = 1, else up to 0.9 at 180 days), distance (`1 / (1 + d / 40 km)`) and weather fit (the
week's best day sets a good/mixed/poor outlook that favours hard, middling or easy climbs by
`DifficultyScoreCalculator`). Missing factors are left out of the mean, so offline or without
location the suggestion still works. A tiny jitter seeded by ISO week + climb id rotates between
otherwise equal climbs from week to week. The chosen climb is pinned in the default
SharedPreferences (`climb_of_week_pin` = `<isoWeek>|<climbId>`) so it stays the suggestion for the
whole week; a pinned climb ridden this week is shown as completed. No wire-format change.

### Hardest climbs in the region (phone-only, issue #211)

Menu entry *Zwaarste klimmen in de regio* (`ui/climbs/TopClimbsActivity`) lists the top 10
known climbs whose start lies within a radius (default: the radius-mode setting) of the phone's
last-known location or a chosen route start. Candidates come from the same catalog pre-filter
as radius mode (`RouteRepository.findNearby`); the pure `domain/climb/RegionalTopClimbs` applies
the exact haversine radius filter, dedupes the same climb across routes on its `ClimbIdentity`
key (keeping the hardest version), and ranks by `DifficultyScoreCalculator.score(gain, gradient, 0)`
— the standalone form without fatigue bonus, so a climb is judged on its own merit rather than on
its position in whichever route it was imported from. Ties break on distance, then name. Tapping
a row opens the climb detail screen. No wire-format change.

### Climb time estimate (phone-only)

The phone estimates how long each climb takes from a rider profile (FTP in
watts, rider weight, bike weight) stored in `SharedPreferences`
(`RiderProfileRepository`). The estimate is computed on demand in the
climb-detail screen (`ClimbDetailViewModel` → `LiveData<ClimbTimeEstimate>`,
recomputed in `onResume` so a changed profile is picked up) and is **not** part
of the wire payload — the watch never sees it.

The model lives in `domain.power`:

- `PowerSpeedSolver` solves the steady-state power-balance equation
  (`P = m·g·(sinθ + Crr·cosθ)·v + ½·ρ·CdA·v³`) for speed via bisection, taking
  the rolling resistance `Crr` as a parameter. Descents (net-assisting gravity)
  clamp to `PowerConstants.MAX_SPEED_MPS`.
- `SurfaceRollingResistance` maps each segment's `surfaceType` (asphalt, gravel,
  dirt, cobblestone, mixed) to its own `Crr`, so rougher surfaces are estimated
  as slower. Unknown surfaces fall back to asphalt.
- `PowerDurationModel` is a Critical-Power 2-parameter curve `P(t) = FTP + W'/t`,
  so longer climbs are ridden closer to FTP and short climbs allow a surge.
- `ClimbTimeEstimator` ties them together by fixed-point iteration: a climb's
  duration sets the sustainable power, which sets per-segment speeds (each using
  its surface `Crr`), which sum back to the duration; iterate until stable. It
  returns total and per-segment seconds, or `null` when the profile is incomplete.

Constants (CdA, air density, drivetrain efficiency, W') live in `PowerConstants`;
the per-surface `Crr` values live in `SurfaceRollingResistance`.

**Fatigue-aware climb-time estimate (phone-only).** The climb-detail screen's time estimate accounts for the effort of the whole route, not just the climb in isolation. All climbs are modelled at CP + x watts with a single shared offset x, solved by a W'-balance bisection over the entire route (`RouteAwareClimbEstimator`, fed by `RouteEffortProfileBuilder`). Non-climb stretches are ridden at the rider's configurable ride-intensity (% FTP, set in Settings), where W' recovers; the bisection keeps the largest x whose route-wide W'-balance never drops below a 10% reserve, so deeper climbs in a hard route are estimated slower and energy is implicitly reserved for what remains. When a route lacks elevation/distance data the screen falls back to the fresh per-climb `ClimbTimeEstimator`. The wire payload and the watch are unchanged.

**Climb as an indoor workout (issue #223, phone-only).** "Exporteer als Zwift-/ERG-workout" on the climb-detail screen writes the climb as a Zwift `.zwo` (power as a fraction of FTP) or an `.erg` (absolute watts over cumulative minutes) file and hands it to the share sheet through `ui/climbs/ClimbWorkoutExportHandoff`, under `cacheDir/shared_workouts/<uuid>/<climb-name>.zwo|erg`. `domain/export/ClimbWorkoutWriter` uses the *fresh* per-climb `ClimbTimeEstimator`, since indoors you start rested: one steady block per segment with the estimated segment time. Each block's power follows the gradient around the estimate's assumed power (+2 % power per +1 % gradient, clamped to ±15 %) and is rescaled so the time-weighted average stays at that power. A 10-min warm-up ramp (45→75 % FTP) comes first and a 5-min cool-down last, and each `.zwo` block shows "Segment i/n: x %". A complete rider profile is required.

**Climb repeats workout (issue #19, phone-only).** The same export dialog offers "Herhaal-klim" (.zwo or .erg), which asks for the number of repeats (2–10, default 5) and the recovery between them ("Auto" or 1–15 min). `ClimbWorkoutWriter.mainSet` expands the per-climb blocks into N passes with a recovery `Step` (`recovery = true`) between each pair and none after the last; `toZwo`/`toErg` take `repeats` + `recoverySec` overloads and one repeat produces byte-identical output to the plain export. Recovery is ridden at 50 % FTP (active recovery, Coggan Z1 < 55 %); the "Auto" duration is half the climb time rounded to whole minutes and clamped to 3–10 min — the classic 2:1 work:rest ratio for threshold repeats and roughly the time the descent back down takes. Title is "N× <klimnaam>", the description states segment count, time per pass, recovery and total session time, and the file is named `Nx_<klimnaam>.zwo|erg`. No FIT workout writer: Garmin users import the `.zwo`/`.erg` through third-party tools.

**MyWhoosh import (issue #342, phone-only).** The route list's "Route toevoegen" dialog has a "MyWhoosh-rit importeren (FIT)" option. `MyWhooshRouteReader` takes the FIT file (via `FitTrackDecoder.decodeRecords`, which now also returns `record` altitude — `enhanced_altitude` preferred — and distance) or a GPX and hands route points to the usual pipeline (cumulative distance, smoothing, `ClimbDetector`). A ride where most altitude records have GPS keeps its track. A virtual ride without positions is laid out on a straight synthetic line due north from 0,0, spaced by the FIT distance field, and flagged virtual. For those routes the 2D Douglas-Peucker `RouteSimplifier` is skipped, since it only looks at lat/lon and would collapse the straight line to its two endpoints, losing the profile. They are thinned to one point per 10 m instead, and the duplicate-climb check is skipped because every virtual route starts at 0,0. The route is saved with id prefix `mywhoosh_`, the name "MyWhoosh – <file>" and a note, and added to the "MyWhoosh" `RouteCollection` (created on first import). No wire-format change: virtual climbs sync like any other, but their coordinates never match a real position.

**MyWhoosh via Strava (issue #344, phone-only).** MyWhoosh has no public API, but it uploads every ride to Strava as a `VirtualRide` named "MyWhoosh - <route>", with fixed virtual coordinates per world. `StravaActivitiesRepository.importMyWhooshRides()` runs in `RouteSyncWorker` after the ride-archive refresh, and in `syncActivities()` before the known climbs are enumerated so the ride matches its own new climbs. It walks the archive (`MyWhooshRouteReader.isMyWhooshActivity`, newest first, at most 10 stream requests per run) and fetches `latlng,distance,altitude,time`. It then runs `MyWhooshRouteReader.fromStreams` + `detectClimbs` (the same pipeline as the FIT import) and saves through the shared `MyWhooshRouteStore` as route `mywhoosh_strava_<activityId>`. A ride is skipped when it has no climbs, or when every climb already exists within the duplicate radius (a repeat of an imported MyWhoosh route). Handled ids are kept in the `mywhoosh_imported_activity_ids` pref; 429/401/403 stop the run and 5xx is retried next time. Rides are recognised by name only, so a renamed ride or a Zwift ride is not imported.

**MyWhoosh export (issue #85, phone-only).** MyWhoosh has no custom-route import, only a web workout builder that uploads `.zwo` files, so the climb goes there as a workout. The export dialog's "MyWhoosh-workout" option calls `ClimbWorkoutWriter.toMyWhooshZwo`, which uses the same plan as the Zwift export but writes only what MyWhoosh's stricter importer handles: self-closing `Warmup`/`SteadyState`/`Cooldown` steps, power rounded to whole percent of FTP, no `<tags>` and no `textevent`s, and a name capped at 40 characters. The per-segment gradients move into the description. The file is `<klimnaam>_mywhoosh.zwo`, and the activity explains the upload route (Drive/mail → MyWhoosh website → Workouts → upload) before the share sheet opens. Plain climb only; no repeats variant.

**intervals.icu export (issue #78, phone-only, new external integration).** intervals.icu is the only training platform integrated; TrainingPeaks' API requires partner approval and is out of scope. Auth is a personal API key sent as HTTP Basic (`API_KEY:<key>`) plus an athlete id (`0` = the key's own athlete, otherwise `i<digits>`), entered in `ui/settings/IntervalsIcuSettingsActivity` with a connection test (`GET /api/v1/athlete/{id}`). `data/intervals/IntervalsIcuRepository` keeps both in EncryptedSharedPreferences (`intervals_icu_auth`), like the Strava token: excluded from Auto Backup/device transfer (`backup_rules.xml`, `data_extraction_rules.xml`), never in the local backup archive (only default prefs are), and listed as `PrivacyCategory.INTERVALS_ICU` on the privacy dashboard, where deleting clears it. The climb-detail workout dialog gains "Naar intervals.icu" and a repeat-climb variant (issue #19 dialog): the same `ClimbWorkoutWriter.toZwo` output is POSTed to `/api/v1/athlete/{id}/events` as `{category: WORKOUT, start_date_local: <date>T00:00:00, type: VirtualRide|Ride, name, description, file_contents: <zwo xml>, filename}` (`IntervalsIcuEventDto`, Retrofit + Jackson, on the view model's executor). intervals.icu has no custom-climb object, so the climb data export is minimal: the description carries the climb's PR and attempt count (`IntervalsIcuExport.description`). Pushing attempts/segment splits as activity notes or custom fields is a follow-up. No wire-format change.

**FTP test assistant (issue #181, phone-only).** The issue allowed the result to return from the watch as a new message type or through the Strava sync; it uses the Strava sync, so there is no wire-format change and no watch code. `domain/power/FtpTestPlan` defines the classic 20-minute protocol (15 min warm-up ramp 50→75 %, 5 min blow-out and 20 min test at 105 % of FTP, 10 min recovery at 50 %, 10 min cool-down 60→40 %), where 105 % is the current FTP / 0.95. It exports that protocol as a `.zwo` through `ClimbWorkoutWriter.toPlainZwo`. That method was extracted from the MyWhoosh export and writes only self-closing `Warmup`/`SteadyState`/`Cooldown` blocks, so both MyWhoosh and Zwift accept the file. The description asks the rider to switch ERG off for the hard blocks. The file is shared through the existing `ClimbWorkoutExportHandoff` cache. `domain/power/FtpTestResultDetector` reads the already stored `StoredRideStreamStats.powerCurve` 20-minute value (no extra Strava requests) and returns a ride as a test in two cases: it has "FTP" as a word in its name and started within the last 30 days, or it started within 14 days after the export (`RiderProfileRepository.PREF_FTP_TEST_EXPORTED_AT`) and its implied FTP is at least 85 % of the current one. In the second case the hardest such ride wins. E-bike rides and results above `FtpEstimator.MAX_PLAUSIBLE_FTP_WATTS` are ignored. FTP = 0.95 × best 20 min. The "FTP-test" screen (`ui/records/FtpTestActivity`, route-list menu) shows the result with the difference from the current FTP. "FTP bijwerken naar X W" asks for confirmation before `RiderProfileRepository.saveFtp`. Both applying and "Negeren" store the ride id in `PREF_FTP_TEST_HANDLED_ID`, so a result is offered only once. Both prefs belong to the Rijdersprofiel privacy category and are part of backups.

### Pacing plan (phone → watch)

The phone precomputes a per-segment target time for every climb via
`service/RoutePacingPlanner` (route-aware fatigue model with a per-climb
fallback, using the rider profile). These are serialised as an optional packed
int array `tsec` on each climb (parallel to `segs`), route-mode only, omitted
when no plan is available. The climb datafield parses `tsec`, records the timer
at climb start, and shows a live time-delta ghost (`+/−s` vs plan) plus a short
post-summit summary. The route detail screen shows a pacing passport (totals +
per-climb target time) via `ui/routes/RoutePassport`. Background sync re-sends a
route when the rider-profile signature changes (combined with the source hash in
the sync gate). Radius mode is unchanged.

### Per-segment PR / live delta (repeat-climb comparison, phone → watch)

Whole-climb PR/attempt tracking already existed (`ClimbAttemptRepository`,
`LogbookCalculator`, the climb logbook UI). This extends it one level deeper, to
per-segment splits, so the watch can show a live "ahead/behind your PR" delta on
a repeat climb — the same rendering mechanism as the pacing-plan ghost above, fed
by a different (auto-derived) reference instead of a manual rider-profile plan.

- **Capture**: `StravaActivitiesRepository.matchActivity()` already matches a
  synced activity's GPS/time stream against every known climb
  (`ClimbAttemptMatcher.match`) to get the whole-climb elapsed time. It now also
  calls `ClimbAttemptMatcher.matchSegments()`, which walks the same matched
  stretch once more and, by interpolating the crossing time of each segment
  boundary, splits the elapsed time into one duration per segment. The result is
  stored as `StoredClimbAttempt.segSplitSec` (nullable — absent when the climb
  had no segments at match time). Segment boundaries are keyed by index, not
  identity: if a climb is later re-segmented (different segment count), older
  attempts' `segSplitSec` simply stop matching and are ignored rather than
  misaligned.
- **Selection**: `SegmentPrCalculator.bestSplits(climbId, segCount, attempts)`
  computes, for a climb's *current* segment count, the fastest-ever split at
  each segment index across all attempts with a matching-length `segSplitSec` —
  a component-wise minimum, not necessarily all from the same attempt (per-segment
  PR, not whole-climb PR).
- **Wire**: `RouteRefTimePlanner.plan(route, attempts)` produces the same
  per-climb-position `int[][]` shape as `RoutePacingPlanner`, and
  `ClimbPayloadBuilder` emits it as an optional packed int array `refsec` on
  each climb (parallel to `segs`), route-mode only, omitted when no attempt has
  a matching segment count. `refsec` is a distinct field from `tsec` — one is an
  auto-derived PR, the other a manual power-based pacing target — and both may
  be present on the same climb at once.
- **Watch**: `CommListener.mc` parses `refsec` into `ClimbData.segRefSec` /
  `hasRefTargets`, mirroring the existing `tsec` parse. `ClimbData.refSecondsAt()`
  mirrors `targetSecondsAt()` (cumulative reference seconds at the current
  progress, linearly interpolated within the running segment). `ClimbProView`'s
  existing ghost-delta line (bottom of the active-climb screen) prefers the PR
  delta ("vs PR") when a PR reference is available and falls back to the pacing
  delta ("vs plan") otherwise — the FR255M's data-field area is too small to show
  both live deltas at once. No new redraw triggers: this piggybacks on the
  existing per-tick `compute()`/segment-change redraw path.
- **Scope limitation**: reference times are only populated for attempts matched
  *after* this change (Strava activities re-synced going forward); already-stored
  whole-climb attempts have no `segSplitSec` and simply don't contribute to
  `refsec` until the rider's next resync captures fresh splits. Radius mode does
  not carry `refsec` (mirrors `tsec`'s route-mode-only precedent — radius-mode
  climbs are not resegmented per-route the same way). The end-of-climb summary
  screen still only shows the `tsec` delta, not a PR delta.
- **Virtual target-speed ghost (issue #31)**: brand-new climbs have no attempts, so
  there is no PR to race. `service/TargetSpeedRefTimePlanner` turns the rider's
  `domain/power/GhostTarget` (constant speed in km/h and/or constant VAM in m/h, stored
  by `RiderProfileRepository` in default prefs, edited in Settings → "Ghost voor nieuwe
  klimmen") into per-segment seconds: length / speed, gain / VAM (a gainless segment
  uses the speed the VAM implies at the climb's average gradient), or the slower of the
  two when both are set; cumulative rounding keeps the sum exact.
  `CombinedRefTimePlanner` uses it as the lowest-priority source per climb — manual
  reference > own PR > target ghost > none — so it only fills climbs without a usable
  PR or manual time. Output rides the existing `refsec` field (phone-only, no wire or
  watch change); the target's signature is part of `RouteSyncWorker`'s change hash so
  editing it triggers a resync.

### VAM / climb-rate per segment (phone → watch)

Alongside the gradient-based colour mapping, the phone computes a per-segment
VAM (vertical ascent metres/hour) via `domain/climb/VamCalculator`, called from
`domain/segment/Segmenter` for every segment it produces. Route points carry no
elapsed-time data (routes come from GPX/FIT geometry, not recorded rides), so
this is a *gradient-implied* VAM — gradient converted to m/h at a fixed
reference climbing speed (`ClimbConstants.VAM_REFERENCE_SPEED_MPS`, 3.5 m/s) —
not a measured ascent rate. Two values are kept per segment: the average
(directly from the segment's own gradient) and the peak (the steepest gradient
over any rolling 100 m window of full-resolution route points inside the
segment, `ClimbConstants.VAM_PEAK_WINDOW_M`), which surfaces short ramps the
segment average smooths over. Both are serialised as an optional packed int
array `vam = [avgVamMPerH, peakVamMPerH, ...]` on each climb (parallel to
`segs`, both mode), omitted unless every segment has a computed value — a
climb resynced from storage that predates VAM support (segments carry the `-1`
sentinel) is sent without `vam` rather than partial data. The climb datafield
(`garmin/source/CommListener.mc` → `ClimbData.segVamAvg`/`segVamPeak`/`hasVam`)
parses it and shows the active segment's avg/peak VAM next to the gradient
stat on the active-climb page.

### Interval block per climb (issue #180)

A climb can carry an **interval block**: a power band in % FTP held from the foot to
the top (presets Drempel 95–100 %, Sweet spot 88–93 %, VO2max 110–120 %, Tempo
80–85 %, or a custom target ±3 %). It works outdoors (watch) and indoors (trainer
workout export) from the same stored choice.

- **Phone**: `domain/power/IntervalBlock` (pure, validated) is stored as
  `StoredClimb.intervalBlock` (`StoredIntervalBlock`: preset, lowPct, highPct) inside
  the route JSON, set from the climb detail screen via
  `RouteRepository#setClimbIntervalBlock` and carried across resync by
  `mergePreviousClimbUserData` like renames. The band stays in % FTP so an FTP change
  follows automatically; `RouteSyncWorker`'s want-hash includes
  `IntervalBlock.signature(route)` so a new/changed block triggers a resend.
- **Wire**: `ClimbPayloadBuilder.withFtpWatts(ftp)` turns the band into watts and emits
  the optional per-climb `ib = [targetWatts, lowWatts, highWatts]` (both modes) — only
  for climbs with a block and only when an FTP is set. ~20 bytes per climb with a
  block; additive, no protocol version bump.
- **Watch (datafield `garmin`)**: `CommListener.mc` parses `ib` into
  `ClimbData.hasBlock/blockTarget/blockLow/blockHigh` (malformed or inverted arrays
  disable the block; a resync without `ib` clears it). The block starts with the
  existing once-per-climb, jitter-safe climb-start trigger — no new trigger logic. While
  the climb is active, the VAM line slot shows the band: `Doel 266-280W` without a power
  meter, or `252W 266-280` in blue/green/red for under/in/over
  (`ClimbData.powerZone`, fed by a null-safe `Activity.Info.currentPower`). When the
  rider tops out (`blockFinished`: left the climb at or past its end) a short single
  vibration signals "blok klaar", latched per climb. `garmin-widget` and
  `garmin-surface` ignore `ib`; `garmin-onboard` is exempt (it builds climbs on the
  watch from the raw route and has no user data).
- **Indoor**: `ClimbWorkoutWriter.plan(…, IntervalBlock)` sets every climb step to the
  block's target and re-estimates segment durations at that fixed power
  (`ClimbTimeEstimator.estimateAtFixedPower`); per-segment gradient text events stay,
  the description names the block and the `.zwo` gets the `INTERVALS` tag. The same
  plan drives the `.erg` and MyWhoosh exports.

### Felt temperature on descents (issue #248)

Watch-only, no wire change: the climb datafield (`garmin`) shows the windchill the
rider feels on a descent, since cooling from the riding wind is easy to underestimate.
`garmin/source/WindChill.mc` holds pure helpers: `windChillC` (JAG/TI / Environment
Canada formula, rider speed as wind speed, only for T ≤ 10 °C and v ≥ 4.8 km/h — else
the air temperature), `DescentTracker` (grade over ≥ 150 m of `elapsedDistance` +
`altitude`; on at ≥ 25 km/h and ≤ −3 %, off below 20 km/h or above −1 %) and
`latchFeltTemp` (whole degrees, moves only on a ≥ 1 °C change so the banner does not
churn). `ClimbProView.compute` feeds them each tick; the temperature comes from
`Sensor.getInfo().temperature` (new `Sensor` permission) — a paired Tempe gives real
ambient air, otherwise the FR255M's internal sensor, which reads high from wrist heat.
The value is drawn as a blue top strip only between climbs (the climb view is
untouched), below the off-route and battery banners in priority, and nothing is shown
without a reading.

### FTP intensity-zone colors (issue #66)

The fixed gradient → color mapping stays the default and the single source of
truth in the protocol. A **second, optional** color source rides next to it:
`domain/power/SegmentIntensityZones` estimates each segment's power with the
indoor-workout pacing model (`ClimbWorkoutWriter.plan`: the climb's sustainable
power from `ClimbTimeEstimator`/`PowerSpeedSolver`, swung up/down with the
segment's gradient), places it in a Coggan zone (`ZoneCalculator.powerZoneIndex`)
and maps that onto the same six color indices (`GradientColor.forPowerZone`,
table in `protocol/colors.md`). `ClimbPayloadBuilder.withIntensityZones(profile)`
emits the result as an optional packed int array `zc` (one colorIndex per
segment, both modes) when the rider profile is complete (FTP + weights);
`RouteSyncWorker` and `WatchRequestHandler` use it. `zc` is stripped from any
payload that would exceed `PayloadBudget.MAX_BYTES`. The rider profile is part of
the route sync hash, so an FTP change resyncs.

On the watch, `garmin` (datafield) and `garmin-widget` parse `zc` into
`ClimbData.segZone[climb]` (allocated only when present; null otherwise, and
cleared on a resync without it). A new app setting **Kleurmodus** (`colorMode`:
0 = Helling, default; 1 = FTP-zone) selects the source; `ClimbData.colorIndexAt`
returns the zone color only in FTP-zone mode on a climb that has `zc`, and the
gradient color otherwise. `garmin-surface` carries no climbs, and
`garmin-onboard` computes its climbs on the watch without a rider profile, so
both keep gradient colors only. The climb detail screen on the phone shows the
zone per segment (a `Z1`–`Z7` badge in the watch's zone color).

---

## Configuration Management

Planned config surfaces (none exist yet):

| Config              | Format                             | Location      | Notes                                        |
| ------------------- | ---------------------------------- | ------------- | -------------------------------------------- |
| Strava OAuth client | `local.properties` / `BuildConfig` | Android       | Do not commit secrets                        |
| Climb thresholds    | constants in `protocol/`           | Both sides    | Change in one place                          |
| Device target       | Connect IQ `manifest.xml`          | Garmin module | Must include Forerunner 255 Music product ID |

---

## Common Patterns

Patterns to apply once code lands. Examples will be filled in as they're implemented.

1. **Repository over data source** — UI/ViewModels depend on repository interfaces, not on Strava SDK or file parsers directly.
2. **Immutable domain models** — Java POJOs with `final` fields and constructor-only initialization (or `record` if the project targets Java 16+); on Monkey C, treat decoded payload as read-only after load.
3. **Background work via WorkManager** — sync and route analysis run as `ListenableWorker`/`Worker` subclasses, not on the main thread.
4. **Pure functions in the domain layer** — `ClimbDetector`, `Segmenter`, `Smoother`, `Simplifier` take inputs, return outputs, no I/O. Easy to unit-test.
5. **Compact payload encoding** — prefer packed primitive arrays over object arrays; ints in meters, gradients as fixed-point (e.g. `int` = gradient × 10). The watch decodes once at sync time.
6. **Hysteresis filter for matching** — never accept a "closer" point that would move progress backward by more than a small threshold (e.g. 20 m). Prevents flapping at switchbacks and during GPS drift.1
7. **Idempotent triggers** — climb-start alert keyed by climb index + a "fired" set; cleared only on route change.
8. **Fail-soft sync** — sync errors log and retry; they never crash the app or block UI updates.
9. **JSON-file persistence** — routes and climb catalog stored as JSON files in app-private storage (`context.getFilesDir()`); one file per route + a single `catalog.json` index. Load on demand, write atomically (write to temp + rename). No Room/SQLite.
10. **Schema-driven protocol** — `protocol/schema.json` is the source of truth. Java POJOs are generated (Gradle task wired into `assemble`); Monkey C classes are hand-edited to match. Never edit generated Java by hand; never change Monkey C without first updating `schema.json`.

---

## Navigation Guide

### By feature

- **Strava** → `android/.../data/strava/`
- **Route parsing** → `android/.../domain/route/`
- **Climb detection** → `android/.../domain/climb/`
- **Sync** → `android/.../service/` + `android/.../connectiq/`
- **Watch rendering** → `garmin/source/views/`
- **Watch GPS matching** → `garmin/source/matching/`
- **Wire format** → `protocol/`

### By task

- "Routes aren't syncing" → check `SyncWorker` logs, then `ConnectIqClient`, then payload size limits.
- "Climb detected but looks wrong" → `ClimbDetector` unit tests with the source GPX as a fixture.
- "Watch redraws too often" → `ClimbView.onUpdate` — gate redraws on segment change.
- "Alert fires twice" → check the per-climb "fired" set in the audio module.

---

## Search Strategies

Once code exists:

- Domain rules: `grep -r "800" --include="*.java"` (climb length), `grep -r "0.03" --include="*.java"` (gradient threshold). If either appears outside `protocol/` or the detector, it's a duplication — fix it.
- Payload format: search `protocol/schema.json` first (canonical). After changing a field name, regenerate Java POJOs and grep `*.mc` to update the hand-written Monkey C side.
- Color thresholds: search for the gradient cutoffs (`0.02`, `0.04`, `0.06`, `0.08`, `0.10`). They should only appear in `protocol/colors.md` or its generated outputs.

---

## Watch app, active-route relay & surface datafield (2026-06-10)

The browse widget is now a **device app** (`garmin-widget/`, manifest type `watch-app`, same app ID) with a glance in the FR255 up/down loop. Heavy init happens in `getInitialView` so the glance stays within its memory budget. The saved-route list reads a lightweight `saved_route_meta` Storage index (`{routeId → {name, climbCount}}`) instead of loading full payloads; climb data is loaded only when a route is opened.

**Active route/climb selection.** Connect IQ apps have isolated storage, so the watch app cannot hand a payload to a datafield directly. Selection is relayed through the phone: watch app sends `SET_ACTIVE_ROUTE {id}` or `SET_ACTIVE_CLIMB {id, climbIdx}`; `WatchRequestHandler` builds the payload and pushes it to the datafield app IDs (`ConnectIqAppId.DATAFIELD`, `ConnectIqAppId.SURFACE_FIELD`); the phone acks the watch app with `ACTIVE_SET {ok, name}`. The climb datafield persists every received payload under Storage key `active_payload` and restores it at `onStart`, so the ride itself is fully offline. The phone must be reachable only at selection time.

**Surface-sections datafield** (`garmin-surface/`, app ID `00112233...`): shows the user-defined surface/flat section the rider is in (name as title, surface type small underneath, remaining metres) and the next one. It receives a lean payload `{v:3, mode:"route", routeId, name, climbs:[], surfSec:[{s,e,t,n?,cp:[dist,latInt,lonInt, ...]}, ...]}` built by `ClimbPayloadBuilder.buildSurfaceSectionPayload`. The `surfSec` array merges `StoredRoute.surfaceSections` with **qualifying** `StoredFlatSegment`s (those the user has named or assigned a surface to) — this **reverses** the earlier 2026-06-10 decision to never send flat segments, but only for ones the user has explicitly touched; untouched flat segments are still skipped. Each section carries an optional `name` and a packed checkpoint array `cp = [distanceFromRouteStart, latInt, lonInt, ...]` (latInt/lonInt = degrees×100000, mirroring climb `calib`), computed at build time from the route geometry. An empty `surfSec` is sent on purpose to clear stale sections. Single-climb activation sends no surface payload.

On the watch, `SurfaceData` keeps `elapsedDistance` as the primary matching axis and applies a smoothed `distanceOffset` snapped to the nearest checkpoint within 40 m (`correctElapsed`), so GPS coordinates correct drift without replacing distance matching. `SurfaceData` additionally splits the current section locally into sub-pieces of 8% of its length (`SUBPIECE_FRACTION_PCT`, ≈13 sub-pieces, capped at `MAX_SUBPIECES`) and tracks `currentSubPiece`/`subPieceCount` — derived purely from `s`/`e`, so no extra payload is needed. `SurfaceFieldView` renders this as a filled segmented bar with an inline "deel X/Y" counter, and fires a one-shot vibrate + `TONE_LAP` alert within 50 m of each section's start, idempotent per section (a per-section flag survives GPS jitter) and reset on route change — the same pattern as the climb-start alert in `ClimbProView`.

---

## Climb Logbook (phone-only, 2026-06-13)

The Climb Logbook matches a rider's Strava activity history against known climbs and surfaces per-climb past attempts, the personal record (PR), and a delta-to-PR. It is **entirely phone-side**: it does not touch the watch, the Connect IQ sync payload, or `protocol/schema.json`.

### Data source

Strava activities are fetched via two new `StravaApiClient` endpoints (`listActivities`, `getStreams`). On first sync the app fetches the last 12 months of activities; subsequent syncs are incremental (the cursor is only advanced once a full batch has been matched and persisted — an aborted run does not lose its place). Sync is triggered manually (no WorkManager periodic worker for activities yet).

Ride comparer (issue #199): tapping a ride in Ritten offers `RideComparison.sameRouteCandidates` (other archived rides with distance within 10 % and start and end within 500 m when both have coordinates). `RideCompareActivity` fetches both rides' streams on demand via `StravaActivitiesRepository#fetchRideStreams` (nothing stored) and `RideComparison.compare` (pure) aligns them on distance: per kilometre the moving time (intervals under 0.5 m/s don't count), speed, mean heart rate and the running time difference, over the distance both rides cover. Ride A is always the older one.

Ride story (issue #193): tapping a ride in Ritten also offers "Rit-verhaal delen". `RideStory` (pure) picks the content: headline stats, up to four climbs ridden on it (PRs first; a first ascent is labelled as such, never as PR; route-deviation passes never count), the average device temperature (from the `temp` stream, else from the attempts) and the first attempt photo of the ride. `RideStoryViewModel` fetches the GPS track on demand (`StravaActivitiesRepository#fetchRideTrack`, keys `latlng,temp`; without Strava or offline the story is made without it) and `RideStoryImageComposer` draws a 1080×1350 image with the route shape from `RouteShape` (equirectangular fit, no map tiles, so no network or attribution), shared through the climb-share FileProvider handoff. Nothing is stored.

One-off history backfill (issue #312): Settings → "Volledige historie ophalen (10 jaar)" enqueues `StravaHistoryBackfillWorker` (unique work, KEEP, linear 15-min backoff). `StravaActivitiesRepository#backfillHistory` walks newest to oldest with `listActivitiesBefore(before, after)` from the moment it was started down to 10 years before it, moving the `before` cursor after every activity so a stopped run resumes exactly there (window and cursor in the `strava_activities` prefs). Every ride goes into the ride archive; cycling rides with GPS (virtual rides included) are matched against known climbs. It pauses on a 429, retries on 5xx/network errors, skips 404s for good, and stops early when Strava's rate-limit headers (`StravaRateLimit`) show the budget reserved for the regular sync is next. It never renames activities on Strava. Once the window is covered, `history_backfill_done` is set and it never runs again.

### Route-independent climb identity

Each known climb is keyed by a `ClimbIdentity`: a bucketed start coordinate (0.001° grid) plus the climb length. This survives route renames, re-imports, and Strava route-ID changes, so attempts from different routes that share the same physical climb are grouped together. `ClimbIdentity` is derived from a `StoredRoute` via `KnownClimbs.fromRoute`.

### Matching (`ClimbAttemptMatcher`)

For each activity the matcher checks whether the GPS track passes through a known climb:

1. **Entry gate**: the track must come within a proximity threshold of the climb start. The timestamp of the earliest crossing is the attempt start.
2. **Exit gate**: the track must come within the same threshold of the climb end. The first sample at or after the entry that comes within the gate of the climb end marks the attempt end (`firstWithin`, the same earliest-match logic used for the entry).
3. **Length validation**: the matched segment length must be within ±25% of the known climb length. This rejects partial traversals (e.g. turning around mid-climb) while tolerating GPS drift.

Out-and-back rides are handled by picking the earliest gate entry: this ensures the ascent on an out-and-back ride is still matched (rather than dropped because a return pass shadowed it). `match()` records one attempt per activity per climb — it does not record separate outbound and return attempts.

### Persistence

The phone also keeps a **ride archive** (`rides.json`, `data/ride/RideRepository`, issue #160): one `StoredRide` summary per synced Strava cycling activity (distance, moving time, elevation, speeds, commute flag, start/end point). It is filled from the activity *list* endpoint only (no streams), with its own sync cursor that backfills the past year on first run, and never blocks climb matching. `domain/ride/RideClassifier` classifies each ride as woon-werk / training / toerrit on the fly (not persisted). Phone-only; never part of the wire payload.

A **Records** screen (issue #156, `ui/records/RideRecordsActivity`) reads the same archive: `domain/ride/RideRecordsCalculator` derives longest ride, highest average speed (only rides >= 20 km, never `VirtualRide`), most elevation, longest moving time and most consecutive local calendar days with a ride. Ties go to the earliest ride. Computed on the fly, not persisted; phone-only.

**Descent info** (issue #215, `domain/climb/DescentAnalyzer` + `DescentLabel`, shown on the climb screen): computed on the fly from the stored route geometry, not persisted. The descent starts at the climb's top and ends at the lowest point before the road rises 15 m again, before the next climb starts, or at the route end (the screen says which). Leading and trailing false flat (descending less than `FALSE_FLAT_MAX_GRADIENT` over at least `FALSE_FLAT_MIN_LENGTH_M`) is trimmed, mirroring `ClimbTrimmer`, so a summit plateau or a valley run-out doesn't dilute the numbers. Less than 40 m of drop is reported as no notable descent. Shown: length, drop, average gradient, maximum gradient over any 100 m stretch, and twistiness. Twistiness is the summed absolute heading change per km: under 120 °/km "vrij recht", under 300 "bochtig", otherwise "zeer bochtig". A net turn of at least 150 ° within 200 m counts as a hairpin; turns are signed in that sum, so S-bends don't count. The geometry is the Douglas-Peucker-simplified route (5 m), which keeps real bends. Phone-only; never part of the wire payload.

**Climb history & facts** (issue #212, `domain/history/FamousClimb` + `ClimbFactsParser` + `FamousClimbMatcher`, `data/history/ClimbFactsRepository`, shown as the "Weetjes" card on the climb screen): a curated dataset ships in the APK as `assets/climb_facts.json` — per famous climb a name, aliases, the top coordinate, one or more sides (named start points) and a few short facts. No backend and no network; the file is loaded once per process off the main thread, and broken entries are skipped rather than failing the whole file. The matcher compares the stored climb's foot and top (`ClimbEndpoints`) with the dataset in three tiers: top **and** a side's start near (tolerances scale with that side's straight-line foot-to-top span: top 20 % clamped to 600–1500 m, start 40 % clamped to 800–3000 m; the closest pair wins and names the side), then top only (a side not in the dataset), then a whole-word, accent-insensitive name match on the user or detected climb name, rejected when the climb lies more than 30 km from the famous top. `ClimbFactsParserTest` validates the bundled file (every entry parses, unique ids, plausible sides, brief facts, each entry matches itself). Read-only app data: not user data, nothing persisted, never part of the wire payload.

**Wind impact on the climb time** (issue #47, `domain/power/WindImpactEstimator`, `domain/weather/ClimbSegmentBearings` + `ClimbWindImpact`, shown on the climb screen under the time estimate): after the normal (fatigue-aware or per-climb) estimate, `ClimbDetailViewModel` fetches the Open-Meteo hourly forecast at the climb top (now including `wind_direction_10m`) on its own executor and caches it per loaded climb. Each segment's bearing is the chord between its start and end point on the stored route geometry. The forecast wind for the current hour is projected onto that bearing (headwind = speed · cos(wind-from − bearing)), scaled by `WIND_HEIGHT_FACTOR` 0.7 from 10 m to rider height, and fed into the aero term of `PowerSpeedSolver` as 0.5·ρ·CdA·(v+w)·|v+w|·v. Windless and windy times are both computed at the estimate's own assumed power, so the delta isolates the wind (a headwind costs more than the same tailwind saves; switchbacks don't fully cancel). The screen shows the estimate plus that delta with "wind tegen" / "wind mee", or "nauwelijks invloed" under 5 s. Without network, forecast hour, wind direction or route geometry the line reads "Zonder windcorrectie" and the base estimate is untouched. Phone-only; nothing persisted, never part of the wire payload.

**Ride stream analysis** (issue #225): some ride insights need the per-second Strava streams, which the archive itself never stores. `StravaActivitiesRepository#analyzeRideStreams` (called after the archive refresh in `RouteSyncWorker` and from the Ritten screen) fetches `time,distance` streams for archived rides without a current analysis, newest first, at most 20 per run (Strava's 100-reads-per-15-min budget is shared with climb matching), and pauses on HTTP 429/401/403 or a network error. `domain/ride/RideStreamAnalyzer` (pure, versioned via `VERSION` so a changed analysis redoes older entries) reduces the streams to a `StoredRideStreamStats` in `ride_stream_stats.json` (`data/ride/RideStreamStatsRepository`); a 404 stores an empty entry so manual rides aren't asked again. The streams are then discarded. First use: the fastest **10, 40 and 100 km** inside any ride, as elapsed time with the window start interpolated between samples; windows spanning a GPS jump (> 30 m/s between samples) are skipped. `FastestDistanceCalculator` ranks the top 3 per distance on the Records screen, excluding indoor and e-bike rides. The file belongs to the Rittenarchief privacy category and is included in backups. Phone-only; never part of the wire payload.

**Ride stream analysis** (issue #225): some ride insights need the per-second Strava streams, which the archive itself never stores. `StravaActivitiesRepository#analyzeRideStreams` (called after the archive refresh in `RouteSyncWorker` and from the Ritten screen) fetches `time,distance` streams for archived rides without a current analysis, newest first, at most 20 per run (Strava's 100-reads-per-15-min budget is shared with climb matching), and pauses on HTTP 429/401/403 or a network error. `domain/ride/RideStreamAnalyzer` (pure, versioned via `VERSION` so a changed analysis redoes older entries) reduces the streams to a `StoredRideStreamStats` in `ride_stream_stats.json` (`data/ride/RideStreamStatsRepository`); a 404 stores an empty entry so manual rides aren't asked again. The streams are then discarded. First use: the fastest **10, 40 and 100 km** inside any ride, as elapsed time with the window start interpolated between samples; windows spanning a GPS jump (> 30 m/s between samples) are skipped. `FastestDistanceCalculator` ranks the top 3 per distance on the Records screen, excluding indoor and e-bike rides. Second use, **sprints** (issue #224, analyzer `VERSION` 2, streams `time,distance,watts,altitude`): `domain/ride/SprintAnalyzer` resamples power to 1 Hz (seconds without a sample count as 0 W, readings > 2500 W are glitches) and stores the peak 5 s and 15 s power with its offset in the ride, plus the peak 10 s speed over continuous riding (window at most 2 s longer than 10 s) that descends no more than 1 %. Without an altitude stream there is no speed sprint, since a descent would pass for one. `SprintCalculator` ranks the top 5 by power (indoor counts, e-bikes don't) and by speed (outdoor only) on the Records screen. The file belongs to the Rittenarchief privacy category and is included in backups. Phone-only; never part of the wire payload.

**Ride stream analysis** (issue #225): some ride insights need the per-second Strava streams, which the archive itself never stores. `StravaActivitiesRepository#analyzeRideStreams` (called after the archive refresh in `RouteSyncWorker` and from the Ritten screen) fetches `time,distance` streams for archived rides without a current analysis, newest first, at most 20 per run (Strava's 100-reads-per-15-min budget is shared with climb matching), and pauses on HTTP 429/401/403 or a network error. `domain/ride/RideStreamAnalyzer` (pure, versioned via `VERSION` so a changed analysis redoes older entries) reduces the streams to a `StoredRideStreamStats` in `ride_stream_stats.json` (`data/ride/RideStreamStatsRepository`); a 404 stores an empty entry so manual rides aren't asked again. The streams are then discarded. First use: the fastest **10, 40 and 100 km** inside any ride, as elapsed time with the window start interpolated between samples; windows spanning a GPS jump (> 30 m/s between samples) are skipped. `FastestDistanceCalculator` ranks the top 3 per distance on the Records screen, excluding indoor and e-bike rides. Second use, **sprints** (issue #224, analyzer `VERSION` 2, streams `time,distance,watts,altitude`): `domain/ride/SprintAnalyzer` resamples power to 1 Hz (seconds without a sample count as 0 W, readings > 2500 W are glitches) and stores the peak 5 s and 15 s power with its offset in the ride, plus the peak 10 s speed over continuous riding (window at most 2 s longer than 10 s) that descends no more than 1 %. Without an altitude stream there is no speed sprint, since a descent would pass for one. `SprintCalculator` ranks the top 5 by power (indoor counts, e-bikes don't) and by speed (outdoor only) on the Records screen. Third use, **heart-rate drift** (issue #222, `VERSION` 3, adds the `heartrate` stream): `domain/ride/HeartRateDriftAnalyzer` computes aerobic decoupling. It skips a 10-min warm-up and keeps only moving samples (1-30 m/s, no gap > 10 s, plausible heart rate), requires at least 60 analyzed minutes, and splits them into two halves by moving time. Drift is the percentage drop of output/heart rate from the first half to the second, where output is power when at least half the samples have it and speed otherwise. Speed is only used on flat rides (< 8 m climbed per km). It also stores the average moving heart rate. `HeartRateDriftCalculator` lists rides newest first with a label (< 5 % stable, < 10 % moderate, else strong; e-bikes excluded) and compares the average of the last 6 weeks with the 6 weeks before, on the "Hartslag-drift" screen (`ui/records/HeartRateDriftActivity`). The file belongs to the Rittenarchief privacy category and is included in backups. Phone-only; never part of the wire payload.

**Ride stream analysis** (issue #225): some ride insights need the per-second Strava streams, which the archive itself never stores. `StravaActivitiesRepository#analyzeRideStreams` (called after the archive refresh in `RouteSyncWorker` and from the Ritten screen) fetches `time,distance` streams for archived rides without a current analysis, newest first, at most 20 per run (Strava's 100-reads-per-15-min budget is shared with climb matching), and pauses on HTTP 429/401/403 or a network error. `domain/ride/RideStreamAnalyzer` (pure, versioned via `VERSION` so a changed analysis redoes older entries) reduces the streams to a `StoredRideStreamStats` in `ride_stream_stats.json` (`data/ride/RideStreamStatsRepository`); a 404 stores an empty entry so manual rides aren't asked again. The streams are then discarded. First use: the fastest **10, 40 and 100 km** inside any ride, as elapsed time with the window start interpolated between samples; windows spanning a GPS jump (> 30 m/s between samples) are skipped. `FastestDistanceCalculator` ranks the top 3 per distance on the Records screen, excluding indoor and e-bike rides. Second use, **sprints** (issue #224, analyzer `VERSION` 2, streams `time,distance,watts,altitude`): `domain/ride/SprintAnalyzer` resamples power to 1 Hz (seconds without a sample count as 0 W, readings > 2500 W are glitches) and stores the peak 5 s and 15 s power with its offset in the ride, plus the peak 10 s speed over continuous riding (window at most 2 s longer than 10 s) that descends no more than 1 %. Without an altitude stream there is no speed sprint, since a descent would pass for one. `SprintCalculator` ranks the top 5 by power (indoor counts, e-bikes don't) and by speed (outdoor only) on the Records screen. Third use, **heart-rate drift** (issue #222, `VERSION` 3, adds the `heartrate` stream): `domain/ride/HeartRateDriftAnalyzer` computes aerobic decoupling. It skips a 10-min warm-up and keeps only moving samples (1-30 m/s, no gap > 10 s, plausible heart rate), requires at least 60 analyzed minutes, and splits them into two halves by moving time. Drift is the percentage drop of output/heart rate from the first half to the second, where output is power when at least half the samples have it and speed otherwise. Speed is only used on flat rides (< 8 m climbed per km). It also stores the average moving heart rate. `HeartRateDriftCalculator` lists rides newest first with a label (< 5 % stable, < 10 % moderate, else strong; e-bikes excluded) and compares the average of the last 6 weeks with the 6 weeks before, on the "Hartslag-drift" screen (`ui/records/HeartRateDriftActivity`). Fourth use, the **power curve** (issue #219, `VERSION` 4, no new streams): `domain/ride/PowerCurveAnalyzer` resamples power to 1 Hz the same way as the sprint (gaps count as 0 W, > 2500 W is a glitch) and stores the best average watts over 5 s, 1, 5, 20 and 60 min as `powerCurve` (0 where the ride is shorter). `PowerCurveCalculator` takes the best per duration over 6 weeks, 90 days, a year or all time (e-bikes excluded, indoor counts, ties to the earliest ride). The "Vermogenscurve" screen (`ui/records/PowerCurveActivity`) draws the chosen period against the all-time curve and lists each value with W/kg (when a rider weight is set) and its ride. The file belongs to the Rittenarchief privacy category and is included in backups. Phone-only; never part of the wire payload.

**Ride stream analysis** (issue #225): some ride insights need the per-second Strava streams, which the archive itself never stores. `StravaActivitiesRepository#analyzeRideStreams` (called after the archive refresh in `RouteSyncWorker` and from the Ritten screen) fetches `time,distance` streams for archived rides without a current analysis, newest first, at most 20 per run (Strava's 100-reads-per-15-min budget is shared with climb matching), and pauses on HTTP 429/401/403 or a network error. `domain/ride/RideStreamAnalyzer` (pure, versioned via `VERSION` so a changed analysis redoes older entries) reduces the streams to a `StoredRideStreamStats` in `ride_stream_stats.json` (`data/ride/RideStreamStatsRepository`); a 404 stores an empty entry so manual rides aren't asked again. The streams are then discarded. First use: the fastest **10, 40 and 100 km** inside any ride, as elapsed time with the window start interpolated between samples; windows spanning a GPS jump (> 30 m/s between samples) are skipped. `FastestDistanceCalculator` ranks the top 3 per distance on the Records screen, excluding indoor and e-bike rides. Second use, **sprints** (issue #224, analyzer `VERSION` 2, streams `time,distance,watts,altitude`): `domain/ride/SprintAnalyzer` resamples power to 1 Hz (seconds without a sample count as 0 W, readings > 2500 W are glitches) and stores the peak 5 s and 15 s power with its offset in the ride, plus the peak 10 s speed over continuous riding (window at most 2 s longer than 10 s) that descends no more than 1 %. Without an altitude stream there is no speed sprint, since a descent would pass for one. `SprintCalculator` ranks the top 5 by power (indoor counts, e-bikes don't) and by speed (outdoor only) on the Records screen. Third use, **heart-rate drift** (issue #222, `VERSION` 3, adds the `heartrate` stream): `domain/ride/HeartRateDriftAnalyzer` computes aerobic decoupling. It skips a 10-min warm-up and keeps only moving samples (1-30 m/s, no gap > 10 s, plausible heart rate), requires at least 60 analyzed minutes, and splits them into two halves by moving time. Drift is the percentage drop of output/heart rate from the first half to the second, where output is power when at least half the samples have it and speed otherwise. Speed is only used on flat rides (< 8 m climbed per km). It also stores the average moving heart rate. `HeartRateDriftCalculator` lists rides newest first with a label (< 5 % stable, < 10 % moderate, else strong; e-bikes excluded) and compares the average of the last 6 weeks with the 6 weeks before, on the "Hartslag-drift" screen (`ui/records/HeartRateDriftActivity`). Fourth use, the **power curve** (issue #219, `VERSION` 4, no new streams): `domain/ride/PowerCurveAnalyzer` resamples power to 1 Hz the same way as the sprint (gaps count as 0 W, > 2500 W is a glitch) and stores the best average watts over 5 s, 1, 5, 20 and 60 min as `powerCurve` (0 where the ride is shorter). `PowerCurveCalculator` takes the best per duration over 6 weeks, 90 days, a year or all time (e-bikes excluded, indoor counts, ties to the earliest ride). The "Vermogenscurve" screen (`ui/records/PowerCurveActivity`) draws the chosen period against the all-time curve and lists each value with W/kg (when a rider weight is set) and its ride. Fifth use, **zone time** (issue #218, `VERSION` 5, no new streams): `domain/ride/ZoneHistogramAnalyzer` stores time-weighted histograms instead of zone totals, so a changed max heart rate or FTP needs no re-fetch: `hrSecondsPerBpm` (1 bpm bins from 40 bpm) and `powerSecondsPer10W` (10 W bins, 1500 W and up in the last), each trimmed after the last non-zero bin. A sample counts until the next one, up to 10 s (longer gaps are pauses); coasting at 0 W counts. `ZoneCalculator` maps them onto five heart-rate zones (60/70/80/90 % of max heart rate) and Coggan's seven power zones (55/75/90/105/120/150 % of FTP, each 10 W bin placed by its middle), per ride and summed over the last 4 weeks; e-bike rides get heart-rate zones only. The max heart rate is a rider setting (`RiderProfileRepository.PREF_MAX_HEART_RATE`, entered on the screen). Until it is set, the highest heart rate held for 30 s in any ride is used. The "Zonetijd" screen (`ui/records/ZoneDistributionActivity`) shows a colored bar per ride. The file belongs to the Rittenarchief privacy category and is included in backups. Phone-only; never part of the wire payload.

A **Doelevenement** screen (issue #221, `ui/goals/GoalEventActivity`) holds one target event (name, date, distance, elevation) in `goal_event.json` (`data/goal/GoalEventStore`, own privacy category, included in backups). `domain/goal/GoalEventProgress` derives from the ride archive, on the fly:
- the countdown and phase: base when more than 84 days out, build, taper in the last 14 days, event day, past;
- readiness: the longest ride and the most climbing in one ride over the last 28 days, compared with the event (outdoor only; e-bikes excluded);
- weekly volume for the last 6 Monday-based weeks, with indoor rides counted;
- one advice line per phase. In the build phase it asks for a longest ride of about 75 % of the event distance before the taper.

Phone-only.

**Climb share codes** (issue #216, `domain/share`, `data/route/SharedClimbImporter`, `ui/share/ClimbCodeSharing`): a climb (climb detail, "Deel als code") or a collection (collection menu) is shared as text through the share sheet, with no GPX file or server. The code is `CPC1:` + base64url of gzipped JSON. Each climb is its display name plus its geometry from 200 m before the foot to 200 m past the top (plus one point beyond), taken from the stored, already smoothed and simplified route. Lat/lon are quantised to 1e-5 degrees and elevation to decimetres, delta-encoded. A collection code carries its climb members plus every climb of its member routes (routes travel as their climbs), deduplicated, at most 25. **Home climbs are never shared**: their geometry would reveal where the rider lives. Decoding finds the code anywhere in a pasted message and is bounded (250 000 characters, 2 MB inflated, 3000 points per climb, coordinates in range), and every failure gives a Dutch message. On import ("Add route" dialog > "Klimcode importeren", prefilled from the clipboard), `SharedClimbImportPlanner` recomputes distances and runs `ClimbDetector` on each geometry, keeping the longest climb. A climb whose start is within `DUPLICATE_CLIMB_MATCH_RADIUS_M` of an existing one is not imported again. A confirm dialog lists every climb with length, gradient and "(heb je al)" before anything is written. Each new climb becomes a small `share_…` route named after it, with the shared name as the climb's `userDisplayName`. A collection code also creates a collection (made unique with " (2)") holding the new and the already-known climbs. Phone-only; never part of the wire payload.

**Climb comparison** (issue #214, `domain/climb/ClimbComparison`, `ui/climbs/ClimbCompareActivity`): "Vergelijk met andere klim" on the climb screen opens a comparison with a second climb the rider picks. The pick list holds every climb in their routes, deduplicated by `ClimbIdentity` and sorted by name. The screen shows both profiles on one scale (height above the foot against distance, built from the segments) and a table of length, elevation gain, average gradient, steepest segment, difficulty (`DifficultyScoreCalculator` without route fatigue, so both are compared fresh), category, estimated time (`ClimbTimeEstimator` per climb from the rider profile, not route-aware, for the same reason), the rider's record and number of attempts from the climb logbook. A one-line verdict says which climb is harder by how much on the difficulty score (within 10 % counts as "ongeveer even zwaar"). Computed on the fly from existing data, not persisted. Phone-only; never part of the wire payload.

**Tire-pressure log** (`tire_pressure_log.json`, `data/tire/TirePressureLogRepository`, issue #155): one JSON object holding the manual checks (timestamp, front/rear pressure in bar with one decimal — the UI shows the psi equivalent next to it to line up with the psi ranges of the #90 tire-pressure advice — optional note) plus the reminder settings (every X days / every X km, 0 = off; defaults 7 days / 300 km). Atomic writes with a static write lock, like `RideRepository`. `domain/tire/TirePressureReminderCalculator` marks a check due when either threshold since the latest entry is reached; km = sum of archived `StoredRide.distanceM` that started after that entry, **excluding `VirtualRide`** (indoor km don't wear road tyres). With no entries yet it is not due (no permanent banner for riders who don't use the feature; the log screen prompts for a first check instead). The reminder is in-app and offline only: a banner on the route list (re-evaluated on resume) plus status on the log screen — no notification permission or worker (possible follow-up). Km only advance when the ride archive syncs from Strava. Phone-only; never part of the wire payload.

Matched attempts are stored in `climb_attempts.json` under `getFilesDir()`, following the same JSON-file pattern used for routes. `ClimbAttemptRepository` deduplicates on `(climbId, activityId)` so re-running a sync never creates duplicate entries. Reads are on demand; writes are atomic (temp + rename).

**Maintenance tracker** (`maintenance.json`, `data/maintenance/MaintenanceRepository`, issue #154): one JSON object with the user's components (defaults Ketting 3000 km, Banden 4000 km, Remblokken 2000 km, Service 5000 km / 12 months; the user can add, rename, re-interval and delete components, and an emptied list is not re-seeded). Each component has an interval in km and/or calendar months (0 = off), a last-serviced date and a small history of service dates (max 10). Atomic writes with a static write lock, like `RideRepository`; a missing or corrupt file loads as the default set. `domain/maintenance/MaintenanceCalculator` (pure, explicit now + zone) computes km since service = sum of archived `StoredRide.distanceM` that started after the last-serviced date, **excluding `VirtualRide`** unless the component opts in (trainer km don't wear a road chain/tyres the same way); undated rides are skipped. Due when either interval is reached, "bijna" from 90 %; a component without a known service date is never due (no permanent banner for new users). "Gedaan" appends now to the history and restarts the count. Surfaced in-app only: a banner on the route list (re-evaluated on resume, off the main thread) opening the "Onderhoud" screen (overflow menu). Independent of climb logic; km only advance when the ride archive syncs. Phone-only; never part of the wire payload.

**Friends' feed** (`friend_feed.json`, `data/social/FriendFeedRepository`, issue #240): social sharing without a backend. "Deel mijn ritten" builds a snapshot (`domain/social/OwnFeedBuilder`: rides from the ride archive and first ascents from climb attempts, last 30 days, max 15 + 5, never thuisklimmen) and encodes it as a text share code `CPF1:` + base64url(gzip(JSON)) (`domain/social/FriendShareCode`, max 20 entries, titles ≤ 60 chars; no coordinates, no Strava ids), sent through the Android share sheet. Friends paste the code in "Vriendenfeed" or share the chat message to ClimbPro (`ACTION_SEND text/plain`). Decoding treats the text as untrusted: size limits before and after inflating, version check (`CPF2:` / `"v":2` → "werk de app bij"), malformed entries skipped. `FriendFeedMerger` de-duplicates re-imports (ride = friend + start second, milestone = + title), updates a renamed friend and keeps ≤ 100 entries per friend. Sharer identity is a random UUID + chosen name in default prefs (`FriendShareIdentity`). Registered in the privacy dashboard and backup. Phone-only; never part of the wire payload.

**Torque values** (`torque_values.json`, `data/maintenance/TorqueValueRepository`, issue #237): the "Aanhaalmomenten" screen (overflow menu) shows a static reference table of typical tightening torques per part (`domain/maintenance/TorqueReference`, e.g. stuurpen stuurklem 4–6 Nm, zadelpenklem carbon 4–6 Nm, cassette-lockring 40 Nm) under a "fabrikant gaat voor" disclaimer, plus the rider's own values. The app has no bike entity, so each own value carries an optional free-text bike label (auto-completed from labels already used), a part name, one Nm value (0,1–200, one decimal; comma or dot) and an optional note. Tapping a reference row pre-fills the add dialog. Atomic writes with a static write lock, like `MaintenanceRepository`; a missing or corrupt file loads empty and out-of-range entries are dropped on load. Registered with the privacy dashboard (`PrivacyCategory.TORQUE`) and the local backup. Phone-only; never part of the wire payload.

**Warranty** (issue #239) is an optional extra per component in the same `maintenance.json`: `warrantyPurchaseEpochSec` + `warrantyMonths` (0 = none); expiry = purchase + N calendar months (`domain/maintenance/WarrantyCalculator`, clamped to month end). A daily `service/WarrantyReminderWorker` (periodic, KEEP, scheduled on app start) posts one "Garantie verloopt bijna" notification (`WarrantyNotifier`, channel `warranty`) in the last 30 days before expiry; idempotent via `warrantyReminderSentForExpiryEpochSec` (keyed by expiry, so correcting the date/term re-arms it). Already expired warranties never notify. Shown as a line on the component card; edited in the component dialog.

**Bike cost overview** (`bike_costs.json`, `data/bike/BikeCostRepository`, issue #233): one JSON object per bike (name, since/retired dates, archive/virtual-ride toggles, manual extra km, a list of purchase/part cost entries) with atomic writes (temp file + rename) behind a static write lock, the same pattern as `RideRepository`/`MaintenanceRepository`. Money is integer cents (`domain/bike/EuroAmount`), never a float, with Dutch-style parsing/formatting (`€ 1.234,56`). A bike's km are the archived `StoredRide.distanceM` for rides that started in `[sinceEpochSec, retiredEpochSec)` (open-ended when not retired), **excluding `VirtualRide`** unless the bike opts in, plus manually entered extra km; `domain/bike/BikeCostCalculator` (pure) sums the purchase and part costs and derives cents-per-km, never producing a rate below 1 km travelled. Riders who mix bikes on the same Strava account (which carries no gear id) can turn the archive off per bike and enter km by hand instead. Registered in `PrivacyCategory` and `BackupArchive.INCLUDED_PATHS`. Surfaced in-app only via the "Fietskosten" screen (route list overflow menu). Phone-only; never part of the wire payload.

**Bike garage** (issue #187, extends `bike_costs.json` rather than adding a file): each `Bike` also carries `type` (road/gravel/mtb/trainer), `weightKg`, `tyreWidthMm`, `chainrings`/`cassette` and an optional `stravaGearId`; the root `BikeCostLog` gains `activeBikeId` and `indoorBikeId`. `StoredRide.gearId` is filled from Strava's `gear_id` on the activity list (`RIDES_SCHEMA_VERSION` 3 re-lists the past year once, so older archived rides get it too). `domain/bike/BikeGarage` (pure, JVM-tested) assigns a ride to a bike: gear-id match → for `VirtualRide` the indoor bike (explicit, else the first trainer-type bike) → the active bike (outdoor rides skip a trainer-type active bike when another exists). Consumers narrow the archive with `BikeGarage.ridesForBike` before their existing km math: `BikeCostCalculator.evaluateGarage` (cost overview), `MaintenanceCalculator.evaluate(..., garage)` for parts with a `MaintenanceComponent.bikeId`, and `TirePressureStatusLoader` when `TirePressureLog.bikeId` is set; a trainer-type bike always counts indoor rides, unlinked components/logs (or links to a deleted bike) keep the old all-rides + `includeVirtualRides` behaviour. The garage is the source of truth for bike weight and gearing: `BikeCostRepository` mirrors the active bike's known weight into `RiderProfileRepository.PREF_BIKE_WEIGHT_KG` and its gearing into the gear calculator's prefs on every write, and the settings screen writes an edited bike weight back to the active bike — so `RiderProfile` itself is unchanged. On the first load of a pre-garage file (`version` 1) `BikeGarage.migrate` runs once, losslessly: existing cost bikes keep everything (type guessed from the name), passport bikes not yet present by name are added, an empty garage gets a "Mijn fiets" from the profile weight/gear-calculator gearing, and the first non-trainer bike becomes active and takes over that weight/gearing; ids are deterministic and an unreadable file is never migrated over. UI: "Fietsgarage" screen (route list overflow menu), plus a bike picker in the maintenance-part and tyre-reminder dialogs. Phone-only; no wire-format change.

**Battery tracker** (`battery_status.json`, `data/battery/BatteryRepository`, issue #238): the rider's rechargeable devices (e-shifting, lights, power meter, head unit/sensor, other) with the last charge moment and a per-device recharge interval in days (kind-specific default, 0 = no reminder). Atomic writes with a static write lock. `domain/battery/BatteryStatusCalculator` (pure, explicit now) marks a device due `intervalDays` after its last charge; a device without a logged charge is never due. `service/BatteryReminderWorker` runs daily (WorkManager, scheduled at app start) and posts one notification per charge cycle (`reminderSentForChargeEpochSec`, only marked once the notification was actually shown so a denied permission retries); logging a new charge ("Opgeladen") re-arms it. Screen "Accu's" in the overflow menu. Reading sensor battery levels from the watch is out of scope for now. Phone-only; never part of the wire payload.

**Pain log** (`pain_log.json`, `data/pain/PainLogRepository`, issue #232): a JSON array of complaints, each with the body areas (`domain/pain/PainArea`: knee, lower back, neck/shoulders, saddle area, hands/wrists, feet, other), a severity 1–5, free-text bike and setup ("zadel 3 mm hoger"), a note, and optionally the archived Strava ride (`StoredRide.activityId`) it followed; without a ride the rider picks a date. Atomic writes with a static write lock. `domain/pain/PainPatternAnalyzer` (pure) summarises how often each area hurts and how badly on average. It groups by bike and by setup only when at least two different values were logged, and flags a long-ride pattern when complaint rides (at least 3 linked) average at least 20 % longer than all outdoor archived rides (at least 5; `VirtualRide` excluded). Screen "Pijnlogboek" in the overflow menu; bike and setup are pre-filled from the latest entry. Phone-only; never part of the wire payload.

**Recovery check** (`recovery_checks.json`, `data/recovery/RecoveryCheckRepository`, issue #183): a JSON array with at most one check per archived ride (`StoredRide.activityId` is the key; saving again replaces it): RPE 1–10, sleep quality 1–5 for the night before, optional hours slept (one decimal, at most 24) and a note. Values are clamped on save and on load; atomic writes with a static write lock. Tapping a ride in the "Ritten" archive opens its action list (Herstel-check, ride story, ride comparer); "Herstel-check" opens the check dialog, and the row shows the logged values. `domain/recovery/RecoveryTrendAnalyzer` (pure) joins checks with the archived rides (checks for rides no longer in the archive are skipped), adds a Foster session load (RPE × moving minutes), and compares the latest `min(5, n / 2)` checks with the same number before them (from 4 checks on). A direction counts from a 0.5 RPE / 0.3 sleep difference; RPE up while sleep goes down raises a rest warning. The "Herstel-trend" screen (`ui/recovery/RecoveryTrendActivity`, "Herstel" button in the archive) draws RPE and sleep (×2) for the last 30 checks and lists every check next to the ride's distance, moving time, speed, elevation and power. Part of the RIDES privacy category and the backup. Phone-only; never part of the wire payload.

**Sweat-loss estimator** (`sweat_loss_log.json`, `data/hydration/SweatLossStore`, issue #186): a flat JSON array of `SweatLossEntry` (UUID id, weight before/after in kg, fluid drunk in ml, duration in minutes, note, optional archived ride id or a picked date). Only the raw inputs are stored; `domain/hydration/SweatLossCalculator` (pure) derives everything on load: sweat loss = weight drop + fluid drunk (1 kg = 1 L; urine and food ignored, the screen asks to weigh without clothes after the toilet), sweat rate in L/h, body-mass change and a status band (gained / < 1 % / 1–2 % / ≥ 2 %). Input is validated (30–250 kg, 15 min–24 h, 0–20 L, weight change ≤ 10 % and no negative sweat) both in the dialog and on load. The personal advice uses the duration-weighted average sweat rate over all measurements, replaces 80 % of it, rounds to 50 ml and caps at 1000 ml/h (flagging riders who sweat more than they can absorb), and is also shown in 500 ml bottles per hour. The store takes its `File` directly (unit-tested without Android), writes atomically and moves a corrupt file aside to `.corrupt` on the next write, like `FavoriteStartPointStore`. Screen "Zweetverlies" in the overflow menu; picking an archived ride fills the duration from its elapsed time, and the rider-profile weight pre-fills "weight before". Included in backups and the privacy dashboard. Phone-only; never part of the wire payload.

**"Veilig thuis"-bericht** (`safe_home.json`, `data/safehome/SafeHomeRepository`, issue #231): an opt-in message to one chosen contact (picked via the system contact picker, so no READ_CONTACTS permission; or typed in) once a ride is finished. A ride counts as finished when it appears on Strava, because the phone has no other reliable end-of-ride signal. The regular ride-archive sync runs only on charging + Wi-Fi, which is too late, so `service/SafeHomeWorker` polls the Strava activity list every 15 minutes (WorkManager minimum, network required). The poll is only scheduled while the feature is on (`syncSchedule` on save and at app start), and each run makes one cheap list call. `domain/safehome/SafeHomeDecider` (pure) only reports rides that ended in the last 3 hours, after the feature was (re-)armed, and were not reported before; `VirtualRide` is skipped. Several qualifying uploads produce one message for the latest and are all marked reported. They are only marked after delivery succeeded. `service/SafeHomeSender` sends the SMS directly when automatic SMS is chosen and `SEND_SMS` is granted (asked only then; `android.hardware.telephony` declared optional). Otherwise it posts a high-priority notification whose tap opens the SMS app pre-filled. The message template supports `{km}` and `{naam}`. Phone-only; never part of the wire payload.

**Sunscreen check** (issue #229, `ui/sunscreen/SunscreenActivity`, `domain/weather/SunscreenAdvisor`): the Open-Meteo forecast (`data/weather/OpenMeteoClient`, keyless) now also requests `uv_index`, which `HourlyForecast.uvIndex` holds (NaN when missing). The rider picks a start (now, or a time today or tomorrow, within the 2-day forecast) and a ride duration. The check uses the phone's freshest cached location fix, falling back to the radius-mode last location. `SunscreenAdvisor` (pure) takes the peak UV over the ride window and maps it to the WHO bands (below 3 none, moderate SPF 30, high SPF 50, very high/extreme SPF 50+), with re-apply moments every 2 h of ride time while the UV index at that moment is still 3 or more. Optional reminders are one-time WorkManager jobs (`service/SunscreenReminderWorker`, one tag, so a new check replaces the old schedule): 15 min before a future start and at each re-apply moment. They fire offline because the forecast was fetched at check time. Watch-side reminders are out of scope (they would need a payload setting). Phone-only; never part of the wire payload.

**Best time to ride a climb** (issue #41, `domain/weather/ClimateNormals` + `BestTimeScorer`, `data/weather/ClimateCache`, shown on the climb screen): on tap, `OpenMeteoClient.fetchClimate` asks the keyless Open-Meteo archive API for the last 3 full calendar years of hourly `temperature_2m`, `wind_speed_10m`, `wind_direction_10m` and `precipitation` at the climb's foot (elevation-corrected to the climb's mean height, `timezone=auto` so hours are local). Hourly rather than daily data because the answer needs day-parts; the four variables gzip to a few hundred kB and the request runs once per location. `ClimateNormals` (pure) aggregates it to 12 months × 4 day-parts (ochtend 7–11, middag 11–15, namiddag 15–19, avond 19–22): mean temperature, mean wind speed, the share of those day-parts with ≥ 0.5 mm precipitation, and the mean "wind-from" vector, so the headwind along any climb bearing (foot → top) can be derived without refetching. That compact form (~2 kB) is cached as `climate/<lat>_<lon>.json` on a 0.05° grid, so nearby climbs share it and it works offline afterwards; opening the screen reads only the cache. `BestTimeScorer` (pure) gives every cell a 0–100 score: points off per °C outside 12–22 °C, per km/h wind above 12 km/h, for the rain chance and per km/h mean headwind; the best months are those within 8 points of the top month (ranges may wrap New Year), and the best day-part is the one with the highest mean score over those months. The cache is listed in the privacy dashboard (`PrivacyCategory.CLIMATE`) but left out of the backup zip (public, re-fetchable). Phone-only; never part of the wire payload.

**Temperature trend** (issue #153, route detail → "Temperatuurtrend tonen", `ui/routes/TemperatureTrendView`, `domain/weather/TemperatureTrend`, `domain/weather/TemperatureGrid`): the route is sampled with the rain-forecast sampler (`RouteSampler`, every 5 km, at most 25 points) and `OpenMeteoClient.temperatureUrl` fetches hourly `temperature_2m` for all samples in one request (3 forecast days, UTC). Each sample's elevation is interpolated from the route (`TemperatureTrend.elevationsAt`) and sent along so Open-Meteo corrects for height on climbs; when any elevation is unknown the parameter is left out entirely, because Open-Meteo reads `nan` as "no correction". The rider picks a start time (`TemperatureTrend.nextStart`: today, or tomorrow when already past). The ride duration is the passport's pacing-plan total, falling back to 25 km/h without a profile (`rideSeconds`), spread linearly over the distance. `TemperatureTrend.compute` (pure) reads each sample's own grid column at its arrival time, interpolating between hours; samples past the forecast are dropped and counted. `describe` gives start, finish, warmest and coldest point in Dutch; the view draws the line chart. On demand, not cached. Phone-only; never part of the wire payload.

**Loop wind direction** (issue #174, `domain/weather/LoopWindAdvisor` + `LoopWindAdvice`, button in `ui/routes/RouteDetailActivity`): the Open-Meteo forecast now also requests `wind_direction_10m` (`HourlyForecast.windDirDeg`, NaN when missing). The route only counts as a loop when start and finish lie within 1 km and within 10 % of the route length; otherwise the advice says the direction is fixed (checked before any network call). The wind is fetched at the route start and averaged over the next 3 hours (speed-weighted vector mean for the direction, plain mean for the speed). Scoring: on a closed loop the headwind summed over any stretch is the wind vector dotted with that stretch's displacement, and halfway is the same point both ways round, so a plain "headwind on the first half, tailwind on the second" split scores both directions identically. What reversing actually changes is which end of the loop is ridden last, so each direction is scored by the headwind component over its second half, weighted by a ramp from 0 at halfway to 1 at the finish; the lower (most tailwind) wins. Below 10 km/h wind the verdict is "calm", and a difference under 3 km/h is "either way". Reversing reuses the existing "Omgekeerde richting" copy. Phone-only; never part of the wire payload.

**Clothing advice** (issue #196, `ui/clothing/ClothingActivity`, `domain/weather/ClothingAdvisor`): same start/duration flow and location fallback as the sunscreen check, using the existing Open-Meteo hourly forecast. `ClothingAdvisor` (pure) computes a "feels like on the bike" temperature per ride hour: the KNMI/JAG-TI wind chill with a typical riding speed of 25 km/h added to the forecast wind (at or below 10 °C; above that the air temperature). The coldest hour picks a kit from six temperature bands (≥ 22, 17–22, 12–17, 8–12, 3–8, < 3 °C); notes add a rain jacket (≥ 50 % wear it, ≥ 30 % back pocket), a wind vest in strong wind, removable layers when the ride spans ≥ 6 °C, and an ice warning near freezing. On demand, nothing stored. Phone-only; never part of the wire payload.

**Air quality and pollen** (issue #197, `ui/airquality/AirQualityActivity`, `domain/weather/AirQualityForecast` + `AirQualityAdvisor`): same start/duration flow and location fallback as the sunscreen check, but against the keyless Open-Meteo air-quality API (`OpenMeteoClient#fetchAirQuality`, CAMS data: `pm2_5`, `pm10`, `european_aqi` and six pollen types, NaN when missing; pollen only exists for Europe and in season). `AirQualityAdvisor` (pure) takes the worst hour of the ride window: European AQI below 40 is fine, 40–60 moderate, 60+ a warning; each pollen type has its own moderate/high grains-per-m³ thresholds (e.g. grass 10/50, ragweed 5/20). The headline names what is bad, the detail lists AQI with its peak time, PM values, pollen per type and short tips for asthma or hay fever. On demand only, nothing stored. Phone-only; never part of the wire payload.

**Fuel planner** (issue #185, `ui/nutrition/FuelPlannerActivity`, `domain/nutrition/FuelPlanner`, `domain/nutrition/RideEffortEstimator`): per route (button "Voedingsplanner" on the route detail screen), how many bars, gels and bottles to bring. Ride time and work come from the existing pacing model: `RouteEffortProfileBuilder` tiles, climbs at the shared fatigue-aware climb power of `RouteAwareClimbEstimator`, the rest at the rider's ride intensity; descents of 4 % or steeper are coasted (no work) and descent speed is capped at 14 m/s. Without a complete rider profile it falls back to 25 km/h plus one hour per 1000 hm (work unknown). Ascent is the sum of rises in the stored elevation profile. `FuelPlanner` (pure) then applies simple guidelines: no food under 75 min, 45 g carbs/h up to 2.5 h, 60 g/h up to 4 h, 75 g/h beyond, +15 g/h at 500+ hm per hour, max 90 g/h, half as 40 g bars and the rest as 25 g gels; fluid 400 ml/h at 10 °C or colder rising to 600 ml/h at 20 °C and 900 ml/h at 30 °C (+30 ml/h per degree above, max 1200), +100 ml/h on hilly rides, scaled by body weight relative to 75 kg (clamped 0.8–1.25); bottles of 500 or 750 ml with two cages, the rest as refills; kcal = work in kJ (≈ 24 % efficiency) or 8 kcal/kg/h; electrolytes advised from 25 °C or 3 h. The temperature is prefilled with the Open-Meteo forecast at the route start averaged over the estimated ride when leaving now (`FuelPlanner.averageTemperature`), is always editable, and defaults to 18 °C when left empty (offline works). Nothing is stored. Phone-only; never part of the wire payload.

**Comeback plan** (`comeback_plan.json`, `data/comeback/ComebackPlanStore`, issue #226): a gradual build-up after a break or injury. Only the inputs are stored: the last ride before the break, the plan start and an injury flag. `domain/comeback/ComebackPlanner` (pure) recomputes the weeks from the ride archive on every open, so actual rides show up as the archive syncs. Baseline = the rider's own weekly hours, km, ride count and longest ride over the 8 weeks before the break (all ride types, including trainer). With fewer than 4 rides in that window it falls back to a cautious default of 3 h / 60 km / 2 rides per week. The break length sets the plan: fewer than 21 days is 2 weeks from 60 %; fewer than 42 is 3 weeks from 50 %; fewer than 84 is 4 weeks from 40 %; longer is 6 weeks from 30 %. An injury starts 10 points lower (minimum 20 %) and adds a week. Each week ramps linearly toward 100 %, scaling hours, km and the longest ride (at least 15 km) while keeping the ride frequency. The intensity guidance tightens at low percentages. A week more than 15 % over its target hours is flagged. A break is suggested from 14 days without a ride, but a plan can always be started (injury case). Screen "Terugkomstplan" in the overflow menu. Phone-only; never part of the wire payload.

**Fitness, fatigue and form** (issue #220, `ui/fitness/FitnessActivity`): `StoredRide` also keeps Strava's power summary (`avgWatts`, `weightedAvgWatts`, `deviceWatts`) from the activity list. When those fields were added, `RIDES_SCHEMA_VERSION` 2 made the next archive sync list the past year once more, so older rides get them too; that uses the cheap list endpoint only. `domain/training/TrainingLoad` gives each ride a TSS-style load: hours × intensity² × 100, with intensity clamped to 0.3-1.3. Intensity comes from the power-meter weighted average / FTP, else Strava's (estimated) average power / FTP, else an assumed 0.70 (0.60 for commutes, 0.50 for e-bikes, which never use power). `domain/training/FitnessCalculator` sums the load per local day and runs the standard exponential averages from the first archived ride: fitness CTL (42 days), fatigue ATL (7 days) and form TSB, which is yesterday's CTL minus ATL. It returns the last 90 days for a hand-drawn chart (`FitnessChartView`), the weekly CTL ramp, a form label (> 25 very fresh, > 5 fresh, >= -10 neutral, >= -30 productive, else overreaching) and how many rides had measured or estimated power. Computed on the fly, not persisted; phone-only.

**Climb training block** (issue #63, `ui/training/ClimbPeriodizationActivity`, `domain/training/ClimbPeriodizationPlanner`): a periodised block of three build weeks and one recovery week, built from known climbs. Candidates are the catalog climbs (deduped by `ClimbIdentity`) with at least one logged attempt; only when none has been ridden is the whole catalog used. The start load is the rider's recent weekly climb elevation gain from `RecoveryAdvisor` (same hm-on-climbs axis; default 600 hm without history, floor 200 hm). Build week k targets start × (1 + 0.10·k); the recovery week targets 0.6 × start. Intensity also progresses: candidates are sorted by `DifficultyScoreCalculator` (no fatigue term) and build week k may only use the easiest ceil(n·(k+1)/(weeks+1)), so the hardest allowed climb rises weekly; recovery uses the easiest third. Each week holds up to three sessions (recovery: two), each a distinct climb ridden N times (max 6), hardest allowed first, splitting the remaining target evenly. Planned and target hm are both shown because small climbs can undershoot. Computed on the fly, not persisted; phone-only, no wire-format change. The repeat-climb workout generator (#19) did not exist yet, so sessions are plain "N× climb" lines rather than exported workouts; the comeback plan (#226) is a separate post-break build-up.

**Training-load calendar** (issue #182, `ui/fitness/TrainingLoadCalendarActivity`): `domain/training/TrainingLoadCalendar` builds a Monday-aligned grid of the last 53 weeks ending today. Each local day sums the `TrainingLoad` of its archived rides (same TSS-style metric as fitness/form) and counts the climb attempts started that day. An attempt whose Strava activity is not in the ride archive also adds a lower-bound load (its time on the climb at an assumed intensity of 0.85); attempts of archived rides add no load, so nothing counts twice. Every day gets a fixed level (0 none, < 50 light, < 100 moderate, < 200 hard, else very hard), not scaled to the rider's own maximum, so colours stay comparable over time. The result also carries active days, total load, the longest streak of riding days and the busiest week. `TrainingLoadHeatmapView` draws the grid on a `Canvas` with fixed-size cells inside a `HorizontalScrollView` (scrolled to today); tapping a cell shows that day's details. Computed on the fly, not persisted; phone-only, no wire-format change.

**Favorite start points** (`favorite_start_points.json`, `data/planning/FavoriteStartPointStore`, issue #206): a flat JSON array of `FavoriteStartPoint` (UUID id, name, lat/lon, created-at) for fixed places like home, work or a parking spot. The store takes its `File` directly (pure, unit-tested without Android): add/rename/delete with name + coordinate validation, list sorted by name, atomic temp-file writes; a corrupt file reads as empty and is moved aside to `.corrupt` on the next write instead of being overwritten. Managed on the "Favoriete startpunten" screen (overflow menu; add from the cached phone location or by typing `lat, lon`, tap to rename/delete). The planning screens offer them as start point: the Hoogtemeter-doel suggestion (issue #68) gets a "Favoriet" button next to "Huidige locatie" / "Start van route", and the Meerdaagse toer (issue #67) start spinner lists "Vertrekken vanaf <naam>" after the climbs (the planner already accepts an arbitrary start coordinate). Included in backups and the privacy dashboard. Phone-only; never part of the wire payload.

**Packing lists** (`packing_lists.json`, `data/planning/PackingListStore`, issue #189): an ordered JSON array of `PackingList` (id, name, items with id/text/checked). A missing file loads three default lists — Training, Toerrit and Bikepacking — with typical items; nothing is written until the first edit, and once written an emptied set is not re-seeded. The store takes its `File` directly (unit-tested without Android): add/rename/delete lists, add/remove items (trimmed, max 120 chars), check/uncheck and "Alles uitvinken" to reset a list before the next ride; unknown ids change nothing. Atomic writes; a corrupt file reads as the defaults and is moved aside to `.corrupt` on the next write. Screen "Paklijst" in the overflow menu: a spinner picks the list, items are checkboxes (long-press removes), the toolbar menu manages lists. Registered in `PrivacyCategory.PACKING_LISTS` and the local backup. Phone-only; never part of the wire payload.

**Bike theft passport** (`bike_passports.json` + `bike_passport_photos/`, `data/bike/BikePassportStore`, issue #190): one `BikePassport` per bike with a required name and free-text brand, model, colour, frame number, purchase date/price, shop and distinguishing features (free text on purpose: they are copied into police/insurance forms as-is), plus photo filenames and an optional receipt (image or PDF). Separate from the cost overview's `Bike`, which is about km and money. The store takes its `File` directly (unit-tested without Android), trims fields, sorts by name and writes atomically; a corrupt file reads as empty and is moved aside to `.corrupt` on the next write. Picked photos are copied into `bike_passport_photos/` under UUID names (`BikePassportPhotoStore`, like `AttemptPhotoStore`); deleting a passport deletes its files and unreferenced files are cleaned up when the screen opens. "Delen" sends `domain/bike/BikePassportText` (only filled-in fields) plus every photo and the receipt through `ACTION_SEND_MULTIPLE`, via a FileProvider `files-path` scoped to that directory. Registered in `PrivacyCategory.BIKE_PASSPORTS` and `BackupArchive.INCLUDED_PATHS`. Screen "Fietspaspoort" in the overflow menu. Phone-only; never part of the wire payload.

**Badges** (issue #191, `domain/ride/BadgeCalculator`, `ui/goals/BadgesActivity`): fixed rules recomputed on every open from the ride archive and the climb attempts, never persisted, so a badge's date is the moment the rule was first met in the history (rides/attempts sorted by start; undated ones skipped). Rules: a ride of ≥ 100 km and ≥ 200 km, ≥ 2.000 hm in one ride, 1.000 and 10.000 km in total, 8.848 hm in total (Everest), 5 rides started before 07:00 local time, and 10 / 50 distinct climbs. All ride types count, including `VirtualRide`, like the yearly goal. "Alle klimmen in een regio" is realised through the rider's own collections: every collection with at least 2 climbs (explicit climb members plus all climbs of member routes, resolved to `ClimbIdentity` in `BadgesViewModel`) gets a "collectie compleet" badge once each of its climbs has an attempt. Earned badges show newest first with their date; the rest show progress, closest to done first. Screen "Badges" in the overflow menu. Phone-only; never part of the wire payload.

**Training advice after a climb** (issue #64, `domain/climb/ClimbPacingAdvisor`, shown on the climb screen under the seasonal comparison): analyses the latest attempt of the climb whose `segSplitSec` matches the current segment count and that has no route deviation (`ClimbPacingAdvisor.latestAnalyzable`). Each split is converted to the steady pedal power that rides that segment in that time (`FtpEstimator.impliedPowerWatts`, rider+bike mass from the profile or 80 kg), so the analysis compares effort rather than raw speed — a slow steep ramp is not a fade. Rules: power of the last distance third vs the first (work/time per third) ≥ 10 % lower → "begin de eerste N m rustiger" (N = first third, rounded to 100 m), ≥ 10 % higher → "de eerste N m mag harder", otherwise "gelijkmatig gedoseerd"; the segment furthest (≥ 15 %) below the attempt's average power is named with its km range and gradient (suppressed when it lies in the last third of a faded climb); and the segment with the largest loss (≥ 5 s) against `SegmentPrCalculator.bestSplits` is named. At most three tips; the domain returns typed tips and the UI formats Dutch string resources. Computed from already-stored attempts on load; phone-only; never part of the wire payload.

**PR chance before riding** (issue #58, `domain/climb/PrChancePredictor`): the climb screen shows "PR-kans vandaag: goede kans / matig / onwaarschijnlijk" with the main reasons. It combines existing models into a points score (each factor −2…+2; ≥ 2 good, 0–1 moderate, < 0 unlikely). *History* on this climb (clean attempts, PR as in `LogbookCalculator`): a PR set in the last 90 days +2, a recent best within 3 % of the PR +1, more than 8 % off −1, only 1–2 attempts +1. *Fitness* from `FitnessCalculator`: fitness (CTL) today ≥ 5 % above the PR day +1, ≤ 10 % below −1 (skipped when the PR predates the archive or falls in its first 42 days); form (TSB) fresh +1, productive −1, overreaching −2. *Weather* on the top for the current hour from the same Open-Meteo forecast as the summit weather (`OpenMeteoClient` + `ClimbEndpoints.top`): rain ≥ 50 %, wind ≥ 30 km/h, feels-like < 5 °C or > 28 °C each −1; otherwise 10–24 °C, < 20 km/h and < 30 % rain +1. The view model posts the offline prediction first and fetches weather on a separate thread; offline the weather simply drops out of the reasons ("Weer onbekend (offline)"). A climb without attempts shows that the first ride is a PR by definition. Phone-only; never part of the wire payload.

**Gear calculator** (issue #188, `domain/power/GearCalculator`, `ui/climbs/GearCalculatorActivity`): "Versnellingen berekenen" on the climb screen. Inputs are the chainrings ("50/34", max 3), a cassette (a known shorthand such as 11-34 or 10-44 from `GearCalculator.CASSETTES`, or an explicit sprocket list), the wheel circumference (default 2105 mm, 700x25c) and a target cadence (default 80 rpm); they are remembered in default SharedPreferences. The sustainable power on this climb comes from `ClimbTimeEstimator` with the rider profile (an incomplete profile falls back to 200 W / 80 kg, flagged as a rough estimate). `PowerSpeedSolver` gives the speed on the steepest segment (with that segment's surface Crr) and at the climb's average gradient; cadence per gear = speed / (circumference × chainring / sprocket) × 60. The screen lists the 12 easiest combinations (lowest ratio first) with both cadences and a verdict: whether the easiest gear reaches the target cadence on the steepest part, otherwise which sprocket on the small ring would (`ceil(cadence × circumference × chainring / (speed × 60))`), or a smaller chainring when that exceeds 52 teeth. Computed on the fly; phone-only; never part of the wire payload.

**Monthly challenge** (issue #192, `domain/ride/MonthlyChallengeCalculator`, `ui/goals/MonthlyChallengeActivity`): one challenge for the current calendar month, stored in default SharedPreferences (`data/ride/MonthlyChallengeRepository`: type, target and the `yyyy-MM` it applies to — in a new month it reads as "no challenge" so the rider picks a fresh one). Types: distinct climbs (unique `climbId`s among the month's climb attempts), elevation gain, distance and number of rides (from the ride archive, all ride types; floored so nothing unridden is claimed). Months are local calendar months in the device zone. The suggestion is the average over the previous 3 months + 10 %, rounded up to a friendly step (1 climb / 100 m / 25 km / 1 ride) and never below a floor (5 climbs / 1.000 m / 200 km / 4 rides). "Uitdaging kiezen" offers each type with its suggestion pre-filled; "Verras me" picks a random type at its suggested target. Progress shows current/target, a bar and a pace hint against the linear expectation for today. Screen "Maanduitdaging" in the overflow menu. Computed on the fly; phone-only; never part of the wire payload.

### Route list overflow menu

`res/menu/route_list_menu.xml` groups the ~50 phone-only screens into six nested submenus (Klimmen, Ritten & analyse, Training & doelen, Voor de rit, Fiets & materiaal, Data & app; issue #351). Only Sync (toolbar icon), Sorteer, Filter op status and Instellingen stay at the top level. The leaf item ids are unchanged, so `RouteListActivity.onOptionsItemSelected` still dispatches on them directly; a tap on a group header falls through to `super` and Android opens the submenu. `RouteListMenuStructureTest` parses the XML on the JVM and fails when the top level grows beyond 10 items, when a group has fewer than 2 entries, or when a leaf `action_*` id and the handler drift apart. New screens go into the fitting group, not the top level. "Overflow menu" in the paragraphs below means the matching group.

### Logbook view

`LogbookCalculator` aggregates the raw `StoredClimbAttempt` list into per-climb PR summaries and per-attempt delta-to-PR. The UI has two entry points:

- **ClimbLogbookActivity** (+ `ClimbLogbookViewModel`, `LogbookAdapter`): reachable from the route list overflow menu ("Logboek"). Shows all climbs with attempt counts and PRs.
- **"Historie" block on `ClimbDetailActivity`**: shows the attempt history and delta-to-PR for that specific climb.

### New components

| Class | Package | Role |
|---|---|---|
| `ClimbIdentity` | `domain/climb` | Route-independent climb key (bucketed coord + length) |
| `KnownClimbs` | `domain/climb` | Derives match endpoints + identity from a `StoredRoute` |
| `ClimbAttemptMatcher` | `domain/matching` | Maps an activity GPS track to an elapsed climb time |
| `StoredClimbAttempt` | `data/route` | Persisted attempt: climbId, activityId, `dateEpochSec` (activity start date), `elapsedSec` |
| `ClimbAttemptRepository` | `data/route` | JSON-file persistence + dedupe for `climb_attempts.json` |
| `LogbookCalculator` | `domain/climb` | Per-climb PR summaries + per-attempt delta-to-PR |
| `StravaActivitiesRepository` | `data/strava` | Fetch activity list + streams; first-sync (12-month) + incremental cursor |
| `ClimbLogbookActivity` | `ui/climbs` | Logbook screen reachable from route list overflow menu |

> **Watch / protocol boundary**: nothing in the Climb Logbook crosses to the watch side. No changes to `protocol/schema.json`, no changes to `ClimbPayloadBuilder`, and no new fields in the sync payload.

---

## garmin-onboard — on-watch parsing experiment

A fourth watch module, **datafield** type (added to an activity's data screens, not launched standalone — same as `garmin`/`garmin-surface`), own CIQ UUID `a0b1c2d3e4f50617a0b1c2d3e4f50617`. Receives raw geometry via `protocol/raw-route.md`, **push-only** — the phone pushes unprompted via a "Verstuur naar horloge" button, the watch never requests or lists routes. Runs the full pipeline on-watch: `RouteParser.mc` ports `ElevationSmoother`/`ClimbDetector`/`ClimbTrimmer`/`Segmenter`/`GradientColor` from the Android domain layer. Domain rules are identical (800 m / 3% thresholds, 2%-over-200 m trim, 8% segments, color cutoffs, 50 m alert), enforced by shared tests (`garmin-onboard/test/` and companion fixtures). Live tracking uses full-polyline nearest-point matching with 20 m hysteresis instead of calibration points; position arrives via `compute(info)`'s `Activity.Info.currentLocation` each activity tick (no separate `Position.enableLocationEvents` registration or manual redraw-gating — the system already refreshes `onUpdate` at the activity's own cadence, same as `garmin-surface`'s `SurfaceFieldView`). The single live screen is a 5 km terrain window (not a climb-only view) with an off-route banner overlay instead of silently-stale data — when GPS strays > 100 m from the route, the last-computed window keeps rendering with a visible "OFF ROUTE" banner, preventing the user from riding blind. This module deliberately inverts the "heavy compute on the phone" rule as a self-contained experiment. The three existing modules (`garmin`, `garmin-widget`, `garmin-surface`) and the v3 packed payload are untouched.

---

## Resolved decisions

Decisions taken from the original open-questions list. These are now load-bearing — change them only with a deliberate revisit, and update this section when you do.

- **Android language: Java.** Not Kotlin. All `.java` files, `build.gradle` (Groovy DSL), POJOs with `final` fields (or `record` if Java 16+ is on the table). Apply `paree-coding-conventions.md` with Java syntax in mind.
- **Local route store: JSON files on app-private storage.** No Room/SQLite. One JSON file per route under `getFilesDir()/routes/<routeId>.json`, plus a `catalog.json` index with `{ routeId, name, hash, climbCount, bbox }` entries for cheap listing and radius queries. Write atomically (temp file + rename). For radius queries, scan `catalog.json` and filter by haversine distance — for <1000 routes this is fine; only add a geohash index if profiling shows it's needed.
- **Background sync trigger: WorkManager periodic.** No Strava webhooks, no server-push. Run with charging + unmetered network constraints; user can force a manual sync from the UI.
- **Radius mode catalog: all climbs within a configurable km radius** of the phone's last known location at sync time (not nearest-N). The radius is a user setting. If the resulting set exceeds the watch's payload budget, the **phone** truncates closest-first and surfaces a warning — the watch must never need to know that truncation happened. The centre comes from `service/RadiusLocation` (issue #310): the freshest cached fix, remembered in the `last_lat`/`last_lon` prefs as fallback for background syncs (Settings refreshes it when radius mode is chosen and on "Sync now"). Without any known position the worker sends nothing and reports `no_location` instead of searching around 0,0.
- **Navigation handoff: via the Garmin Connect mobile app**, not via our Connect IQ datafield. The Android app exports/shares the source GPX to Garmin Connect (intent-based); Garmin Connect pushes the course to the watch; the user starts navigation from the watch UI. Our app does not implement course-push itself.
- **Custom metadata on watch: name only.** Free-form notes and tags stay on the phone and never enter the wire format. The user-supplied **route name** and **climb name** are the only user metadata that may be included in the payload, and only if they fit the byte budget — if not, the watch falls back to deriving a label (e.g. "Climb 2 of 5").
- **Shared protocol: JSON Schema as the source of truth.** `protocol/schema.json` is canonical. Java POJOs are **generated** from it (e.g. `jsonschema2pojo` Gradle plugin into a `protocol-java` build output). Monkey C has no mainstream JSON-Schema generator, so the Monkey C classes are **hand-written from the same schema** and kept in lockstep by a round-trip test on each side that loads a reference payload from `protocol/examples/` and re-serializes it. If the schema and the Monkey C classes drift, the round-trip test fails.

## Open design questions

_None at the moment — all design questions resolved. New questions should be added here as they come up during scaffolding._
