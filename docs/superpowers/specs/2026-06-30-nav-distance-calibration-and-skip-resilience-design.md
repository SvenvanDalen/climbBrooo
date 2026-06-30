# Navigation-anchored distance + skip resilience — Design

**Date:** 2026-06-30
**Status:** Approved (design phase) — ready for implementation plan
**Module(s) touched:** `protocol/` (schema + examples), `android/` (ClimbPayloadBuilder), `garmin/` (climb datafield: ClimbData, ClimbProView, CommListener)

## Problem

The climb datafield matches the rider's position against the route using the
**activity odometer** (`Activity.Info.elapsedDistance`) as its progress axis,
corrected by sparse per-climb GPS calibration points. Two gaps:

1. **Distance accuracy.** The odometer rarely equals distance-from-route-start
   (the rider may start the activity before the route, the odometer drifts, GPS
   integrates noise). Between calibration points the climb-distance and the
   "distance to next climb" prediction can be meaningfully off. When the rider
   navigates the route as a Garmin **course**, Garmin already knows the true
   distance-along-course — but the datafield never reads it.

2. **Skipped climb stalls progression.** In `ClimbData.updateProgress`, a climb
   that has calibration geometry but was never physically entered
   (`climbEntered[i] == false`) stays the "next" climb forever. If the rider
   bypasses it (detour, road closure, deliberate skip), the loop `return`s on
   that climb on every tick and never advances to climb `i+1`. The watch is
   stuck on a climb the rider has already ridden past.

## Goals

- **G1:** When the rider navigates the selected route as a Garmin course, use
  the navigation distance-along-course as the progress axis, so climb distances
  and the next-climb prediction are accurate and continuously aligned — *with a
  safety mechanism so a wrong/absent course never corrupts matching.*
- **G2:** When the rider skips a climb, the watch abandons it (after ~1 km past
  its end, once the rider is confirmed back on the route further along) and
  continues with the remaining climbs.

## Non-goals

- No changes to radius mode (no route, no `rtl`, no navigation distance).
- No changes to the surface datafield (`garmin-surface/`) or the browse widget
  (`garmin-widget/`). Only the climb datafield (`garmin/`) changes on the watch.
- No new course-push: navigation handoff stays "phone shares GPX → Garmin
  Connect pushes the course" (locked decision). We only *read* the course's
  navigation distance if the rider chose to navigate.
- We do not detect *which* course the rider loaded. We infer agreement
  statistically (length gate + calibration agreement), never by course identity.

## Constraints (carried from CLAUDE.md / ARCHITECTURE.md)

- **Wire format lockstep.** Any wire change edits `protocol/schema.json`,
  `protocol/examples/*.json`, `service/ClimbPayloadBuilder.java`, **and** the
  Monkey C `CommListener.mc` **together**. `ProtocolRoundTripTest` validates the
  Java side against the schema; Monkey C is review-only. Never hand-edit
  generated Java (the builder hand-builds wire maps, so it is edited directly).
- **Heavy compute on the phone.** Route total length is computed phone-side and
  shipped; the watch only does a subtraction and comparisons per tick.
- **Battery.** No new per-tick allocation; redraws stay gated on segment change.
- **Offline-first.** All matching works with no phone connection (the payload,
  incl. `rtl`, is persisted under `active_payload` and restored at `onStart`).

---

## Feature 1 — Navigation-anchored distance

### Wire change: route total length `rtl`

Add one optional top-level integer to the **route-mode** envelope:

```json
{ "v": 3, "mode": "route", "routeId": "...", "rtl": 18452, "climbs": [ ... ] }
```

- **Key:** `rtl` — route total length in **whole metres** (integer).
- **Source (phone):** the last element of `route.distances` (cumulative
  per-point distance), rounded to an int. Omitted when `route.distances` is null
  or empty.
- **Producers:** `ClimbPayloadBuilder.buildRoutePayload` and
  `buildSingleClimbPayload` (both route-mode climb-datafield payloads).
  `buildSurfaceSectionPayload` and `buildRadiusPayload` do **not** add `rtl`.
- **Schema/examples:** add `rtl` as an optional integer to the route envelope in
  `protocol/schema.json`; add it to `protocol/examples/route_mode_full.json`
  (and any other route-mode example) so `ProtocolRoundTripTest` covers it.
- **Watch parse (`CommListener.onMessage`):** read `rtl` into
  `data.routeTotalLen` (0 when absent). Reset alongside the other envelope fields
  on every payload.

### Watch: choosing the progress axis (`ClimbProView.compute`)

Each tick, compute both candidate distances and pick one:

```
elapsed = info.elapsedDistance            // existing odometer axis
navDist = -1
if routeTotalLen > 0
   and info has :distanceToDestination
   and info.distanceToDestination != null:
       navDist = routeTotalLen - info.distanceToDestination
       if navDist < 0: navDist = 0

axis = data.chooseAxis(elapsed, navDist)   // returns the metres to match on
data.updateProgress(axis)
```

