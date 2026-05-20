# Implementation Plan — ClimbPro for Forerunner 255 Music

> **Note on plan location**: per the user's global CLAUDE.md, plans should live at `<project-root>\Documentation\ClaudePlans\<YYYY-MM-DD>_<topic>.md`. Plan-mode tooling writes here first; after approval, copy this file to `D:\Sources\Michel\Android App Sven\Documentation\ClaudePlans\2026-05-20_implementation-plan.md` before starting work.

## Context

The project is **fully designed but unimplemented**. The repository currently contains only `Idea.md` (product spec), `ARCHITECTURE.md` (design contract with all decisions resolved), `CLAUDE.md` (operating instructions), and `README.md`. No source code, no build files.

The product is a custom ClimbPro experience for the **Garmin Forerunner 255 Music**: phone does the heavy route analysis, watch only renders. Three modules (`android/`, `garmin/`, `protocol/`) communicating through a JSON-Schema-defined wire format.

All key technical decisions are locked in (see `ARCHITECTURE.md` §Resolved decisions): **Java** on Android, **JSON files** for persistence, **WorkManager periodic** for sync, **radius-by-km** for radius mode, **Garmin Connect handoff** for navigation, **name-only** user metadata on the watch, **JSON Schema-driven codegen** for the protocol (Java generated, Monkey C hand-written).

User scope decisions for this plan: **bottom-up phasing by module**, **full Strava OAuth + auto-import** in v1, **unit tests for domain + protocol round-trip** (no instrumentation/simulator scripts yet).

The intended outcome of executing this plan is a production-quality v1 that detects climbs from Strava-imported and GPX-imported routes, syncs them to the watch, supports both route-follow and radius modes, fires climb-start alerts on the watch, and survives the edge cases listed in `Idea.md`.

---

## High-level approach

Eight phases, executed in order. Each phase produces something testable on its own; no phase depends on a later one. The protocol is built first so both Android and Garmin sides have a stable contract to code against.

```
Phase 0  Tooling & scaffolding
Phase 1  Shared protocol (schema.json, examples, codegen wiring)
Phase 2  Android domain layer (pure Java, fully unit-tested)
Phase 3  Android data layer (JSON store, Strava OAuth, route cache)
Phase 4  Android service layer (WorkManager sync, Connect IQ wrapper)
Phase 5  Android UI layer (MVVM, all user features)
Phase 6  Garmin Connect IQ datafield (Monkey C)
Phase 7  Integration, polish, edge cases
```

Estimated total: **~5–7 weeks of focused part-time work** (25–37 days). Phase-by-phase estimates below.

---

## Phase 0 — Tooling & scaffolding

**Goal**: empty repo → buildable empty modules.

**Tasks**
- Create the directory tree from `ARCHITECTURE.md` §Project Structure (`android/`, `garmin/`, `protocol/`).
- Android: initialize Gradle project (Groovy DSL — `build.gradle`, not `.kts`), Java 17 target, AndroidX, minSdk 26 (Connect IQ Communications requires modern BLE), targetSdk current.
- Add `jsonschema2pojo` Gradle plugin wired to read `protocol/schema.json` and generate POJOs into a build-time source set.
- Garmin: install Connect IQ SDK, create empty Monkey C datafield project, set device profile to **Forerunner 255 Music** in `manifest.xml`. Confirm `monkeyc` builds an empty `.prg`.
- Add `.gitignore` (Android Studio + Gradle + `iq/` build artifacts + `local.properties`).
- Create empty `Documentation/ClaudePlans/` folder per user's global convention.

**Deliverables**
- `./gradlew assembleDebug` produces an empty-app APK.
- `monkeyc -o app.prg -f monkey.jungle -y <key>` produces an empty datafield `.prg` that launches in the simulator.
- Repo committed with scaffolding only.

**Files created**
- `android/app/build.gradle`, `android/build.gradle`, `android/settings.gradle`, `android/gradle.properties`, `android/gradle/wrapper/*`
- `android/app/src/main/AndroidManifest.xml` (minimal)
- `garmin/manifest.xml`, `garmin/monkey.jungle`, `garmin/source/App.mc` (skeleton)
- `protocol/` empty
- `.gitignore`, `Documentation/ClaudePlans/.gitkeep`

**Estimate**: 0.5–1 day · **Risk**: low · **Complexity**: low (boilerplate)

---

## Phase 1 — Shared protocol

**Goal**: canonical wire format defined, Java POJOs generating, reference examples in place.

