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
└── protocol/                      # Shared wire-format spec + codegen sources
    ├── schema.json                # Canonical JSON Schema — source of truth for Java codegen
    ├── schema.md                  # Human-readable notes on schema (rationale, byte budget, change log)
    ├── examples/                  # Reference payloads used by round-trip tests on both sides
    └── colors.md                  # Gradient → color table (single source of truth)
```

### Module dependencies (strict)

```
android ──────► protocol ◄────── garmin
```

`android` and `garmin` both depend on `protocol`. They **never** depend on each other directly. Any cross-side concept (payload shape, color thresholds, climb-detection constants) lives in `protocol`.

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
GPS tick ──► nearest-point search ──► hysteresis filter ──► progress update
                                                                │
                                                                ├──► within 30 m of next calib point? → snap progress (GPS drift correction)
                                                                │
                                                                ├──► within 50 m of climb start? → audio/vibration (once per climb)
                                                                │
                                                                └──► active segment changed? → redraw
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
per-segment surface edits (matched by climb `startDistance`, copied by segment index for
non-UNKNOWN values), so the seeding step is skipped to avoid clobbering them. This upholds
the "user customisation survives resync" rule for surfaces, the same way renames are kept.

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

### Route-independent climb identity

Each known climb is keyed by a `ClimbIdentity`: a bucketed start coordinate (0.001° grid) plus the climb length. This survives route renames, re-imports, and Strava route-ID changes, so attempts from different routes that share the same physical climb are grouped together. `ClimbIdentity` is derived from a `StoredRoute` via `KnownClimbs.fromRoute`.

### Matching (`ClimbAttemptMatcher`)

For each activity the matcher checks whether the GPS track passes through a known climb:

1. **Entry gate**: the track must come within a proximity threshold of the climb start. The timestamp of the earliest crossing is the attempt start.
2. **Exit gate**: the track must come within the same threshold of the climb end. The first sample at or after the entry that comes within the gate of the climb end marks the attempt end (`firstWithin`, the same earliest-match logic used for the entry).
3. **Length validation**: the matched segment length must be within ±25% of the known climb length. This rejects partial traversals (e.g. turning around mid-climb) while tolerating GPS drift.

Out-and-back rides are handled by picking the earliest gate entry: this ensures the ascent on an out-and-back ride is still matched (rather than dropped because a return pass shadowed it). `match()` records one attempt per activity per climb — it does not record separate outbound and return attempts.

### Persistence

Matched attempts are stored in `climb_attempts.json` under `getFilesDir()`, following the same JSON-file pattern used for routes. `ClimbAttemptRepository` deduplicates on `(climbId, activityId)` so re-running a sync never creates duplicate entries. Reads are on demand; writes are atomic (temp + rename).

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

## Resolved decisions

Decisions taken from the original open-questions list. These are now load-bearing — change them only with a deliberate revisit, and update this section when you do.

- **Android language: Java.** Not Kotlin. All `.java` files, `build.gradle` (Groovy DSL), POJOs with `final` fields (or `record` if Java 16+ is on the table). Apply `paree-coding-conventions.md` with Java syntax in mind.
- **Local route store: JSON files on app-private storage.** No Room/SQLite. One JSON file per route under `getFilesDir()/routes/<routeId>.json`, plus a `catalog.json` index with `{ routeId, name, hash, climbCount, bbox }` entries for cheap listing and radius queries. Write atomically (temp file + rename). For radius queries, scan `catalog.json` and filter by haversine distance — for <1000 routes this is fine; only add a geohash index if profiling shows it's needed.
- **Background sync trigger: WorkManager periodic.** No Strava webhooks, no server-push. Run with charging + unmetered network constraints; user can force a manual sync from the UI.
- **Radius mode catalog: all climbs within a configurable km radius** of the phone's last known location at sync time (not nearest-N). The radius is a user setting. If the resulting set exceeds the watch's payload budget, the **phone** truncates closest-first and surfaces a warning — the watch must never need to know that truncation happened.
- **Navigation handoff: via the Garmin Connect mobile app**, not via our Connect IQ datafield. The Android app exports/shares the source GPX to Garmin Connect (intent-based); Garmin Connect pushes the course to the watch; the user starts navigation from the watch UI. Our app does not implement course-push itself.
- **Custom metadata on watch: name only.** Free-form notes and tags stay on the phone and never enter the wire format. The user-supplied **route name** and **climb name** are the only user metadata that may be included in the payload, and only if they fit the byte budget — if not, the watch falls back to deriving a label (e.g. "Climb 2 of 5").
- **Shared protocol: JSON Schema as the source of truth.** `protocol/schema.json` is canonical. Java POJOs are **generated** from it (e.g. `jsonschema2pojo` Gradle plugin into a `protocol-java` build output). Monkey C has no mainstream JSON-Schema generator, so the Monkey C classes are **hand-written from the same schema** and kept in lockstep by a round-trip test on each side that loads a reference payload from `protocol/examples/` and re-serializes it. If the schema and the Monkey C classes drift, the round-trip test fails.

## Open design questions

_None at the moment — all design questions resolved. New questions should be added here as they come up during scaffolding._