`navDist` is, by construction, a distance-from-route-start — the same reference
frame as `climbStartDist`/`climbEndDist` — so it is directly substitutable for
the odometer with no per-climb shifting.

### Watch: trust model (`ClimbData`)

A single one-way trust flag governs whether `navDist` is used. State on
`ClimbData`:

- `routeTotalLen` (int, 0 = unknown) — parsed from `rtl`.
- `navTrust` (enum-ish int): `UNKNOWN(0)`, `TRUSTED(1)`, `REVOKED(2)`. Starts
  `UNKNOWN`. Reset to `UNKNOWN` on every new payload.
- `navMaxToDest` (int) — largest `distanceToDestination` observed this ride,
  used by the length gate. Reset on payload.

**Axis selection — `chooseAxis(elapsed, navDist)`:**

- If `navDist < 0` (not navigating / no `rtl`) → return `elapsed`.
- Length gate (only while `UNKNOWN`): track `navMaxToDest = max(navMaxToDest,
  distanceToDestination)`. Once `navMaxToDest` is within `NAV_LEN_TOL` of
  `routeTotalLen` (tolerance = max(`NAV_LEN_TOL_M` = 500 m, 10% of
  `routeTotalLen`)) → set `navTrust = TRUSTED`. (Rationale: at/near the start of
  a correctly-loaded course, remaining-distance ≈ total route length. A course
  of a very different length never passes the gate.)
- If `navTrust == TRUSTED` → return `navDist`.
- Otherwise (`UNKNOWN` not yet gated, or `REVOKED`) → return `elapsed`.

**Calibration agreement (revocation) — in `checkCalibration`:**

When the rider passes within `CALIB_SNAP_M` of calibration point `k` of the
active climb, the point's true absolute route distance is known:

```
absCalibDist = climbStartDist0[ci] + calibDist[ci][k]
```

where `climbStartDist0` is the **original, unshifted** start anchor (see below).

- If `navTrust == TRUSTED` and `|navDist - absCalibDist| > NAV_DISAGREE_M`
  (150 m) → set `navTrust = REVOKED`. From this tick on, `chooseAxis` returns
  the odometer, and the existing odometer drift-correction resumes from a clean
  anchor.
- The revocation is **one-way**: once `REVOKED`, nav distance is never used
  again for this payload (no oscillation).

### Watch: no odometer-shifting while trusted

The existing calibration logic *shifts* `climbStartDist[ci]` to correct odometer
drift (`checkCalibration`, and the approach-align in `updateRouteMatch`). With a
trusted nav distance there is no drift to correct, and shifting the anchor would
corrupt the absolute reference. Therefore:

- Keep an immutable copy of the start anchors: `climbStartDist0[i]`, set when the
  payload is parsed and never mutated. Used to compute `absCalibDist` and (when
  trusted) to drive matching directly.
- When `navTrust == TRUSTED`:
  - `checkCalibration` does **not** shift `climbStartDist` or set
    `progressInClimb`; it only performs the agreement check and advances
    `calibIdx`.
  - The approach-align branch in `updateRouteMatch` does **not** shift
    `climbStartDist`; `climbEntered[i]` may instead be set directly when the
    trusted `axis` reaches `climbStartDist0[i]` (the course confirms arrival).
    The GPS approach snap still sets `climbEntered[i]` too (belt and braces).
- When not trusted (`UNKNOWN`/`REVOKED`): behaviour is **exactly today's** —
  odometer axis, GPS calib shifting, GPS approach-align. (`climbStartDist`
  starts each payload equal to `climbStartDist0`.)

> This cleanly separates the two regimes: a trusted absolute course-distance is
> matched as-is and only *validated* by calibration; the odometer is matched and
> *corrected* by calibration, as today.

### New constants (`ClimbData`)

| Constant | Value | Meaning |
|---|---|---|
| `NAV_LEN_TOL_M` | 500 | Floor of the length-gate tolerance (m) |
| `NAV_LEN_TOL_PCT` | 10 | Length-gate tolerance as % of `routeTotalLen` |
| `NAV_DISAGREE_M` | 150 | Nav-vs-calib disagreement that revokes trust (m) |

---

## Feature 2 — Skip resilience

### Behaviour

A climb is **skipped** when, without ever having been entered, the rider has
ridden ~1 km past its end *and is confirmed back on the route further along*.
Once skipped, `updateProgress` ignores it and advances to the next climb.

### State (`ClimbData`)

- `climbSkipped` — bool per climb. Reset to `false` for every climb on each new
  payload (alongside `climbEntered`/`calibIdx`).

### New constant

| Constant | Value | Meaning |
|---|---|---|
| `SKIP_MARGIN_M` | 1000 | Distance past a climb's end before it may be skipped (m) |

### Detection (`ClimbData.updateProgress`)

In the climb loop, before the existing `confirmed`/range checks, treat an
already-skipped climb as finished (skip over it). Then add the skip *decision*:

```
for i in 0..climbCount:
    if climbSkipped[i]: continue          // already abandoned → next climb

    confirmed = climbEntered[i] or calibCount[i] == 0

    // Skip decision: never entered, has GPS geometry, ridden well past, on route.
    if not confirmed
       and calibCount[i] > 0
       and axis > climbEndDist0[i] + SKIP_MARGIN_M
       and backOnRoute(i):
           climbSkipped[i] = true
           continue                       // move on to climb i+1

    ... existing confirmed/range logic ...
```

`climbEndDist0[i]` is the unshifted end anchor (parallel to `climbStartDist0`),
so the 1 km margin is measured against the true route geometry regardless of any
odometer shifting.

**`backOnRoute(i)` — "confirmed back on the route further along":**

- **Trusted nav mode** (`navTrust == TRUSTED`): the course distance inherently
  proves the rider is on the route, so `backOnRoute` is `true`.
- **Odometer mode:** require GPS confirmation that the rider has reached a
  *later* climb's geometry — i.e. some climb `j > i` has `climbEntered[j] ==
  true`, **or** the rider is currently within `APPROACH_SNAP_M` of a later
  climb's first calibration point. (The existing `updateRouteMatch` already sets
  `climbEntered[j]` on GPS arrival; this reuses that signal.) Without such
  confirmation the climb is **not** skipped — an off-route odometer overrun must
  not false-skip.

> Net effect: a deliberately-skipped climb is dropped only when the evidence is
> strong (1 km past *and* re-acquired on the route), while a rider who briefly
> diverges near a climb start and rejoins still gets that climb.

---

## Data flow (activity-time, route mode, updated)

```
GPS tick ──► compute():
   navDist = rtl - distanceToDestination (if navigating)
   axis    = chooseAxis(elapsed, navDist)   // trusted nav | odometer
        │
        ├─► updateProgress(axis)
        │      ├─ skip climbs flagged climbSkipped
        │      ├─ skip-decide: past end +1km & backOnRoute & never entered → climbSkipped
        │      └─ active climb / next climb / progress as before
        │
        └─► updateRouteMatch(lat, lon)
               ├─ on climb: checkCalibration
               │     • trusted → validate navDist vs absCalibDist; revoke on disagree
               │     • else    → shift climbStartDist (existing drift correction)
               └─ approach: confirm climbEntered (GPS snap; or trusted axis ≥ start)
```

## Testing strategy

All watch logic is exercised through the existing Monkey C unit harness
(`garmin/test/ClimbDataTest.mc`) — pure `ClimbData` methods, no SDK. New cases:

**Feature 1 (axis & trust):**
- `chooseAxis` returns odometer when not navigating (`navDist < 0`).
- Length gate: `navMaxToDest` within tolerance of `routeTotalLen` → `TRUSTED`,
  and `chooseAxis` returns `navDist`.
- Length gate fails for a course whose length differs > tolerance → stays
  `UNKNOWN`, returns odometer.
- Calibration agreement within `NAV_DISAGREE_M` keeps `TRUSTED`.
- Calibration disagreement > `NAV_DISAGREE_M` → `REVOKED`, subsequent
  `chooseAxis` returns odometer.
- While `TRUSTED`, `checkCalibration` does not shift `climbStartDist`.
- While odometer mode, existing shift behaviour unchanged (regression).

**Feature 2 (skip):**
- Never-entered climb with calib geometry, `axis` > end + 1 km, trusted nav →
  `climbSkipped` set, next climb becomes active/next.
- Same distance but odometer mode with **no** later-climb confirmation → **not**
  skipped (stays next).
- Odometer mode, later climb GPS-confirmed (`climbEntered[j>i]`) → skip allowed.
- A skipped climb is not re-activated even if the odometer/axis later passes
  back through its range.
- `climbSkipped` resets on a new payload.

**Phone / protocol:**
- `ProtocolRoundTripTest` covers `rtl` in `route_mode_full.json` (schema valid +
  live builder output valid).
- `ClimbPayloadBuilderTest`: `rtl` equals rounded last `distances` element;
  omitted when `distances` empty/null; absent from radius & surface payloads.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Rider navigates a *different* course of similar length | Length gate passes, but per-climb calibration disagreement revokes trust within the first climb |
| Rider navigates no course | `navDist < 0` → odometer, exactly as today |
| `distanceToDestination` unsupported on a tick | Treated as not-navigating → odometer fallback |
| Genuine off-route overrun near a climb start | `backOnRoute` requires later-climb confirmation (odometer mode); not skipped |
| Wire drift (Java vs Monkey C) | `rtl` added to schema + examples + builder + CommListener in one change; round-trip test guards Java |

## Documentation updates (same change)

- `Documentation/ARCHITECTURE.md`: update the activity-time data-flow box and add
  a short "Navigation-anchored distance & skip resilience (2026-06-30)" section.
- `protocol/schema.md`: document `rtl`.
- `README.md` / `HANDLEIDING.md`: note that navigating the route as a Garmin
  course improves on-watch distance accuracy.
