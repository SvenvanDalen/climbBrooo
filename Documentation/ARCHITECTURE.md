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
                                                                ├──► within 50 m of climb start? → audio/vibration (once per climb)
                                                                │
                                                                └──► active segment changed? → redraw
```

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

### Custom surface sections (phone-only)

Beyond auto-detected per-climb-segment and per-flat-segment surface types, the user can
manually mark an **arbitrary stretch** of a route with a surface type. These live in
`StoredRoute.surfaceSections` (`StoredSurfaceSection`: `startDistance`, `endDistance`,
`surfaceType`, all integer metres) and are managed from the route detail screen
(`RouteDetailActivity` → "Ondergrond-stukken").

They are **phone-only for now** — deliberately not serialised into the Connect IQ payload.
The distance-range shape is chosen so a future wire extension can carry them unchanged:
a packed `surfSec` array of `[startDistance, endDistance, surfaceType, …]` integers on the
route payload, added via `protocol/schema.json` first (then regenerated Java POJOs and a
hand-written Monkey C match), with overlap-resolution decided watch-side at that time.
Custom sections survive route re-import (preserved in `RouteRepository.saveRoute` like
flat-segment surfaces) and contribute to the catalog `surfaceTypes` index.

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

**Surface-sections datafield** (`garmin-surface/`, app ID `00112233...`): shows the user-defined surface section the rider is in (surface + remaining metres) and the next one. It receives a dedicated lean payload `{v:3, mode:"route", routeId, name, climbs:[], surfSec:[start,end,type, ...]}` built by `ClimbPayloadBuilder.buildSurfaceSectionPayload` from `StoredRoute.surfaceSections` (see `protocol/schema.json` `surfaceSections`). An empty `surfSec` is sent on purpose to clear stale sections. Auto-detected flat segments are deliberately **not** included (user decision 2026-06-10). Single-climb activation sends no surface payload — sections are route-relative.

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
