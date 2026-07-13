# Onboard ClimbPro: push-only transfer + 5 km terrain window

**Status:** approved design, supersedes the relevant sections of
`docs/superpowers/plans/2026-07-03-garmin-onboard-climbpro.md` (Tasks 6–10).
That plan is otherwise unchanged (Tasks 1–5: scaffold, RawRouteStore, elevation
smoothing, climb detection, false-flat trim, segmentation/color — all still
apply as written).

## Why

Two problems with the original plan:

1. **Pull model puts the request on the wrong side.** The watch had to run its
   own `LIST_ROUTES`/`LOAD_RAW_ROUTE` picker and initiate the transfer. The
   user wants the phone to be the one in control — pick a route on the phone,
   push it to the watch — matching how route selection already works for the
   v3 widget path.
2. **Off-route data went stale silently.** `OnboardClimbData.updatePosition`
   already set `offRoute = true` when the GPS fix was too far from the route,
   but `activeClimbIndex`/`activeSegmentIndex`/`progressInClimb` were simply
   frozen at their last values and the climb-detail screen kept rendering them
   as if still live — nothing told the rider the numbers were stale. Also, the
   climb-only view only ever showed rider-relevant terrain when it matched the
   ≥800 m / ≥3% climb definition; a short punchy rise (e.g. a Dutch dike) that
   doesn't meet that bar was invisible.

## What changes

### 1. Push-only wire protocol

Drop `LIST_ROUTES` and `LOAD_RAW_ROUTE` entirely. The only messages left are
phone → watch:

| Message | Fields |
|---|---|
| `{"type":"RAW_HDR","id","name","n":<pointCount>,"tot":<chunkCount>}` | announces a transfer; `n ≤ 6000` |
| `{"type":"RAW_CHUNK","id","seq":<0-based>,"lat":[...],"lon":[...],"ele":[...]}` | ≤ 250 points per chunk; lat/lon = degrees × 100000, ele = decimeters |

`MAX_RAW_POINTS = 6000`, `CHUNK_POINTS = 250` unchanged. No watch → phone
messages remain in this protocol.

**Watch side:** `OnboardApp`/`OnboardCommListener` only ever *receive*. On
`onPhoneMessage`, dispatch `RAW_HDR`/`RAW_CHUNK` into `RawRouteStore` exactly
as before. On app start, restore the last persisted route from Storage
(offline-first is unchanged); if none exists, the terrain view shows
"Geen route" until a push arrives. `OnboardRoutePickView` and
`OnboardRouteIndex` are deleted from the file map — they are no longer needed
since the watch never lists or requests routes.

**Phone side:** `OnboardRequestHandler` (the `LIST_ROUTES`/`LOAD_RAW_ROUTE`
responder) is replaced by `OnboardPushService`, a small class with
`pushRoute(StoredRoute route)` that calls the existing
`RawRoutePayloadBuilder.buildMessages(route)` and sends each message via
`ConnectIqClient.sendMessageToOnboard(...)` immediately, with no request to
respond to. `ConnectIqClient` keeps `sendMessageToOnboard`; it drops
`setOnboardRequestHandler`/app-event registration for inbound onboard
messages, since the phone never receives any.

**Trigger:** `RouteDetailActivity` gets a new button, "Verstuur naar horloge",
next to the existing `btnSelectRoute`/`btnShareToGarmin`. Its click handler
calls `viewModel.sendToOnboard(routeId)` → `OnboardPushService.pushRoute`.
This is a deliberate, explicit user action — not automatic on route selection
— so a transfer (up to 24 chunks) never happens by surprise.

### 2. Single "5 km terrain window" view replaces the climb-only screen

The climb-active vs. between-climbs split is gone. There is one view function,
rendering the elevation profile from `routeProgress` to
`routeProgress + 5000` m (clamped to route end), using the raw
`store.dist`/`store.ele` arrays already held by `RawRouteStore` — this is the
same data `drawOverview` already draws for the whole route, just windowed and
always the primary screen.

- Any climb segments whose distance range overlaps the window are colored
  with their existing `segColor` (yellow → red), drawn on top of the terrain
  line, exactly as `drawOverview` does today for the whole-route mini profile.
- Terrain outside a detected climb (a short dike ramp, a rolling stretch that
  doesn't clear 800 m / 3%) is drawn neutrally from the raw profile — it was
  always in `RawRouteStore`, just never rendered because the old view only
  drew climbs.
- A progress marker and "distance to next climb" / "no climbs in range" text
  work as before.
- **Off-route:** `updatePosition` keeps freezing `routeProgress` (unchanged
  hysteresis/off-route logic from the existing plan). The terrain window
  keeps rendering the last computed window with the progress marker frozen,
  plus an "Off route" banner drawn over it. When a fix matches back onto the
  route, `offRoute` clears and the window/marker resume updating live — no
  separate blank "off route" screen, the rider keeps rough context of where
  they were.
- Climb-start alert (`takeAlert`, 50 m radius, once-per-climb, vibration +
  tone) is unchanged — it depends on `routeProgress`/`alertedClimb`, not on
  which screen is shown.

## Impact on the existing plan's tasks

- **Tasks 1–5** (scaffold, `RawRouteStore`, `RouteParser` smoothing/detection/
  trim/segmentation/color): unchanged, implement as originally planned.
- **Task 6** (live tracking): `updatePosition`/`bestMatch`/hysteresis/
  off-route/alert logic unchanged. `updateProgress()` no longer needs to pick
  a screen mode, but still computes `activeClimbIndex`/`activeSegmentIndex`
  (used for alert bookkeeping and for coloring the active segment in the
  terrain window).
- **Task 7** (comm listener + route index + wiring): drop
  `OnboardRouteIndex`/route-list handling; comm listener becomes
  receive-only (`RAW_HDR`/`RAW_CHUNK` dispatch to `RawRouteStore`).
- **Task 8** (views): becomes one task — delete `OnboardRoutePickView`, build
  the terrain-window view (`drawWindow`) in `OnboardView.mc`, keep
  `OnboardDelegate` (back button, etc.).
- **Task 9** (`RawRoutePayloadBuilder`): unchanged; now called from
  `OnboardPushService` instead of `OnboardRequestHandler`.
- **Task 10** (Android wiring): `OnboardRequestHandler` + its
  `ConnectIqClient` request-handling plumbing is replaced by
  `OnboardPushService` + the `RouteDetailActivity` button +
  `RouteDetailViewModel.sendToOnboard`.

## Testing

Same TDD approach and harness as the rest of the plan
(`tools/run-monkeyc-tests.ps1` for Monkey C, Gradle JUnit for Android):

- Watch: replace `OnboardCommTest`'s `LIST_ROUTES`/`LOAD_RAW_ROUTE` cases with
  tests that feed `RAW_HDR`/`RAW_CHUNK` straight into `OnboardApp.processMessage`
  and assert `RawRouteStore` state. New `OnboardViewTest` cases for
  `drawWindow`: window clamped at route end, climb coloring inside the
  window, neutral terrain outside detected climbs, off-route banner drawn
  when `offRoute` is true.
- Android: `OnboardPushServiceTest` replaces `OnboardRequestHandlerTest` —
  assert `pushRoute` sends exactly `RawRoutePayloadBuilder.buildMessages(route)`
  via `sendMessageToOnboard`, in order, with no prior message required.
