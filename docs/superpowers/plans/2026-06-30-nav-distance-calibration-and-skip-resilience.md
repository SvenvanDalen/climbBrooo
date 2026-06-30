# Navigation-anchored distance + skip resilience + preflight pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the climb datafield match position against Garmin's navigation course distance (with a safety fallback), continue past deliberately-skipped climbs, and gate every build behind a preflight pipeline that runs all checks first.

**Architecture:** The phone ships the route total length (`rtl`) in the route payload. The watch derives distance-along-course (`rtl − distanceToDestination`) and uses it as the matching axis when a length-gate + calibration-agreement trust check passes, otherwise it falls back to today's odometer behaviour. A climb that is ridden ~1 km past without being entered (and the rider is confirmed back on the route) is marked skipped so progression continues. A `scripts/preflight.ps1` pipeline runs JVM tests (incl. a new wire-lockstep guard) and Monkey C compilation before `scripts/build.ps1` produces any artefact.

**Tech Stack:** Java (Android, Gradle Groovy DSL), Monkey C (Connect IQ datafield), JSON Schema (protocol), PowerShell (pipeline). Design spec: `docs/superpowers/specs/2026-06-30-nav-distance-calibration-and-skip-resilience-design.md`.

## Global Constraints

- **Wire-format lockstep:** any change to the wire format edits `protocol/schema.json`, `protocol/examples/*.json`, `android/.../service/ClimbPayloadBuilder.java`, **and** the Monkey C `garmin/source/CommListener.mc` in the same task. `ProtocolRoundTripTest` validates the Java side; the new lockstep guard checks the Monkey C side; Monkey C otherwise stays review-only.
- **Heavy compute on the phone, watch only renders/matches:** `rtl` is computed phone-side; the watch does only subtraction + comparisons per tick.
- **Battery:** no new per-tick object allocation; redraws stay gated on segment change.
- **Offline-first:** all watch matching works with no phone connection (payload incl. `rtl` is persisted under Storage `active_payload`).
- **Scope:** only the climb datafield (`garmin/`) changes on the watch. No changes to `garmin-widget/`, `garmin-surface/`, radius mode, or the surface payload.
- **Schema envelope has `"additionalProperties": false`** — a new top-level key must be added to `schema.json` before any example or builder output will validate.
- **Run JVM tests with:** `android/gradlew.bat test` (Windows). This is an Android module, so the `--tests` filter only works on the unit-test task: run a single class with `android/gradlew.bat :app:testDebugUnitTest --tests <FQCN>`.
- **Monkey C tests** compile with `monkey-test.jungle` and run in the Connect IQ simulator (`monkeydo`); they have no headless harness, so plan steps note when a test can only be verified in the simulator.

---

## Task 1: Wire — route total length `rtl`

**Files:**
- Modify: `protocol/schema.json` (route envelope properties)
- Modify: `protocol/examples/route_mode_full.json`
- Modify: `protocol/schema.md` (document `rtl`)
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java`
- Modify (Monkey C, review-only): `garmin/source/ClimbData.mc`, `garmin/source/CommListener.mc`

**Interfaces:**
- Produces: route-mode envelope key `rtl` (integer, whole metres) on `buildRoutePayload` and `buildSingleClimbPayload` output; **absent** from `buildRadiusPayload` and `buildSurfaceSectionPayload`.
- Produces (watch): `ClimbData.routeTotalLen` (Number, 0 when absent), parsed by `CommListener.onMessage`.

- [ ] **Step 1: Add `rtl` to the schema envelope**

In `protocol/schema.json`, inside the top-level envelope `properties` (next to `"name"`), add:

```json
    "rtl": {
      "description": "Route mode (optional): total route length in whole metres. The watch derives distance-along-course as (rtl - Activity.Info.distanceToDestination) when navigating.",
      "type": "integer",
      "minimum": 1
    },
```

- [ ] **Step 2: Add `rtl` to the route example**

In `protocol/examples/route_mode_full.json`, add `"rtl": 8000,` to the top-level object (after the `"name"` line; value is > the largest climb `ed` of 3000):

```json
  "name": "Full demo climb",
  "rtl": 8000,
  "climbs": [
```

- [ ] **Step 3: Write the failing builder test**

Append to `ClimbPayloadBuilderTest.java`:

```java
    @Test
    public void routePayloadHasRtlEqualToLastDistance() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
        StoredRoute r = routeFixture();
        r.distances = new double[]{0, 1000, 5000, 8421.6};
        com.fasterxml.jackson.databind.JsonNode p =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.buildRoutePayload(r));
        assertTrue("rtl present", p.has("rtl"));
        assertEquals(8422, p.get("rtl").asInt()); // rounded last distance
    }

    @Test
    public void radiusPayloadHasNoRtl() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
        com.fasterxml.jackson.databind.JsonNode p =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.buildRadiusPayload(routeFixture().climbs));
        assertTrue("no rtl in radius", !p.has("rtl"));
    }

    @Test
    public void routePayloadOmitsRtlWhenNoDistances() throws Exception {
        ClimbPayloadBuilder b = new ClimbPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
        StoredRoute r = routeFixture();
        r.distances = null;
        com.fasterxml.jackson.databind.JsonNode p =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.buildRoutePayload(r));
        assertTrue("rtl omitted when no distances", !p.has("rtl"));
    }
```

If `ClimbPayloadBuilderTest` lacks a `routeFixture()` helper or `assertEquals`/`assertTrue` imports, mirror the ones in `ProtocolRoundTripTest` (`import static org.junit.Assert.assertEquals;` etc.) and copy its `routeFixture()` into the test (or call the existing one).

- [ ] **Step 4: Run the test to verify it fails**

Run: `android/gradlew.bat test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest`
Expected: FAIL — `rtl present` assertion fails (builder does not emit `rtl`).

- [ ] **Step 5: Emit `rtl` in the builder**

In `ClimbPayloadBuilder.java`, add a private helper and call it from `buildRoutePayload` and `buildSingleClimbPayload` (route-mode only — do NOT touch `buildRadiusPayload` / `buildSurfaceSectionPayload`). In `buildRoutePayload`, after `payload.put("routeId", route.routeId);`:

```java
        putRouteTotalLength(payload, route);
