# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This repository currently contains only the project specification (`Idea.md`). **No source code, build files, or modules exist yet.** Any "build", "test", or "run" commands below describe the *intended* toolchain once scaffolding lands — they are not runnable today.

The first concrete task in this repo is to scaffold the three modules described in `Documentation/ARCHITECTURE.md`. Read `Idea.md` and `Documentation/ARCHITECTURE.md` before making structural decisions; they together define the product contract.

## What this project is

A custom ClimbPro-style system for the **Garmin Forerunner 255 Music**. The phone does all heavy route analysis; the watch only renders compact, precomputed climb data. Four components:

1. **Android companion app** (Java, MVVM + Repository, WorkManager) — parses GPX/FIT, detects climbs, segments them, syncs to the watch, integrates with Strava.
2. **Garmin Connect IQ datafield** (Monkey C) — renders current climb profile and next-climb preview, matches GPS to route in realtime.
3. **Garmin Connect IQ Onboard watch app** (Monkey C) — receives raw routes pushed from the phone, runs the full climb pipeline on the watch, renders a 5 km terrain window.
4. **Shared protocol** — compact climb payload schema used by both sides over the Connect IQ Communications API.

## User-facing features

The product surface (from `Idea.md`) — the app must support all of these:

- **Select a route to follow** — pick a synced route from a list; that route becomes the active one on the watch.
- **Radius mode** — instead of following a route, watch checks for any known climbs within a configurable radius of the current GPS position. **Architecturally distinct** from route-follow mode: there is no active route, just a watch-side index of climbs keyed by start coordinate. See `Documentation/ARCHITECTURE.md` for the two-mode data flow.
- **Rename routes and climbs** — user-supplied display names that survive resync (don't overwrite on re-import).
- **Custom metadata on routes** — free-form notes/tags/details. Stored on phone; whether any of it ships to the watch depends on payload budget.
- **Show current route/climb data on watch** — the live datafield views.
- **Start navigation to a selected route** — hand off to Garmin's navigation; the route must be loadable as a course on the watch.
- **Import a single climb/route from a GPX file** — manual GPX import alongside Strava sync.

Two modes (route-follow vs radius) means the wire format and the watch's local state must support **either an active route OR a set of nearby climbs** — design payloads with that in mind, not just the route case.

## Non-negotiable domain rules

These come from `Idea.md` and must be preserved across all implementations:

- **Climb definition**: ≥ 800 m length AND ≥ 3% average gradient. Both conditions required.
- **False-flat trim**: after a climb is detected, leading and trailing *vals plat* — a contiguous stretch averaging **< 2 %** gradient over **≥ 200 m** — is trimmed off so the climb starts/ends on real climbing. Never trim a climb below the 800 m minimum. Threshold lives in `ClimbConstants` (`FALSE_FLAT_MAX_GRADIENT`, `FALSE_FLAT_MIN_LENGTH_M`); logic in `domain/climb/ClimbTrimmer.java`.
- **Segmentation**: each detected climb is split into segments of exactly **8% of the climb length** (so every climb has 12–13 segments; do not hardcode a count).
- **Segment color mapping** (gradient → color): 0–2% light yellow, 2–4% yellow, 4–6% dark yellow, 6–8% orange, 8–10% dark orange, 10%+ red. Keep this mapping in one place — the shared protocol module — so phone and watch agree.
- **Climb-start alert**: vibration + sound, trigger **once per climb** within 50 m of start. Idempotency must survive GPS jitter (don't re-trigger if the user drifts back across the 50 m boundary).
- **Offline-first**: all activity-time logic must work with no phone/network connection. Sync is opportunistic, never blocking.

## Architecture in one paragraph

Routes arrive on the phone (Strava import, file pick, or Garmin Connect course detection). The phone parses GPX/FIT, smooths elevation (moving average), simplifies geometry (Douglas-Peucker), detects climbs against the rules above, segments them, and serializes a **compact** payload (integer distances in meters, gradients as fixed-point, packed segment arrays — minimize byte count, the watch is memory-constrained). The payload syncs to the watch over Connect IQ Communications API with incremental/retry semantics. On the watch, the datafield holds precomputed segment arrays and only does cheap per-tick work: nearest-point search against the route, hysteresis-filtered progress, and canvas redraws when the active segment changes.

**Heavy compute belongs on the phone. The watch only renders and matches GPS.** Any time you're tempted to compute something on the watch, check whether the phone can precompute it into the payload instead.

## Locked-in technical decisions

These are settled — don't reopen them without a deliberate revisit (see `Documentation/ARCHITECTURE.md` §Resolved decisions for full reasoning):

- **Android language: Java**, not Kotlin. POJOs with `final` fields; `record` if the project targets Java 16+.
- **Persistence: JSON files** under `getFilesDir()/routes/` + a `catalog.json` index. No Room, no SQLite.
- **Background sync: WorkManager periodic** with charging + unmetered constraints, plus a manual sync button.
- **Radius mode**: phone syncs all climbs within a user-configurable km radius of last known location. Phone truncates if over budget; watch is unaware of truncation.
- **Navigation handoff**: phone shares GPX to Garmin Connect (intent); Garmin Connect pushes the course to the watch. We do not push courses ourselves.
- **User metadata on watch**: route name + climb name only (if they fit). Notes/tags stay phone-side.
- **Protocol source of truth**: `protocol/schema.json` (JSON Schema) describes the **v3 packed wire format** (short keys, packed integer arrays). Java POJOs are **generated** from it (`generateProtocolPojos`), but the producer `service/ClimbPayloadBuilder` hand-builds the wire maps directly (it does not use the generated POJOs); Monkey C parsers are **hand-written** to match. `ProtocolRoundTripTest` validates both `protocol/examples/*.json` and the **live builder output** against the schema, so any Java-side drift fails CI — but the Monkey C parsers have no JVM harness and stay **review-only**, so when you change the wire format you MUST update `schema.json`, `protocol/examples/`, `ClimbPayloadBuilder`, **and** the Monkey C `CommListener.mc`/`SurfaceData.mc` together. Never hand-edit generated Java.
- **garmin-onboard exception**: the onboard module intentionally parses the raw route on the watch (experiment); the heavy-compute-on-phone rule still governs the other modules. Push-only wire contract in `protocol/raw-route.md` (phone → watch, triggered by a phone-side button — the watch never requests), constants duplicated in `RawRoutePayloadBuilder`/`RawRouteStore` — change together.

## Key cross-cutting concerns

- **Payload size**: the watch has tight memory. Prefer packed arrays over JSON objects with repeated keys; prefer enums/indices over color strings; prefer relative offsets over absolute coordinates where lossless.
- **GPS drift & route matching**: nearest-point search must use hysteresis (don't snap to a closer point if it would move progress *backwards* by more than a small threshold). Support route reversal and off-route states without crashing.
- **Edge cases that have bitten similar projects**: missing/zero elevation samples, corrupted GPX, extremely long routes (memory), Bluetooth reconnects mid-sync, course modified after partial sync. Handle these in the sync manager, not in the climb detector.
- **Battery on watch**: minimize redraws. Datafield should redraw only on segment change or significant position delta, not every GPS tick.

## Intended toolchain (not yet present)

Once scaffolded, expect:

- **Android**: Gradle Groovy DSL (`build.gradle`, not `.kts`). Commands: `./gradlew assembleDebug`, `./gradlew test`, `./gradlew :app:connectedDebugAndroidTest`. Single-test runs via `./gradlew test --tests <FQCN>`.
- **Connect IQ**: Monkey C SDK with `monkeyc` compiler and the Connect IQ simulator. Build via `monkeyc -o app.prg -f monkey.jungle -y <developer_key>`; run in simulator for Forerunner 255 Music device profile.
  - **Monkey C unit tests are runnable, not review-only.** Each watch module (`garmin`, `garmin-widget`, `garmin-surface`, `garmin-onboard`) has a `monkey-test.jungle` that adds `test/` to the source path and `(:test)` functions under `test/*.mc` (Toybox.Test). Build a test prg with `monkeyc -f <module>/monkey-test.jungle -o build/<module>-test.prg -y <key> -d fr255m --unit-test`, then run it in a running simulator with `monkeydo build/<module>-test.prg fr255m /t` (Windows uses `/t`, not `-t`). The simulator must be started first (`connectiq`). Each run prints a `PASSED (passed=N, failed=N, errors=N)` summary.
  - **One command for all four suites**: `pwsh -File tools/run-monkeyc-tests.ps1`. It auto-locates the active SDK (from `%APPDATA%\Garmin\ConnectIQ\current-sdk.cfg`) and developer key, launches the simulator if needed, builds + runs every module's test jungle, and exits non-zero on any build failure, test failure, or error. Current status: **168 tests pass** (garmin 73, garmin-widget 37, garmin-surface 26, garmin-onboard 32), including edge-case coverage (axis/trust gating, segment-boundary and past-segment clamps, calibration exhaustion/sequencing, off-route/radius early-returns, parser truncation + malformed-array + resync-stale-clear guards, surface checkpoint selection, raw-route chunking + out-of-order recovery, on-watch detection + trimming + segmentation + terrain-window rendering, datafield compute()/off-route-debounce/aborted-resync/orphaned-storage-slice coverage, widget sync-retry policy, per-segment VAM parse/fallback guards). This complements the JVM-side `MonkeyCSourceGuard` (braces/test-return lint) and the protocol lockstep guard — the source guard is a fast headless gate, the simulator run is the real assertion harness.
- **Shared models**: `protocol/schema.json` is canonical. Java POJOs are generated by a Gradle task (e.g. `jsonschema2pojo`) into the Android module's build sources; Monkey C classes are hand-written and kept in sync via round-trip tests against `protocol/examples/`.

When you add the first build file, **update this section with the actual commands** rather than leaving the intended ones in place.

## Team conventions

Follow `paree-coding-conventions.md` from the user's global `docu/` folder for code style — interpret with Java syntax in mind, since this project is Java, not Kotlin. Project-specific deviations (if any) should be documented here.

## Working in this repo

- The Forerunner 255 Music is the target device. Don't add features that require capabilities it lacks (e.g., touchscreen, color-rich rendering beyond what MIP supports). Check the device profile before assuming an API is available.
- When changing the wire payload format, edit `protocol/schema.json` first, regenerate Java POJOs, and update the Monkey C class by hand in the same change. Update `protocol/examples/` so the round-trip tests still pass. Asymmetric updates will silently break sync.
- When a change materially alters architecture or the wire format, update `Documentation/ARCHITECTURE.md` and `README.md` in the same change (per user's global instructions).
