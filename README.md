# Android App Sven — ClimbPro for Forerunner 255 Music

Custom ClimbPro-style climb analysis and visualization for the Garmin Forerunner 255 Music. Heavy route analysis runs on an Android companion app; the watch displays only compact, precomputed climb data.

> **Status**: design phase. The repository currently contains only specifications and design documents — no source code yet.

## Documentation

1. **[Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md)** — read first. Module structure, data flow, design contract.
2. **[CLAUDE.md](CLAUDE.md)** — operating instructions for AI-assisted development sessions; lists the non-negotiable domain rules.
3. **[Idea.md](Idea.md)** — original product specification (climb detection rules, payload format, edge cases).
4. **[Documentation/SETUP.md](Documentation/SETUP.md)** — local toolchain setup (Android Studio, Connect IQ SDK, Strava API).
5. **[Documentation/ClaudePlans/](Documentation/ClaudePlans/)** — historical and current implementation plans.

## What it does

- Detects climbs (≥ 800 m, ≥ 3% avg gradient) in Strava routes and imported GPX/FIT files.
- Splits each climb into segments of 8% of the climb length, color-coded by gradient.
- Syncs compact climb data to a Garmin Forerunner 255 Music via the Connect IQ Communications API.
- On the watch: shows current climb profile with live progress, plus a preview of the next climb.
- Triggers a vibration and tone when the rider approaches a climb start.

Everything works offline during the activity. The phone is only needed for sync, not during the ride.

The watch side consists of three Connect IQ apps for the Forerunner 255 Music: the **ClimbPro app** (`garmin-widget/`, a device app with a glance in the up/down loop) for browsing routes/climbs, saving them to the watch, and marking one as *active*; the **ClimbPro datafield** (`garmin/`) that renders the active climb during a ride (fully offline — the active payload is persisted on the watch); and the **Ondergrond datafield** (`garmin-surface/`) that shows the user-defined surface section you are riding plus the next one. Setting the active route/climb from the watch requires the phone to be reachable at that moment; the ride itself does not.

## User features

- Select a route to follow from the synced library.
- **Radius mode** — no fixed route; the watch alerts on any known climb within a configurable radius of the current GPS position.
- Rename routes and climbs; names survive resync.
- Add custom notes/tags to routes for personal context.
- Show live route/climb data on the watch.
- Start navigation to the selected route.
- Import a single climb or route from a GPX file.
- Sort the route library (by import time or name); the list auto-refreshes when a Strava sync finishes.
- **Estimated climb time** — per-climb and per-segment time estimates based on your FTP, body weight and bike weight (set these in Settings), with rolling resistance adjusted per segment surface (asphalt rolls faster than gravel/cobbles). The estimate accounts for fatigue across the whole route: climbs later in a hard route are estimated slower because earlier efforts have depleted W'. Ride intensity between climbs (configurable in Settings as % FTP) controls how much W' recovers on non-climb sections. Phone-only; not synced to the watch.
- **Pacing-paspoort & live ghost** — the route detail screen shows a pacing passport (total climbs, elevation, hardest climb, estimated total time, and a target time per climb). Those per-segment target times are synced to the watch (route mode), where the climb datafield shows live whether you are ahead of or behind your plan (`+/−s`) and a short summary after each summit.

## Components

| Component | Tech | Role |
|-----------|------|------|
| Android companion app | Java, MVVM, Repository, WorkManager | Route parsing, climb detection, segmentation, Strava integration, sync orchestration |
| ClimbPro app (watch) | Monkey C, Connect IQ SDK | Device app with glance; browse and save routes/climbs; set active route/climb |
| ClimbPro datafield (watch) | Monkey C, Connect IQ SDK | Render current/next climb, match GPS to route, fire alerts; fully offline once active payload received |
| Ondergrond datafield (watch) | Monkey C, Connect IQ SDK | Show user-defined surface section and next section during ride |
| Shared protocol | JSON Schema (canonical) → generated Java POJOs + hand-written Monkey C classes | Single source of truth for wire format and domain constants |

See `ARCHITECTURE.md` for the full breakdown.

## Build & run

Phase 0 scaffolding is in place but the toolchain needs to be installed locally before anything builds. See **[Documentation/SETUP.md](Documentation/SETUP.md)** for the step-by-step (Android Studio + Connect IQ SDK + Strava API registration).

Once SETUP.md is complete:

```powershell
# Android
cd android
.\gradlew.bat assembleDebug
.\gradlew.bat test

# Garmin datafield
cd garmin
monkeyc -o ClimbPro.prg -f monkey.jungle -y developer_key -d fr255m
```

Target device: **Garmin Forerunner 255 Music**. Other devices are out of scope for v1.