```

Add the same line in `buildSingleClimbPayload` after its `routeId` put. Then add the helper:

```java
    /** Route-mode only: total route length (m), from the last cumulative distance. */
    private static void putRouteTotalLength(Map<String, Object> payload, StoredRoute route) {
        if (route.distances != null && route.distances.length > 0) {
            payload.put("rtl", (int) Math.round(route.distances[route.distances.length - 1]));
        }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `android/gradlew.bat test --tests nl.paree.climbpro.service.ClimbPayloadBuilderTest --tests nl.paree.climbpro.protocol.ProtocolRoundTripTest`
Expected: PASS — builder emits `rtl`, the updated example and live builder output validate against the schema.

- [ ] **Step 7: Parse `rtl` on the watch (Monkey C, review-only)**

In `garmin/source/ClimbData.mc`, add the field near the other envelope fields (after `var routeName = null;`):

```monkeyc
    var routeTotalLen = 0;    // route total length (m) from payload "rtl"; 0 = unknown
```

In `garmin/source/CommListener.mc` `onMessage`, after `data.routeName = msg.get("name");` add:

```monkeyc
        var rtl = msg.get("rtl");
        data.routeTotalLen = (rtl != null && rtl instanceof Toybox.Lang.Number) ? rtl : 0;
```

- [ ] **Step 8: Document `rtl` in the schema notes**

In `protocol/schema.md`, add one line under the route-envelope key list:

```
- `rtl` (route mode, optional): total route length in whole metres; the watch computes distance-along-course = `rtl - distanceToDestination`.
```

- [ ] **Step 9: Commit**

```bash
git add protocol/schema.json protocol/examples/route_mode_full.json protocol/schema.md android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderTest.java garmin/source/ClimbData.mc garmin/source/CommListener.mc
git commit -m "feat(protocol): add route total length (rtl) for nav-distance calibration"
```

---

## Task 2: Watch — immutable start/end anchors

**Files:**
- Modify: `garmin/source/ClimbData.mc`
- Modify: `garmin/source/CommListener.mc` (populate the anchors on parse)
- Test: `garmin/test/ClimbDataTest.mc`

**Interfaces:**
- Produces: `ClimbData.climbStartDist0[i]` / `climbEndDist0[i]` — the unshifted start/end anchors, set when a payload is parsed, never mutated by matching. `climbStartDist`/`climbEndDist` remain the working (possibly shifted) values, initialised equal to the `0` anchors.

- [ ] **Step 1: Write the failing test**

Add to `garmin/test/ClimbDataTest.mc`:

```monkeyc
(:test)
function anchors_mirrorStartEndOnParse(logger) {
    var d = new ClimbData();
    d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);   // helper sets both working + immutable anchors
    Test.assertEqual(d.climbStartDist[0], 1000);
    Test.assertEqual(d.climbEndDist[0], 1800);
    Test.assertEqual(d.climbStartDist0[0], 1000);
    Test.assertEqual(d.climbEndDist0[0], 1800);
    // Shifting the working value must not change the immutable anchor.
    d.climbStartDist[0] = 950;
    Test.assertEqual(d.climbStartDist0[0], 1000);
    return true;
}
```

- [ ] **Step 2: Verify it fails (simulator)**

Run: `monkeyc -f garmin/monkey-test.jungle -o ClimbProTest.prg -y developer_key -d fr255m --test` then `monkeydo ClimbProTest.prg fr255m -t`
Expected: FAIL/compile error — `climbStartDist0`, `climbEndDist0`, and `setAnchors` do not exist.
(If the Connect IQ SDK is not installed on this machine, this step is verified during execution of the preflight pipeline in Task 7; note the limitation and proceed.)

- [ ] **Step 3: Add the anchors and allocation**

In `ClimbData.mc`, declare the arrays beside `climbStartDist`/`climbEndDist`:

```monkeyc
    var climbStartDist0;  // immutable start anchor (never shifted) — absolute route distance
    var climbEndDist0;    // immutable end anchor (never shifted)
```

In `initialize()`, allocate and zero them alongside the existing arrays:

```monkeyc
        climbStartDist0 = new [MAX_CLIMBS];
        climbEndDist0 = new [MAX_CLIMBS];
```

and inside the existing `for (var i = 0; i < MAX_CLIMBS; i++)` init loop add:

```monkeyc
            climbStartDist0[i] = 0;
            climbEndDist0[i] = 0;
```

Add a small helper used by tests and by the parser:

```monkeyc
    // Sets both the working values and the immutable anchors for a climb.
    function setAnchors(i, startDist, endDist) {
        climbStartDist[i]  = startDist;
        climbEndDist[i]    = endDist;
        climbStartDist0[i] = startDist;
        climbEndDist0[i]   = endDist;
    }
```

- [ ] **Step 4: Populate anchors when parsing a climb**

In `CommListener.mc` `parseClimb`, replace the two lines:

```monkeyc
        data.climbStartDist[idx] = getInt(climbDict, "sd",  0);
        data.climbEndDist[idx]   = getInt(climbDict, "ed",  0);
```

with:

```monkeyc
        data.setAnchors(idx, getInt(climbDict, "sd", 0), getInt(climbDict, "ed", 0));
```

- [ ] **Step 5: Verify it passes**

Run the Monkey C test target as in Step 2.
Expected: PASS (and all existing `ClimbDataTest` cases still pass — they read `climbStartDist`, which is unchanged).

- [ ] **Step 6: Commit**

```bash
git add garmin/source/ClimbData.mc garmin/source/CommListener.mc garmin/test/ClimbDataTest.mc
git commit -m "refactor(watch): add immutable climb start/end anchors"
```

---

## Task 3: Watch — axis selection + trust (length gate)

**Files:**
- Modify: `garmin/source/ClimbData.mc`
- Modify: `garmin/source/ClimbProView.mc` (feed the chosen axis)
- Test: `garmin/test/ClimbDataTest.mc`

**Interfaces:**
- Consumes: `ClimbData.routeTotalLen` (Task 1), immutable anchors (Task 2).
- Produces: `ClimbData.chooseAxis(elapsed, navDist)` returning the metres to match on; trust state `navTrust` (`NAV_UNKNOWN=0`, `NAV_TRUSTED=1`, `NAV_REVOKED=2`); `navMaxToDest`. Reset on payload via `resetNavTrust()`.

- [ ] **Step 1: Write the failing tests**

Add to `garmin/test/ClimbDataTest.mc`:

```monkeyc
(:test)
function chooseAxis_notNavigating_returnsOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 8000;
    d.resetNavTrust();
    Test.assertEqual(d.chooseAxis(1234, -1), 1234); // navDist < 0 → odometer
    return true;
}

(:test)
function chooseAxis_lengthGatePass_returnsNavDist(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 8000;
    d.resetNavTrust();
    // distanceToDestination ~ full route at start → navMaxToDest within tolerance.
    // navDist = rtl - distToDest = 8000 - 7900 = 100; distToDest 7900 within 10% of 8000.
    var axis = d.chooseAxis(50, 100);
    Test.assertEqual(d.navTrust, d.NAV_TRUSTED);
    Test.assertEqual(axis, 100);
    return true;
}

(:test)
function chooseAxis_lengthGateFail_staysOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 20000;          // our route is 20 km...
    d.resetNavTrust();
    // ...but the navigated course is only ~8 km: distToDest peaks near 8000.
    var axis = d.chooseAxis(50, 12000); // navDist = 20000-8000; distToDest=8000 « 20000-tol
    Test.assertEqual(d.navTrust, d.NAV_UNKNOWN);
    Test.assertEqual(axis, 50);        // falls back to odometer
    return true;
}
```

- [ ] **Step 2: Verify it fails (simulator / preflight)**

Run the Monkey C test target (Task 2 Step 2).
Expected: FAIL/compile error — `chooseAxis`, `resetNavTrust`, `navTrust`, `NAV_*` undefined.

- [ ] **Step 3: Implement trust + axis selection**

In `ClimbData.mc` add constants near the other thresholds:

```monkeyc
    // Navigation-distance trust
    const NAV_LEN_TOL_M   = 500;   // length-gate tolerance floor (m)
    const NAV_LEN_TOL_PCT = 10;    // length-gate tolerance as % of routeTotalLen
    const NAV_DISAGREE_M  = 150;   // nav-vs-calib disagreement that revokes trust (m)
    const NAV_UNKNOWN = 0;
    const NAV_TRUSTED = 1;
    const NAV_REVOKED = 2;
```

Add state with the other runtime vars:

```monkeyc
    var navTrust = 0;        // NAV_UNKNOWN / NAV_TRUSTED / NAV_REVOKED
    var navMaxToDest = 0;    // largest distanceToDestination seen this ride (length gate)
```

Add methods:

```monkeyc
    function resetNavTrust() {
        navTrust = NAV_UNKNOWN;
        navMaxToDest = 0;
    }

    // Returns the distance (m) to match progress on this tick.
    //   navDist < 0  → not navigating → odometer.
    //   navTrust TRUSTED → navDist; else odometer (with length-gate evaluation while UNKNOWN).
    function chooseAxis(elapsed, navDist) {
        if (navDist < 0 || routeTotalLen <= 0) {
            return elapsed;
        }
        if (navTrust == NAV_UNKNOWN) {
            var distToDest = routeTotalLen - navDist;   // == info.distanceToDestination
            if (distToDest > navMaxToDest) { navMaxToDest = distToDest; }
            var tol = (routeTotalLen * NAV_LEN_TOL_PCT) / 100;
            if (tol < NAV_LEN_TOL_M) { tol = NAV_LEN_TOL_M; }
            if (navMaxToDest >= routeTotalLen - tol) {
                navTrust = NAV_TRUSTED;
            }
        }
        return (navTrust == NAV_TRUSTED) ? navDist : elapsed;
    }
```

- [ ] **Step 4: Reset trust on every payload**

In `CommListener.mc` `onMessage`, where `payloadReceived` is set and the per-climb reset loop runs (after `data.payloadReceived = true;`), add:

```monkeyc
        data.resetNavTrust();
```

- [ ] **Step 5: Feed the chosen axis from the view**

In `garmin/source/ClimbProView.mc` `compute`, replace the elapsed/`updateProgress` block:

```monkeyc
        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
```
... keep, then immediately after computing `elapsed`, before `data.updateProgress(elapsed);`, insert:

```monkeyc
        var navDist = -1;
        if (data.routeTotalLen > 0
                && info has :distanceToDestination && info.distanceToDestination != null) {
            navDist = data.routeTotalLen - info.distanceToDestination.toNumber();
            if (navDist < 0) { navDist = 0; }
        }
        var axis = data.chooseAxis(elapsed, navDist);
```

and change `data.updateProgress(elapsed);` to `data.updateProgress(axis);`.

- [ ] **Step 6: Verify it passes**

Run the Monkey C test target.
Expected: PASS for the three `chooseAxis_*` cases; existing cases unaffected.

- [ ] **Step 7: Commit**

```bash
git add garmin/source/ClimbData.mc garmin/source/ClimbProView.mc garmin/source/CommListener.mc garmin/test/ClimbDataTest.mc
git commit -m "feat(watch): nav-distance axis selection with length-gate trust"
```

---

## Task 4: Watch — calibration agreement + no-shift while trusted

**Files:**
- Modify: `garmin/source/ClimbData.mc`
- Test: `garmin/test/ClimbDataTest.mc`

**Interfaces:**
- Consumes: `navTrust`, `NAV_DISAGREE_M`, immutable anchors, the per-tick `navDist` (the view passes it in via a new `navDistThisTick` field set in `compute`).
- Produces: revocation of `navTrust` to `NAV_REVOKED` on calibration disagreement; `checkCalibration` does not shift `climbStartDist`/`progressInClimb` while `NAV_TRUSTED`.

- [ ] **Step 1: Write the failing tests**

Add to `garmin/test/ClimbDataTest.mc`:

```monkeyc
(:test)
function calib_agreementWithinTol_keepsTrusted(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;
    d.activeClimbIndex = 0;
    d.navDistThisTick = 1400;            // absCalib = 1000 + 400 = 1400 → agree
    d.checkCalibration(52.0f, 5.0f);     // within snap radius
    Test.assertEqual(d.navTrust, d.NAV_TRUSTED);
    Test.assertEqual(d.climbStartDist0[0], 1000);   // anchor untouched
    Test.assertEqual(d.climbStartDist[0], 1000);    // NOT shifted while trusted
    return true;
}

(:test)
function calib_disagreementRevokesTrust(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;
    d.activeClimbIndex = 0;
    d.navDistThisTick = 1700;            // absCalib 1400, off by 300 > 150 → revoke
    d.checkCalibration(52.0f, 5.0f);
    Test.assertEqual(d.navTrust, d.NAV_REVOKED);
    return true;
}

(:test)
function calib_odometerMode_stillShifts(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;          // odometer mode
    d.activeClimbIndex = 0;
    d.lastElapsedDistance = 1450;        // GPS says we're at the 400 m calib point
    d.checkCalibration(52.0f, 5.0f);
    Test.assertEqual(d.progressInClimb, 400);
    Test.assertEqual(d.climbStartDist[0], 1050);  // shifted: 1450 - 400 (existing behaviour)
    return true;
}
```

- [ ] **Step 2: Verify it fails (simulator / preflight)**

Run the Monkey C test target.
Expected: FAIL — `navDistThisTick` undefined; `checkCalibration` shifts unconditionally so `calib_agreementWithinTol_keepsTrusted` fails on the un-shifted assertion.

- [ ] **Step 3: Add the per-tick nav distance field and set it from the view**

In `ClimbData.mc` runtime vars add:

```monkeyc
    var navDistThisTick = -1;   // navDist for the current tick (-1 = not navigating); set by view
```

In `ClimbProView.mc` `compute`, right after computing `axis` (Task 3 Step 5), add:

```monkeyc
        data.navDistThisTick = navDist;
```

- [ ] **Step 4: Branch `checkCalibration` on trust**

In `ClimbData.mc`, replace the body of `checkCalibration` after the proximity test so it validates (trusted) or shifts (odometer):

```monkeyc
    function checkCalibration(lat, lon) {
        if (activeClimbIndex < 0) { return; }
        var ci = activeClimbIndex;
        var k  = calibIdx[ci];
        if (k >= calibCount[ci]) { return; }

        var dm = distM(lat, lon, calibLat[ci][k], calibLon[ci][k]);
        if (dm < CALIB_SNAP_M) {
            if (navTrust == NAV_TRUSTED) {
                // Validate the trusted course distance against the known absolute distance.
                var absCalib = climbStartDist0[ci] + calibDist[ci][k];
                var diff = navDistThisTick - absCalib;
                if (diff < 0) { diff = -diff; }
                if (navDistThisTick >= 0 && diff > NAV_DISAGREE_M) {
                    navTrust = NAV_REVOKED;
                }
                // Do NOT shift climbStartDist / progressInClimb: navDist is already absolute.
            } else {
                // Odometer mode: correct drift by shifting the working start anchor.
                climbStartDist[ci] = lastElapsedDistance - calibDist[ci][k];
                progressInClimb    = calibDist[ci][k];
                updateCurrentSegment();
            }
            calibIdx[ci] = k + 1;
        }
    }
```

- [ ] **Step 5: Do not shift on approach while trusted**

In `updateRouteMatch`, guard the approach-align shift so it only runs in odometer mode (the GPS confirmation of `climbEntered` still runs in both modes):

```monkeyc
            if (actual <= APPROACH_SNAP_M) {
                climbEntered[ni] = true;
                if (navTrust != NAV_TRUSTED) {
                    climbStartDist[ni] = lastElapsedDistance - calibDist[ni][0];
                }
            }
```

- [ ] **Step 6: Verify it passes**

Run the Monkey C test target.
Expected: PASS for all three new cases; the existing `checkCalibration_nearPoint_snapsProgress` case (odometer mode) still passes.

- [ ] **Step 7: Commit**

```bash
git add garmin/source/ClimbData.mc garmin/source/ClimbProView.mc garmin/test/ClimbDataTest.mc
git commit -m "feat(watch): validate nav-distance via calibration, revoke on disagreement"
```

---

## Task 5: Watch — skip resilience

**Files:**
- Modify: `garmin/source/ClimbData.mc`
- Modify: `garmin/source/CommListener.mc` (reset `climbSkipped` on payload)
- Modify: `Documentation/ARCHITECTURE.md` (data-flow + new section)
- Test: `garmin/test/ClimbDataTest.mc`

**Interfaces:**
- Consumes: immutable anchors, `navTrust`, `climbEntered`, `calibCount`, `APPROACH_SNAP_M`.
- Produces: `ClimbData.climbSkipped[i]` (bool), `SKIP_MARGIN_M`, `backOnRoute(i)`; `updateProgress` ignores skipped climbs and marks new ones.

- [ ] **Step 1: Write the failing tests**

Add to `garmin/test/ClimbDataTest.mc`:

```monkeyc
(:test)
function skip_trustedNavPastEnd_marksSkippedAndAdvances(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);   // climb 0
    d.setAnchors(1, 4000, 5000);   // climb 1
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;            // on the course → back-on-route is implicit
    d.updateProgress(2900);               // 1100 m past climb-0 end, never entered
    Test.assertEqual(d.climbSkipped[0], true);
    Test.assertEqual(d.nextClimbIndex, 1); // progression advanced to climb 1
    return true;
}

(:test)
function skip_odometerNoLaterConfirm_doesNotSkip(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;            // odometer mode, no later climb confirmed
    d.updateProgress(2900);               // odometer past end, but might be off-route
    Test.assertEqual(d.climbSkipped[0], false);
    Test.assertEqual(d.nextClimbIndex, 0); // still waiting on climb 0
    return true;
}

(:test)
function skip_odometerLaterClimbEntered_skips(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;
    d.climbEntered[1] = true;             // GPS confirmed we reached the later climb
    d.updateProgress(2900);
    Test.assertEqual(d.climbSkipped[0], true);
    return true;
}

(:test)
function skip_skippedClimbNotReactivated(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1;
    d.climbSkipped[0] = true;             // already abandoned
    d.updateProgress(1500);               // axis back inside climb-0 range
    Test.assertEqual(d.activeClimbIndex, -1);  // not reactivated
    Test.assertEqual(d.nextClimbIndex, 1);
    return true;
}
```

- [ ] **Step 2: Verify it fails (simulator / preflight)**

Run the Monkey C test target.
Expected: FAIL — `climbSkipped`, `SKIP_MARGIN_M`, `backOnRoute` undefined; `updateProgress` returns on climb 0 forever.

- [ ] **Step 3: Add state + allocation + reset**

In `ClimbData.mc` add the constant and declaration:

```monkeyc
    const SKIP_MARGIN_M = 1000;   // ride this far past a climb's end (m) before it may be skipped
```
```monkeyc
    var climbSkipped;   // bool per climb: rider bypassed it; progression skips over it
```

In `initialize()` allocate and zero it:

```monkeyc
        climbSkipped = new [MAX_CLIMBS];
```
and inside the init loop:
```monkeyc
            climbSkipped[i] = false;
```

In `CommListener.mc` `onMessage`, in the per-climb reset loop (next to `data.climbEntered[i] = false;`), add:

```monkeyc
            data.climbSkipped[i] = false;
```

- [ ] **Step 4: Add `backOnRoute` and the skip decision in `updateProgress`**

Add the helper to `ClimbData.mc`:

```monkeyc
    // True when the rider is confirmed back on the route at/after climb i.
    //  - trusted nav: course distance proves we are on the route.
    //  - odometer:   a later climb must be GPS-confirmed (entered) to avoid false-skip.
    hidden function backOnRoute(i) {
        if (navTrust == NAV_TRUSTED) { return true; }
        for (var j = i + 1; j < climbCount; j++) {
            if (climbEntered[j]) { return true; }
        }
        return false;
    }
```

In `updateProgress`, at the very top of the `for (var i = 0; i < climbCount; i++)` loop (before computing `confirmed`), add the skip handling:

```monkeyc
            if (climbSkipped[i]) { continue; }   // already abandoned → next climb
```

Then, immediately after `var confirmed = climbEntered[i] || calibCount[i] == 0;`, add the skip decision:

```monkeyc
            if (!confirmed && calibCount[i] > 0
                    && elapsedDistance > climbEndDist0[i] + SKIP_MARGIN_M
                    && backOnRoute(i)) {
                climbSkipped[i] = true;
                continue;
            }
```

(Here `elapsedDistance` is the `updateProgress` parameter — already the chosen axis from the view.)

- [ ] **Step 5: Verify it passes**

Run the Monkey C test target.
Expected: PASS for all four skip cases; existing `updateProgress`/`updateRouteMatch` cases still pass.

- [ ] **Step 6: Update ARCHITECTURE.md**

In `Documentation/ARCHITECTURE.md`, update the "Activity-time" data-flow box to show the axis-selection step (`navDist = rtl - distanceToDestination`, trusted vs odometer) and add a dated section:

```markdown
### Navigation-anchored distance & skip resilience (2026-06-30)

The climb datafield can match on Garmin's navigation course distance instead of
the raw odometer. The phone ships `rtl` (route total length); the watch computes
`navDist = rtl - Activity.Info.distanceToDestination` and uses it once a **length
gate** (course length ≈ `rtl`) and ongoing **calibration agreement** (navDist
within 150 m of each calibration point's known absolute distance) confirm the
loaded course is the selected route. Trust is one-way: a disagreement revokes it
and the watch falls back to the odometer drift-correction for the rest of the
ride. While trusted, calibration points only *validate* (never shift) the anchor.

A climb that is ridden `SKIP_MARGIN_M` (1 km) past its end without ever being
entered — and with the rider confirmed back on the route (trusted nav, or a later
climb GPS-confirmed) — is flagged `climbSkipped` so progression continues to the
next climb. `climbSkipped` resets on every payload.
```

- [ ] **Step 7: Commit**

```bash
git add garmin/source/ClimbData.mc garmin/source/CommListener.mc garmin/test/ClimbDataTest.mc Documentation/ARCHITECTURE.md
git commit -m "feat(watch): skip bypassed climbs and continue progression"
```

---

## Task 6: Pipeline — wire-lockstep guard (JVM test)

**Files:**
- Create: `android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolLockstepGuardTest.java`

**Interfaces:**
- Consumes: repo files `protocol/schema.json`, `protocol/examples/route_mode_full.json`, `service/ClimbPayloadBuilder.java`, `garmin/source/CommListener.mc`.
- Produces: a JVM test (part of `gradlew test`) that fails when a wire key is present on one side of the contract but missing on another — catching asymmetric updates the Monkey C side would otherwise hide.

- [ ] **Step 1: Write the failing test**

Create `ProtocolLockstepGuardTest.java`:

```java
package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Guards wire-format lockstep across the four contract surfaces. The Monkey C parser has no
 * JVM harness, so this text-level presence check is the safety net: if a wire key lives in the
 * schema + builder but not in CommListener.mc (or vice-versa), the contract has drifted.
 */
public class ProtocolLockstepGuardTest {

    /** Keys that must appear on every surface of the route-mode wire contract. */
    private static final String[] LOCKSTEP_KEYS = { "rtl" };

    @Test
    public void wireKeysPresentOnAllSurfaces() throws Exception {
        File root = repoRoot();
        String schema   = read(new File(root, "protocol/schema.json"));
        String example  = read(new File(root, "protocol/examples/route_mode_full.json"));
        String builder  = read(new File(root,
                "android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java"));
        String commList = read(new File(root, "garmin/source/CommListener.mc"));

        for (String key : LOCKSTEP_KEYS) {
            String q = "\"" + key + "\"";
            assertTrue(key + " missing from schema.json",        schema.contains(q));
            assertTrue(key + " missing from route example",      example.contains(q));
            assertTrue(key + " missing from ClimbPayloadBuilder", builder.contains(q));
            assertTrue(key + " missing from CommListener.mc",    commList.contains(q));
        }
    }

    private static String read(File f) throws Exception {
        if (!f.exists()) { fail("contract file not found: " + f); }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Walk up from the test working dir until a dir containing protocol/schema.json is found. */
    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "protocol/schema.json").exists()) { return dir; }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("repo root (with protocol/schema.json) not found from "
                + new File("").getAbsolutePath());
    }
}
```

- [ ] **Step 2: Run to verify it passes now (guard is green because Task 1 added `rtl` everywhere)**

Run: `android/gradlew.bat test --tests nl.paree.climbpro.protocol.ProtocolLockstepGuardTest`
Expected: PASS. To prove the guard bites, temporarily delete the `rtl` line from `garmin/source/CommListener.mc`, re-run → FAIL with "rtl missing from CommListener.mc", then restore it and re-run → PASS.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/test/java/nl/paree/climbpro/protocol/ProtocolLockstepGuardTest.java
git commit -m "test(protocol): wire-lockstep guard across schema/example/builder/Monkey C"
```

---

## Task 7: Pipeline — Monkey C source guard (JVM, headless)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/protocol/MonkeyCSourceGuard.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/protocol/MonkeyCSourceGuardTest.java`

**Why:** `monkeyc` is not always installed, so the preflight's Monkey C *compile* step is often skipped. This guard runs in the JVM gate (always) and statically catches the two failures most likely to slip through review without the SDK: unbalanced braces/parens (a compile break) and a `(:test)` function missing `return true;` (a Monkey C test that silently does not pass).

**Interfaces:**
- Produces: pure `MonkeyCSourceGuard.bracesBalanced(String)` → boolean and `MonkeyCSourceGuard.testsWithoutReturnTrue(String)` → `List<String>` (offending function names); plus a JUnit test that unit-tests both on good/bad samples **and** runs them over the real `garmin*/source` and `garmin*/test` `.mc` files.

- [ ] **Step 1: Write the failing test**

Create `MonkeyCSourceGuardTest.java`:

```java
package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MonkeyCSourceGuardTest {

    @Test
    public void balanced_ignoresBracesInCommentsAndStrings() {
        assertTrue(MonkeyCSourceGuard.bracesBalanced("function f() { var s = \"}{ ) (\"; }"));
        assertTrue(MonkeyCSourceGuard.bracesBalanced("// } } }\nfunction f() { }"));
        assertTrue(MonkeyCSourceGuard.bracesBalanced("/* { ( */ function f() { return 1; }"));
    }

    @Test
    public void balanced_detectsMissingBrace() {
        assertFalse(MonkeyCSourceGuard.bracesBalanced("function f() { return 1;"));
        assertFalse(MonkeyCSourceGuard.bracesBalanced("function f( { }"));
    }

    @Test
    public void testsWithoutReturnTrue_flagsMissing() {
        String bad = "(:test)\nfunction t1(l) { Test.assertEqual(1,1); }\n"
                   + "(:test)\nfunction t2(l) { return true; }\n";
        List<String> missing = MonkeyCSourceGuard.testsWithoutReturnTrue(bad);
        assertEquals(1, missing.size());
        assertTrue(missing.contains("t1"));
    }

    @Test
    public void realMonkeyCSourcesPassGuard() throws Exception {
        File root = repoRoot();
        List<File> files = new ArrayList<>();
        String[] dirs = {
            "garmin/source", "garmin/test",
            "garmin-widget/source", "garmin-widget/test",
            "garmin-surface/source", "garmin-surface/test",
        };
        for (String d : dirs) {
            File dir = new File(root, d);
            if (!dir.isDirectory()) { continue; }
            File[] mc = dir.listFiles((f, n) -> n.endsWith(".mc"));
            if (mc != null) { for (File f : mc) { files.add(f); } }
        }
        assertFalse("expected to find .mc files", files.isEmpty());
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue("unbalanced braces/parens in " + f, MonkeyCSourceGuard.bracesBalanced(src));
            List<String> missing = MonkeyCSourceGuard.testsWithoutReturnTrue(src);
            assertTrue("(:test) without 'return true' in " + f + ": " + missing, missing.isEmpty());
        }
    }

    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "protocol/schema.json").exists()) { return dir; }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("repo root not found from " + new File("").getAbsolutePath());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `android/gradlew.bat :app:testDebugUnitTest --tests nl.paree.climbpro.protocol.MonkeyCSourceGuardTest`
Expected: FAIL — `MonkeyCSourceGuard` does not exist (compile error).

- [ ] **Step 3: Implement the guard**

Create `MonkeyCSourceGuard.java`:

```java
package nl.paree.climbpro.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headless static checks over Monkey C source (no SDK needed). */
public final class MonkeyCSourceGuard {

    private MonkeyCSourceGuard() {}

    /** Replace // and /* *\/ comments and "..." strings with spaces so token counting is reliable. */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char d = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                while (i < n && src.charAt(i) != '\n') { i++; }
            } else if (c == '/' && d == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) { i++; }
                i += 2;
            } else if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') { i++; }
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** True when {} and () are balanced (ignoring comments/strings). */
    static boolean bracesBalanced(String src) {
        String s = stripCommentsAndStrings(src);
        int curly = 0, paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') { curly++; }
            else if (c == '}') { curly--; if (curly < 0) { return false; } }
            else if (c == '(') { paren++; }
            else if (c == ')') { paren--; if (paren < 0) { return false; } }
        }
        return curly == 0 && paren == 0;
    }

    private static final Pattern TEST_FN =
            Pattern.compile("\\(:test\\)\\s*function\\s+(\\w+)");

    /** Names of (:test) functions whose body lacks a `return true`. */
    static List<String> testsWithoutReturnTrue(String src) {
        String s = stripCommentsAndStrings(src);
        List<String> missing = new ArrayList<>();
        Matcher m = TEST_FN.matcher(s);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) { spans.add(new int[]{ m.start(), m.end() }); names.add(m.group(1)); }
        for (int k = 0; k < spans.size(); k++) {
            int from = spans.get(k)[1];
            int to = (k + 1 < spans.size()) ? spans.get(k + 1)[0] : s.length();
            String body = s.substring(from, to);
            if (!body.contains("return true")) { missing.add(names.get(k)); }
        }
        return missing;
    }
}
```

> Note: in the doc comment above, the block-comment terminator inside the prose is written `*\/` only to keep this Markdown code fence intact — when you create the real file, write it as the normal `*` + `/` sequence (the implementation in `stripCommentsAndStrings` already handles real `/* */` correctly).

- [ ] **Step 4: Run to verify it passes**

Run: `android/gradlew.bat :app:testDebugUnitTest --tests nl.paree.climbpro.protocol.MonkeyCSourceGuardTest`
Expected: PASS — sample-based checks pass and every real `.mc` file is balanced with all `(:test)` functions returning true. (If `realMonkeyCSourcesPassGuard` fails, a real Monkey C file has a genuine brace or missing-`return true` defect — fix the source, not the test.)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/protocol/MonkeyCSourceGuard.java android/app/src/test/java/nl/paree/climbpro/protocol/MonkeyCSourceGuardTest.java
git commit -m "test(pipeline): headless Monkey C source guard (braces + test-return)"
```