**Tasks**
- Write `protocol/schema.json` (JSON Schema draft-07) covering the envelope and both modes:
  - Envelope: `routeId` (string, optional in radius mode), `mode` (`"route" | "radius"`), `name` (optional, string), `climbs` (array).
  - `Climb`: `startDistance` (int, meters, only in route mode), `endDistance` (int, only in route mode), `startLat`/`startLon` (float, only in radius mode), `length` (int, meters), `elevationGain` (int, meters), `avgGradient` (int, gradient × 10 — fixed point), `name` (optional string), `segments` (array).
  - `Segment`: `gradient` (int, fixed point ×10), `distance` (int, meters), `elevationGain` (int, meters), `colorIndex` (int 0–5).
- Write `protocol/colors.md` — gradient cutoff table from `Idea.md`. Six color indices 0–5 mapped to (light yellow, yellow, dark yellow, orange, dark orange, red). This is the single source of truth.
- Write `protocol/schema.md` — human-readable notes: byte-budget rationale, "why fixed-point gradients", change log header.
- Write `protocol/examples/route_mode_short.json`, `protocol/examples/route_mode_full.json` (matches `Idea.md` §Example JSON Payload), `protocol/examples/radius_mode.json`.
- Wire `jsonschema2pojo` in `android/app/build.gradle` to generate into `android/app/build/generated/source/jsonschema2pojo/`.
- Verify generated POJOs compile and a smoke test can deserialize each example.

**Deliverables**
- `protocol/schema.json` validates against draft-07.
- Each `protocol/examples/*.json` validates against `schema.json` (use a CI step or a JUnit test that loads the schema).
- Android module compiles with generated POJOs.

**Files created**
- `protocol/schema.json`, `protocol/schema.md`, `protocol/colors.md`
- `protocol/examples/route_mode_short.json`, `route_mode_full.json`, `radius_mode.json`
- `android/app/src/test/java/.../ProtocolRoundTripTest.java` (load each example, deserialize, re-serialize, assert structural equality)

**Estimate**: 1–2 days · **Risk**: medium (schema choices are load-bearing) · **Complexity**: medium

**Risks**: getting the byte budget wrong here costs rework everywhere. Prefer compact packed arrays once the schema is exercised by a realistic route (~5 climbs, ~13 segments each → eyeball the serialized size).

---

## Phase 2 — Android domain layer (pure Java, fully tested)

**Goal**: every domain rule (parse, smooth, simplify, detect, segment, color) implemented as pure functions with JUnit coverage. No Android dependencies in this layer — runs on plain JVM.

