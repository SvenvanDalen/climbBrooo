# ClimbPro for Garmin Forerunner 255 Music

A custom, near-native **ClimbPro** experience for the Garmin Forerunner 255 Music.
All heavy route analysis runs on an **Android companion app**; the watch only renders
compact, precomputed climb data and matches GPS to the route in realtime.

The product is fully **offline-first**: once a route is on the watch, the ride needs no
phone and no network. Sync is opportunistic.

> **Status — implemented.** This is a working, multi-module codebase: a full Android
> app (Java, MVVM), three Connect IQ apps (Monkey C), and a shared protocol module with
> generated Java POJOs and round-trip tests. Local toolchains (Android Studio, Connect IQ
> SDK, Strava API keys) still have to be installed before you can build — see
> **[HANDLEIDING.md](HANDLEIDING.md)** (Dutch, full install manual + function reference)
> or **[Documentation/SETUP.md](Documentation/SETUP.md)**.

---

## What it does

- **Detects climbs** in Strava routes and imported GPX files — a climb is `≥ 800 m`
  long **and** `≥ 3 %` average gradient (both required).
- **Trims false flats** (*vals plat*) off the start/end of each climb so it begins and
  ends on real climbing — but never trims a climb below 800 m.
- **Segments** each climb into a fixed number of slices and **color-codes** them by
  gradient (light yellow → red) using one shared table.
- **Syncs** a compact payload to the watch over the Connect IQ Communications API, with
  incremental resync, retry, and offline-first orchestration.
- **On the watch**: shows the live climb profile with progress, previews the next climb,
  shows the surface section you're on, and **alerts** (vibrate + tone) once per climb
  near the start.
- **Phone-side extras**: per-climb time estimate (fatigue-aware), pacing plan, and a
  climb logbook built from your Strava history.

---

## The four apps

| App | Folder | Tech | Role |
|-----|--------|------|------|
| **Android companion** | `android/` | Java, MVVM, WorkManager | Parse GPX/FIT, detect/segment climbs, Strava integration, sync orchestration, all phone-side analytics |
| **ClimbPro browse app** (watch) | `garmin-widget/` | Monkey C (`watch-app` + glance) | Browse synced routes/climbs, save them, mark one **active** |
| **ClimbPro datafield** (watch) | `garmin/` | Monkey C (`datafield`) | Render the active climb during a ride, match GPS, fire the start alert — fully offline |
| **Ondergrond datafield** (watch) | `garmin-surface/` | Monkey C (`datafield`) | Show the user-defined surface section you're on + the next one |
| **Shared protocol** | `protocol/` | JSON Schema (canonical) | Single source of truth for the wire format and domain constants |

The three watch apps have **separate, isolated storage**, so selecting an active route on
the browse app is relayed *through the phone* to the datafields. Setting the active
route/climb needs the phone reachable at that moment; the ride itself does not.

### Connect IQ app IDs (must match across phone + manifests)

| App | UUID |
|-----|------|
| Browse app (`garmin-widget`) — phone's sync counterpart | `fedcba9876543210fedcba9876543210` |
| Climb datafield (`garmin`) | `0123456789abcdef0123456789abcdef` |
| Surface datafield (`garmin-surface`) | `00112233445566770011223344556677` |

These are mirrored in each `manifest.xml` and in
`android/.../connectiq/ConnectIqAppId.java`. **For a public release, regenerate all three
to fresh UUIDs** (change the manifest and `ConnectIqAppId` together).

---

## User features

- **Select a route to follow** — pick a synced route; it becomes active on the watch.
- **Radius mode** — no fixed route; the watch alerts on any known climb within a
  configurable radius of your GPS position.
- **Rename routes and climbs** — names survive resync (kept separate from source data).
- **Custom notes/tags on routes** — phone-side only; not synced to the watch.
- **Custom surface sections** — mark an arbitrary stretch of a route with a surface type
  and optional name; rendered on the Ondergrond datafield.