---

## Task 8: Pipeline — preflight gate + gated build

**Files:**
- Create: `scripts/preflight.ps1`
- Create: `scripts/build.ps1`
- Create: `.github/workflows/preflight.yml`
- Modify: `Documentation/SETUP.md` (document the pipeline)
- Modify: `README.md` (one line: navigation improves accuracy; build via preflight)
- Modify: `HANDLEIDING.md` (Dutch one-liner on navigatie-nauwkeurigheid)

**Interfaces:**
- Consumes: `android/gradlew.bat test` (incl. the new guards), `monkeyc` (if the Connect IQ SDK is on PATH).
- Produces: `scripts/preflight.ps1` (exit 0 only when all hard gates pass) and `scripts/build.ps1` (runs preflight, then builds artefacts only on success).

- [ ] **Step 1: Write the preflight pipeline**

Create `scripts/preflight.ps1`:

```powershell
#requires -version 5
<#
  Preflight pipeline: runs every check that must pass BEFORE anything is built.
  Hard gates (failure → exit 1):
    1. Android JVM tests (unit + ProtocolRoundTripTest + ProtocolLockstepGuardTest + MonkeyCSourceGuardTest).
    2. Monkey C compilation of all three Connect IQ apps (only if `monkeyc` is on PATH).
  Soft gates (warn, do not fail):
    - Monkey C unit tests (need the simulator; skipped when unavailable).
  Usage:  pwsh -File scripts/preflight.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fail = @()
$warn = @()

Write-Host '== [1/3] Android JVM tests ==' -ForegroundColor Cyan
Push-Location (Join-Path $root 'android')
try {
    & (Join-Path $root 'android\gradlew.bat') test --console=plain
    if ($LASTEXITCODE -ne 0) { $fail += 'Android JVM tests failed' }
} finally { Pop-Location }

Write-Host '== [2/3] Monkey C compilation ==' -ForegroundColor Cyan
$monkeyc = (Get-Command monkeyc -ErrorAction SilentlyContinue)
if ($null -eq $monkeyc) {
    $warn += 'monkeyc not on PATH — Connect IQ compile skipped'
} else {
    $key = Join-Path $root 'developer_key'
    $apps = @(
        @{ dir = 'garmin';         out = 'ClimbPro.prg' },
        @{ dir = 'garmin-widget';  out = 'ClimbWidget.prg' },
        @{ dir = 'garmin-surface'; out = 'SurfaceField.prg' }
    )
    foreach ($a in $apps) {
        $jungle = Join-Path $root (Join-Path $a.dir 'monkey.jungle')
        $out    = Join-Path $env:TEMP $a.out
        & monkeyc -f $jungle -o $out -y $key -d fr255m
        if ($LASTEXITCODE -ne 0) { $fail += ("Monkey C compile failed: " + $a.dir) }
    }
}

Write-Host '== [3/3] Monkey C unit tests ==' -ForegroundColor Cyan
if ($null -eq (Get-Command monkeydo -ErrorAction SilentlyContinue)) {
    $warn += 'monkeydo/simulator unavailable — Monkey C unit tests skipped (review-only)'
} else {
    $key = Join-Path $root 'developer_key'
    $testOut = Join-Path $env:TEMP 'ClimbProTest.prg'
    & monkeyc -f (Join-Path $root 'garmin\monkey-test.jungle') -o $testOut -y $key -d fr255m --test
    if ($LASTEXITCODE -ne 0) { $fail += 'Monkey C test build failed' }
    else { & monkeydo $testOut fr255m -t; if ($LASTEXITCODE -ne 0) { $fail += 'Monkey C unit tests failed' } }
}

Write-Host ''
foreach ($w in $warn) { Write-Host ("WARN: " + $w) -ForegroundColor Yellow }
if ($fail.Count -gt 0) {
    foreach ($f in $fail) { Write-Host ("FAIL: " + $f) -ForegroundColor Red }
    Write-Host 'PREFLIGHT FAILED' -ForegroundColor Red
    exit 1
}
Write-Host 'PREFLIGHT PASSED' -ForegroundColor Green
exit 0
```