**Tasks**
- `domain/route/GpxParser.java` — parse GPX (use a small lib like `gpx-parser` or hand-roll with XmlPullParser). Output `List<RoutePoint>` with lat/lon/elevation.
- `domain/route/FitParser.java` — parse FIT (Garmin's `fit-sdk-java`). Same `List<RoutePoint>` output.
- `domain/route/ElevationSmoother.java` — moving average over elevation with configurable window.
- `domain/route/RouteSimplifier.java` — Douglas-Peucker with configurable epsilon.
- `domain/route/CumulativeDistance.java` — compute haversine distance between successive points; produce per-point cumulative distance.
- `domain/climb/ClimbDetector.java` — implement the rule: walking window, identify candidate climbs, then keep those with `length ≥ 800m && avgGradient ≥ 3%`. Output `List<Climb>`.
- `domain/segment/Segmenter.java` — split each climb into segments of 8% of climb length. Last segment may be short; cover the full climb with no gaps.
- `domain/segment/GradientColor.java` — read the cutoffs from `protocol/colors.md`-derived constants (or load from a generated source). Map a gradient to a colorIndex 0–5.
- `domain/matching/NearestPointFinder.java` — given a route polyline and a GPS point, find the nearest segment + projected distance. Used phone-side for previewing matching behavior (the watch has its own copy).
- Constants for `MIN_CLIMB_LENGTH_M = 800`, `MIN_AVG_GRADIENT = 0.03`, `SEGMENT_FRACTION = 0.08` live in one place — `domain/climb/ClimbConstants.java`. Grep guarantees enforced by tests.

**Tests** (all JUnit 5, no Android dependency)
- `GpxParserTest` — fixtures: a clean route, a route with missing elevation, a corrupted file.
- `FitParserTest` — same coverage with FIT fixtures.
- `ElevationSmootherTest` — checks moving average correctness, edge effects at start/end.
- `RouteSimplifierTest` — checks point count reduction and that the simplified line stays within epsilon of original.
- `ClimbDetectorTest` — synthetic routes:
  - one climb above threshold → 1 detected
  - climb below 800m → 0 detected
  - climb below 3% → 0 detected
  - back-to-back climbs separated by short flat → 2 detected
  - long climb with one short steep section → 1 detected, not 2
- `SegmenterTest` — invariants: `sum(segment.distance) == climb.length`, segment count in {12, 13}, no overlaps.
- `GradientColorTest` — exact mapping at boundaries (0.0%, 2.0%, 2.01%, 4.0%, ...).
- `NearestPointFinderTest` — known projection cases.

**Deliverables**
- All domain code with green tests.
- Test fixtures under `android/app/src/test/resources/routes/` (real GPX/FIT files — use a few real Strava routes).

**Files created** (under `android/app/src/main/java/<pkg>/domain/`)
- `route/{GpxParser,FitParser,RoutePoint,ElevationSmoother,RouteSimplifier,CumulativeDistance}.java`
- `climb/{Climb,ClimbDetector,ClimbConstants}.java`
- `segment/{Segment,Segmenter,GradientColor}.java`
- `matching/NearestPointFinder.java`
- Corresponding `*Test.java` files under `src/test/`.

**Estimate**: 4–6 days · **Risk**: medium (climb-detection edge cases are tricky) · **Complexity**: medium-high

**Risks**: Real-world GPX files have noise that synthetic tests miss. Plan to retest detection results against 5–10 real Strava routes manually after the unit tests pass.

---

## Phase 3 — Android data layer

**Goal**: persistent storage + Strava integration. Repositories cleanly hide both behind narrow interfaces.

**Tasks**
- `data/route/RouteRepository.java` — JSON-file persistence:
  - One file per route: `getFilesDir()/routes/<routeId>.json` containing the parsed `Route` + detected `Climb[]` + user-supplied `name`/`notes` separate from source data so they survive resync.
  - `catalog.json` index: `[{routeId, name, sourceHash, climbCount, bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon, climbStartCoords: [[lat,lon],...]}]`. Climb start coords in the index enable radius queries without loading every route file.
  - Atomic writes: write to `<file>.tmp`, then rename. Never overwrite the live file on a half-write.
- `data/strava/StravaAuthRepository.java` — OAuth 2.0 with PKCE. Use `AppAuth-Android` library; store refresh token in `EncryptedSharedPreferences`. Register a Strava API client and put `clientId` + `clientSecret` in `local.properties` (loaded via `BuildConfig`).
- `data/strava/StravaApiClient.java` — Retrofit client for the Strava routes API (`GET /athlete/routes`, `GET /routes/{id}/export_gpx`). Returns `Route` objects via the GPX parser from Phase 2.
- `data/strava/StravaRoutesRepository.java` — coordinates Auth + API + RouteRepository. `syncRoutes()` lists routes, diffs by source hash, downloads new/changed ones, parses → detects → segments → persists.
- `data/sync/SyncStateRepository.java` — tracks per-route sync status to the watch (last synced hash, last sync timestamp, retry count).

**Tests**
- `RouteRepositoryTest` (instrumented? no — keep Java-only using a temp dir): write + read round-trip, atomic write under simulated crash (write to tmp then assert main file unchanged).
- Strava code is integration territory; for unit tests, only test the response-mapping logic against canned JSON. Don't test the actual API.

**Deliverables**
- Routes can be persisted and listed.
- Strava OAuth completes end-to-end against the real Strava API (manual verification).
- Strava routes import + persist + are visible in the catalog.

**Files created**
- `data/route/{RouteRepository,RouteCatalogEntry}.java`
- `data/strava/{StravaAuthRepository,StravaApiClient,StravaRoutesRepository,StravaRouteDto}.java`
- `data/sync/{SyncStateRepository,SyncState}.java`
- `local.properties.example` documenting the Strava client ID/secret format.

**Estimate**: 3–5 days · **Risk**: medium-high (Strava OAuth + token refresh) · **Complexity**: medium

**Risks**: Strava API rate limits (100 req/15min, 1000/day). Plan for caching and back-off. Token refresh must be transparent to upstream callers.

---

## Phase 4 — Android service layer

**Goal**: background sync to the watch. Survives reconnects and partial sync.

**Tasks**
- `connectiq/ConnectIqClient.java` — wraps Garmin's Connect IQ Mobile SDK. Lifecycle: discover devices → connect to the user's FR255 Music → send messages → handle disconnects. Exposes a simple `sendPayload(byte[]) → Result` interface.
- `service/RouteSyncWorker.java` — `WorkManager` `Worker`. Periodic (e.g. every 6 hours) + on-demand. On run:
  1. Pull latest Strava routes (`StravaRoutesRepository.syncRoutes()`).
  2. For each route whose source hash changed or that has no sync state, regenerate the climb payload.
  3. If mode is route-follow: build a payload for the user-selected route only.
  4. If mode is radius: scan `catalog.json` for climbs within the configured km radius of last known location, assemble a radius-mode payload (truncate closest-first if over byte budget; surface a warning notification to the user if truncated).
  5. Send via `ConnectIqClient` with exponential backoff on failure.
  6. Update `SyncStateRepository`.
- `service/ClimbPayloadBuilder.java` — takes domain `Route` + `Climb[]` and produces the wire payload (generated POJOs from `protocol/schema.json`). Serialize via Jackson or Moshi. **Stay strictly within the protocol's int/fixed-point representation**; no floats in the wire payload.
- `service/RadiusModeAssembler.java` — for radius mode: query the catalog by haversine, sort by distance from current location, accumulate climbs until byte budget hit. Warning emitted if truncation occurs.
- `service/PayloadBudget.java` — single constant for the max watch-side payload size (start at 8 KB; tune in Phase 7 after measuring).

**Tests**
- `ClimbPayloadBuilderTest` — given a known `Route`, asserts a specific JSON output (byte-for-byte). Catches accidental encoding changes.
- `RadiusModeAssemblerTest` — synthetic catalog of N climbs at varying distances; assert correct truncation and warning emission.
- `RouteSyncWorkerTest` — instrumented test (Robolectric, no real WorkManager) verifying state transitions.

**Deliverables**
- A route imported from Strava ends up as a successfully-sent payload to the watch (verified in Connect IQ simulator with a stub receiver).

**Files created**
- `connectiq/{ConnectIqClient,ConnectIqState}.java`
- `service/{RouteSyncWorker,ClimbPayloadBuilder,RadiusModeAssembler,PayloadBudget}.java`

**Estimate**: 3–4 days · **Risk**: medium (Connect IQ Mobile SDK has rough edges around reconnect) · **Complexity**: medium

**Risks**: Connect IQ Mobile SDK's reconnect behavior is finicky. Plan for a manual "send sync" button in Phase 5 as a fallback if periodic sync proves flaky in the field.

---

## Phase 5 — Android UI layer

**Goal**: every user feature from `Idea.md` reachable from the app. MVVM with AndroidX Lifecycle + LiveData.

**Screens**
- **RouteListScreen** — list synced routes; "Import GPX" + "Sign in to Strava" + settings.
- **RouteDetailScreen** — show one route's climbs (mini-profile per climb), allow rename, edit notes (custom metadata), "Select for follow" button, "Share to Garmin Connect" button (intent to Garmin Connect with the GPX → navigation handoff).
- **SettingsScreen** — toggle between route-follow and radius mode (radius km input when in radius mode), Strava re-auth, sync now button, payload budget (advanced).
- **ClimbDetailScreen** — show climb profile, allow rename, color-coded segments.
- **StravaAuthScreen** — kicks off OAuth, returns to RouteListScreen.

**ViewModels**
- `RouteListViewModel`, `RouteDetailViewModel`, `SettingsViewModel`, `ClimbDetailViewModel`, `StravaAuthViewModel` — all use repositories from Phase 3.

**Tasks**
- Implement screens with XML + ViewBinding (Java).
- Wire up rename flow: rename writes to `RouteCatalogEntry.userName` / `Climb.userName` which is a separate field from source-derived `name`. Survives resync.
- "Import GPX" — system file picker (`ACTION_OPEN_DOCUMENT`) → `GpxParser` → `ClimbDetector` → `Segmenter` → `RouteRepository.save()`.
- "Share to Garmin Connect" — `Intent.ACTION_SEND` with the source GPX file's content URI, restricted to Garmin Connect's package (`com.garmin.android.apps.connectmobile`) if installed; fallback to chooser.

**Tests**
- ViewModel logic tested with JUnit + Mockito where it makes sense (e.g. that selecting a route updates the active route in `SyncStateRepository`).
- No Espresso tests per scope decision.

**Files created** (under `android/app/src/main/java/<pkg>/ui/`)
- `routes/{RouteListActivity,RouteListViewModel,RouteListAdapter}.java`
- `routes/{RouteDetailActivity,RouteDetailViewModel}.java`
- `climbs/{ClimbDetailActivity,ClimbDetailViewModel}.java`
- `settings/{SettingsActivity,SettingsViewModel}.java`
- `strava/{StravaAuthActivity,StravaAuthViewModel}.java`
- XML layouts and themes.

**Estimate**: 5–7 days · **Risk**: low-medium · **Complexity**: medium

**Risks**: Garmin Connect intent format isn't well documented — may need to test on a real phone.

---

## Phase 6 — Garmin Connect IQ datafield (Monkey C)

**Goal**: datafield runs on Forerunner 255 Music, decodes payloads from the phone, renders current/next climb in both modes, fires alerts.

**Tasks**
- `garmin/source/App.mc` — bootstrap; load most-recent payload from app storage at start.
- `garmin/source/data/PayloadDecoder.mc` — decode the JSON envelope written by phone; hand-mirror the protocol classes (`ClimbPayload`, `Climb`, `Segment`) in Monkey C. Verify against `protocol/examples/`.
- `garmin/source/data/ClimbCache.mc` — in-memory cache of decoded climbs. No re-allocation per tick — preallocate at sync time.
- `garmin/source/matching/RouteMatcher.mc` (route-follow mode) — nearest-point search against the route polyline; hysteresis (don't accept a closer point that would move progress backward by more than 20m). Outputs `(distanceAlongRoute, activeClimbIndex)`.
- `garmin/source/matching/NearbyClimbs.mc` (radius mode) — for each climb in the cache, compute haversine distance from current GPS to climb start; activate the climb when within 50m (the alert threshold also marks "entered climb").
- `garmin/source/audio/ClimbStartAlert.mc` — vibrate + play tone when within 50m of a climb start. Maintain a "fired" set keyed by climb index; reset on payload change. Idempotent across GPS jitter.
- `garmin/source/views/ClimbView.mc` — render current climb profile with color-coded segments, current-position marker, remaining distance, remaining elevation. Use precomputed segment positions; redraw **only on active-segment change** or significant position delta (e.g. 10m).
- `garmin/source/views/NextClimbView.mc` — mini profile of next climb + distance-to-start, length, avg gradient, total elevation.
- `manifest.xml` — `productId` for FR255 Music, Connect IQ Communications permission, background permission.

**Tests**
- Round-trip test in Monkey C: load each `protocol/examples/*.json` (bundled as resources), decode, re-encode (where applicable), assert structural equivalence. This is the Monkey C side of the protocol-drift check.
- Manual testing in the Connect IQ simulator with the FR255 Music profile, plus a fake-GPS trace replaying a known route.

**Deliverables**
- Datafield builds, runs in simulator, renders current climb when a payload is loaded.
- Alert fires once per climb when entering 50m radius.
- Radius mode activates a climb when the simulator's GPS approaches its start coordinate.

**Files created** (under `garmin/source/`)
- `App.mc`, `ClimbApp.mc`
- `data/{PayloadDecoder,ClimbPayload,Climb,Segment,ClimbCache}.mc`
- `matching/{RouteMatcher,NearbyClimbs}.mc`
- `audio/ClimbStartAlert.mc`
- `views/{ClimbView,NextClimbView}.mc`
- `manifest.xml`, `monkey.jungle`, `resources/strings.xml`, `resources/drawables.xml`

**Estimate**: 5–7 days · **Risk**: high (Monkey C is unfamiliar; FR255 Music has tight memory; rendering perf needs measurement) · **Complexity**: high

**Risks**: FR255 Music memory budget for datafields is ~64KB code + ~16KB data. Payload size must fit. Rendering perf must not drop GPS tick rate. Allocate time for memory profiling in the simulator.

---

## Phase 7 — Integration, polish, edge cases

**Goal**: real-world reliability. Hit every edge case in `Idea.md` §Edge Cases.

**Tasks**
- **Battery & memory measurement** — run a real 2-hour activity on a Forerunner 255 Music. Measure battery delta vs. baseline; measure peak memory in simulator. Tune redraw thresholds and payload size if needed.
- **Payload size measurement** — for a realistic catalog (50 routes, ~5 climbs each), measure radius-mode payload size. Adjust `PayloadBudget` constant if needed.
- **Edge cases**:
  - Leaving the route → matching enters "off route" state; current-climb view dims; resumes when re-entering.
  - Route reversal → detected by "progress moves backward more than threshold"; emit warning, don't crash.
  - GPS drift → hysteresis filter (already designed); add a unit test with a recorded drift trace.
  - Missing elevation data → `ElevationSmoother` treats nulls as "skip"; climb detection requires elevation, so routes without elevation are flagged as "no climbs detected" (not an error).
  - Corrupted GPX → `GpxParser` raises a typed exception; UI shows a clear error toast, doesn't crash.
  - Extremely long routes → `RouteSimplifier` is bounded; cap at e.g. 10,000 points after simplification; assert in tests.
  - Reconnects → `ConnectIqClient` retries with backoff; user can manually retry from the UI.
  - Sync failures → surface in UI, never crash; failed routes show a "retry" affordance in the route list.
- **Documentation pass** — update `README.md` with real build commands once they work, fill in `CLAUDE.md` toolchain section with the actual commands.
- **First on-device test** — install on a real phone + paired FR255 Music. Run through every user feature.

**Deliverables**
- One signed-off real-world ride with the system in use.
- All edge cases above have at least one test or a manual reproduction step.

**Files updated**
- `README.md`, `CLAUDE.md` — replace "intended" commands with measured ones.
- New tests under `android/app/src/test/java/.../edge/`.

**Estimate**: 3–5 days · **Risk**: medium (real-world surprises) · **Complexity**: medium

---

## Critical files (to be created — none exist yet)

This list is the canonical edit map; if a file isn't here, it's probably not needed.

| Concern | File |
|---|---|
| Protocol schema (canonical) | `protocol/schema.json` |
| Color cutoffs | `protocol/colors.md` |
| Reference payloads | `protocol/examples/*.json` |
| Domain constants | `android/app/src/main/java/<pkg>/domain/climb/ClimbConstants.java` |
| Climb detection | `android/app/src/main/java/<pkg>/domain/climb/ClimbDetector.java` |
| Segmentation | `android/app/src/main/java/<pkg>/domain/segment/Segmenter.java` |
| Color mapping | `android/app/src/main/java/<pkg>/domain/segment/GradientColor.java` |
| Route persistence | `android/app/src/main/java/<pkg>/data/route/RouteRepository.java` |
| Strava integration | `android/app/src/main/java/<pkg>/data/strava/*.java` |
| Sync worker | `android/app/src/main/java/<pkg>/service/RouteSyncWorker.java` |
| Payload builder | `android/app/src/main/java/<pkg>/service/ClimbPayloadBuilder.java` |
| Connect IQ wrapper | `android/app/src/main/java/<pkg>/connectiq/ConnectIqClient.java` |
| Watch route matching | `garmin/source/matching/RouteMatcher.mc` |
| Watch radius matching | `garmin/source/matching/NearbyClimbs.mc` |
| Watch render loop | `garmin/source/views/ClimbView.mc` |
| Watch alerts | `garmin/source/audio/ClimbStartAlert.mc` |

---

## Verification

End-to-end acceptance:

1. **Fresh install on a real phone** — sign in to Strava → routes appear → select one → "Share to Garmin Connect" → course shows on watch.
2. **Watch boots the datafield** — load a synced payload → ClimbView renders the first climb with colored segments.
3. **Simulated ride** (or real ride) — GPS approaches climb start → vibration + tone fire **exactly once** → progress marker advances → segment color highlights update at boundaries → NextClimbView shows the upcoming climb.
4. **Radius mode** — toggle in settings → set radius (e.g. 5km) → walk/drive within radius of a known climb start → climb activates on watch.
5. **Reload** — force-quit phone app, kill BLE, re-pair → on next sync, all routes still visible, climbs still synced.
6. **Edge cases**:
   - Import a corrupted GPX → app shows error, doesn't crash.
   - Import a GPX with no elevation → climbs list empty, no error.
   - Run a 200km route through detector → completes in reasonable time, no OOM.

Per-phase verification is described in each phase's "Deliverables" section above.

---

## Open risks

- **FR255 Music memory**: payload + decoder + cache + render state must fit. Plan to profile early in Phase 6; if tight, may need to drop optional fields from the payload (e.g. climb names).
- **Strava OAuth**: requires registering an app with Strava; client secret cannot be in the APK without obfuscation. For a personal-use app this is acceptable; if distributed, needs a backend proxy.
- **Connect IQ Mobile SDK reconnect**: known to be finicky. Manual sync button is the escape hatch.
- **Java vs Kotlin**: chosen, but most modern Android samples are Kotlin; expect occasional translation when reading docs.
- **Single-developer pace**: 25–37 days assumes focused part-time work. Real elapsed time depends on availability.