- **Live data on the watch** — current/next climb, progress, surface section.
- **Start navigation** — hands the GPX to Garmin Connect, which pushes the course.
- **Import a single route/climb from a GPX file**.
- **Sort the route library** (import time or name); auto-refreshes after a Strava sync.
- **Estimated climb time** — per-climb / per-segment, from your FTP + weights, with
  per-surface rolling resistance and route-wide fatigue (W'-balance). Phone-only.
- **Pacing passport + live ghost** — per-climb target times synced to the watch;
  the climb datafield shows `+/−s` vs plan and a post-summit summary.
- **Climb Logbook** — per-climb attempt history + PRs from your Strava rides. Phone-only.

---

## Repository layout

```
.
├── android/                # Android companion app (Java, Gradle Groovy DSL)
│   └── app/src/main/java/nl/paree/climbpro/
│       ├── connectiq/      # Connect IQ Mobile SDK wrapper, payload codec, app IDs
│       ├── data/           # Repositories: route, strava, sync, rider profile, attempts
│       ├── domain/         # Pure logic: climb, segment, route, matching, power
│       ├── service/        # SyncOrchestrator, workers, payload builders, pacing
│       └── ui/             # MVVM screens: routes, climbs, settings, strava
├── garmin/                 # ClimbPro datafield (Monkey C)
├── garmin-widget/          # ClimbPro browse app + glance (Monkey C)
├── garmin-surface/         # Ondergrond (surface) datafield (Monkey C)
├── protocol/               # schema.json (canonical) + examples + colors.md + notes
├── Documentation/          # ARCHITECTURE.md, SETUP.md, CONNECTION.md, plans
├── docs/                   # garmin-widget-setup.md, superpowers plans & specs
├── HANDLEIDING.md          # Full Dutch manual: functions + complete install guide
└── CLAUDE.md               # AI-session operating instructions
```

`android` and `garmin*` both depend on `protocol`; they never depend on each other.

---

## Build & run (quick reference)

Full step-by-step (prerequisites, keys, sideloading, troubleshooting) is in
**[HANDLEIDING.md](HANDLEIDING.md)**.

```powershell
# Android companion app
cd android
.\gradlew.bat assembleDebug          # build debug APK
.\gradlew.bat test                   # run JVM unit tests
.\gradlew.bat :app:installDebug      # install on a connected phone

# Watch apps (Connect IQ SDK + a developer key)
monkeyc -o garmin\ClimbPro.prg        -f garmin\monkey.jungle        -y <key> -d fr255m
monkeyc -o garmin-widget\ClimbBrowse.prg -f garmin-widget\monkey.jungle -y <key> -d fr255m
monkeyc -o garmin-surface\Surface.prg -f garmin-surface\monkey.jungle -y <key> -d fr255m
```

Target device: **Garmin Forerunner 255 Music** (`fr255m`). Other devices are out of scope.

---

## Domain rules (non-negotiable)

These come from `Idea.md` and live in `domain/climb/ClimbConstants.java` + `protocol/`:

| Rule | Value |
|------|-------|
| Min climb length | `800 m` |
| Min average gradient | `3 %` |
| False-flat trim threshold | `< 2 %` over `≥ 200 m` (never below the 800 m min) |
| Segment color cutoffs | 0–2 % light yellow · 2–4 % yellow · 4–6 % dark yellow · 6–8 % orange · 8–10 % dark orange · 10 %+ red |
| Climb-start alert | vibrate + tone, once per climb, within `50 m` of start (jitter-safe) |
| Route-matching hysteresis | `20 m` |

Change a threshold in **one** place (`ClimbConstants` / `protocol/colors.md`), never inline.

---

## Documentation map

| File | What it covers |
|------|----------------|
| **[HANDLEIDING.md](HANDLEIDING.md)** | **Start here** — full function reference + complete install manual (Dutch) |
| [Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md) | Module boundaries, data flow, design contract |
| [Documentation/CONNECTION.md](Documentation/CONNECTION.md) | Phone ⇄ watch Connect IQ connection internals (Dutch) |
| [Documentation/SETUP.md](Documentation/SETUP.md) | Toolchain install checklist |
| [docs/garmin-widget-setup.md](docs/garmin-widget-setup.md) | Building + sideloading the browse widget (Dutch) |
| [protocol/schema.md](protocol/schema.md) | Why the wire format is shaped the way it is |
| [Idea.md](Idea.md) | Original product specification |
| [CLAUDE.md](CLAUDE.md) | AI-session operating instructions |

---

## Wire format in one glance

The watch payload is the **v3 packed format**: short keys and packed integer arrays to
fit the watch's tight memory (cap `4096 bytes` per message). Gradients are fixed-point
(`percent × 10`), colors are integer indices, and per-segment data is packed as
`segs = [distance, elevationGain, gradient×10, colorIndex, …]` (4 ints/segment).
`protocol/schema.json` is canonical; Java POJOs are **generated** from it
(`generateProtocolPojos`), Monkey C parsers are hand-written, and `ProtocolRoundTripTest`
validates both the examples and the live builder output against the schema. When you
change the wire format, update `schema.json`, `protocol/examples/`, `ClimbPayloadBuilder`,
**and** the Monkey C parsers together.