- [ ] **Step 2: Run the preflight to verify it gates correctly**

Run: `pwsh -File scripts/preflight.ps1` (or `powershell -File scripts/preflight.ps1`)
Expected: the Android test gate runs and reports PASS; the Monkey C steps either compile or print a `WARN: ... skipped` line if the SDK is absent; final line `PREFLIGHT PASSED`. If JVM tests fail, the script prints `PREFLIGHT FAILED` and exits 1.

- [ ] **Step 3: Write the gated build script**

Create `scripts/build.ps1`:

```powershell
#requires -version 5
<#
  Gated build: nothing is built unless preflight passes first.
  Usage:  pwsh -File scripts/build.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '== Preflight gate ==' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'preflight.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Build aborted: preflight did not pass.' -ForegroundColor Red
    exit 1
}

Write-Host '== Building Android APK ==' -ForegroundColor Cyan
& (Join-Path $root 'android\gradlew.bat') assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host 'Android build failed' -ForegroundColor Red; exit 1 }

Write-Host '== Building Connect IQ apps ==' -ForegroundColor Cyan
if ($null -eq (Get-Command monkeyc -ErrorAction SilentlyContinue)) {
    Write-Host 'monkeyc not on PATH — skipping .prg build' -ForegroundColor Yellow
} else {
    $key  = Join-Path $root 'developer_key'
    $dist = Join-Path $root 'builds'
    if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }
    $apps = @(
        @{ dir = 'garmin';         out = 'ClimbPro.prg' },
        @{ dir = 'garmin-widget';  out = 'ClimbWidget.prg' },
        @{ dir = 'garmin-surface'; out = 'SurfaceField.prg' }
    )
    foreach ($a in $apps) {
        & monkeyc -f (Join-Path $root (Join-Path $a.dir 'monkey.jungle')) `
                  -o (Join-Path $dist $a.out) -y $key -d fr255m
        if ($LASTEXITCODE -ne 0) { Write-Host ("Build failed: " + $a.dir) -ForegroundColor Red; exit 1 }
    }
}
Write-Host 'BUILD COMPLETE' -ForegroundColor Green
```

- [ ] **Step 4: Run the gated build to verify the gate is honoured**

Run: `pwsh -File scripts/build.ps1`
Expected: preflight runs first; on PASS the Android APK builds (and `.prg` files if `monkeyc` is present); on a forced JVM-test failure the script prints `Build aborted: preflight did not pass.` and exits without building. (To confirm the gate, temporarily break a JVM test, run, observe the abort, then restore.)

- [ ] **Step 5: Add a CI workflow mirroring the JVM gate**

Create `.github/workflows/preflight.yml`:

```yaml
name: preflight
on:
  push:
  pull_request:
jobs:
  jvm-gates:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Run JVM tests (unit + protocol round-trip + lockstep guard)
        run: ./gradlew test --console=plain
```

- [ ] **Step 6: Document the pipeline**

In `Documentation/SETUP.md`, under the build steps, add:

```markdown
### Preflight pipeline (build gate)

Run all checks before building any artefact:

```
pwsh -File scripts/preflight.ps1   # JVM tests + Monkey C compile (+ sim tests if available)
pwsh -File scripts/build.ps1       # preflight, then APK + .prg only if it passed
```

`preflight.ps1` exits non-zero if Android JVM tests (including `ProtocolRoundTripTest`,
`ProtocolLockstepGuardTest`, and `MonkeyCSourceGuardTest`) or any Monkey C compilation
fail. The `MonkeyCSourceGuard` statically checks the Monkey C sources (brace/paren balance
and `(:test)` return-true) so defects are caught even when the Connect IQ SDK is absent. Monkey C unit
tests run when the Connect IQ simulator is available, otherwise they are reported
as skipped. CI runs the JVM gate on every push (`.github/workflows/preflight.yml`).
```

In `README.md`, add one line near the build/usage section:

```markdown
- Navigating the selected route as a Garmin course improves on-watch distance accuracy (the datafield matches on course distance, falling back to the activity odometer). Build via `scripts/build.ps1`, which runs the preflight gate first.
```

In `HANDLEIDING.md`, add a Dutch one-liner:

```markdown
- Navigeer de gekozen route als Garmin-course voor nauwkeurigere afstanden op het horloge; het datafield valt automatisch terug op de odometer als je niet navigeert.
```

- [ ] **Step 7: Commit**

```bash
git add scripts/preflight.ps1 scripts/build.ps1 .github/workflows/preflight.yml Documentation/SETUP.md README.md HANDLEIDING.md
git commit -m "feat(pipeline): preflight gate runs all checks before any build"
```

---

## Final verification

- [ ] **Run the full preflight gate**

Run: `pwsh -File scripts/preflight.ps1`
Expected: `PREFLIGHT PASSED` (Monkey C steps may print `WARN: ... skipped` if the SDK is absent — that is acceptable; the JVM gate must pass).

- [ ] **Run the full JVM suite explicitly**

Run: `android/gradlew.bat test --console=plain`
Expected: `BUILD SUCCESSFUL`; `ProtocolRoundTripTest`, `ProtocolLockstepGuardTest`, `MonkeyCSourceGuardTest`, and `ClimbPayloadBuilderTest` all green.

- [ ] **Monkey C tests (simulator, if available)**

Run: `monkeyc -f garmin/monkey-test.jungle -o ClimbProTest.prg -y developer_key -d fr255m --test` then `monkeydo ClimbProTest.prg fr255m -t`
Expected: all `ClimbDataTest` cases pass, including the new `chooseAxis_*`, `calib_*`, `anchors_*`, and `skip_*` cases.

---

## Self-review notes (author)

- **Spec coverage:** G1 (nav axis + trust) → Tasks 1,3,4; G2 (skip) → Task 5; immutable-anchor groundwork → Task 2; pipeline extra feature → Tasks 6 (wire lockstep guard), 7 (Monkey C source guard), 8 (preflight gate + gated build). Wire lockstep constraint → Tasks 1 + 6.
- **Type consistency:** `climbStartDist0`/`climbEndDist0`, `navTrust`/`NAV_*`, `navMaxToDest`, `navDistThisTick`, `routeTotalLen`, `climbSkipped`, `SKIP_MARGIN_M`, `chooseAxis`, `resetNavTrust`, `backOnRoute`, `setAnchors`, `putRouteTotalLength` are each defined once and used with the same name/signature throughout.
- **No placeholders:** every code step shows complete code; every run step states the exact command and expected result.
