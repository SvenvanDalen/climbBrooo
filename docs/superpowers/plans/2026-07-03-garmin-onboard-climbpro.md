# Garmin Onboard ClimbPro (on-watch route parsing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new, self-contained Connect IQ watch-app (separate folder `garmin-onboard/`) that receives RAW route points **pushed unprompted by the phone** and performs the ENTIRE ClimbPro analysis on the watch — cumulative distance, elevation smoothing, climb detection, false-flat trim, 8% segmentation, gradient colors — then renders a single "5 km terrain window" live view with GPS route matching and an off-route fallback banner so the displayed data can never go silently stale.

**Architecture:** Connect IQ apps cannot read course/GPX files on the device, so the phone ships raw points (lat/lon/elevation only, fixed-point integers, chunked messages) to the new app's own CIQ UUID. The phone is always the initiator — the watch never lists or requests routes, it only receives what the phone sends when the user taps "Verstuur naar horloge" (push-only protocol, see `docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md`). Everything downstream of the points is computed on the watch — this deliberately inverts the repo's usual "heavy compute on the phone" rule for this one experimental module; the existing three modules are untouched. The on-watch pipeline is a Monkey C port of the Java `ClimbDetector`/`ClimbTrimmer`/`Segmenter`/`GradientColor`/`ElevationSmoother` logic and MUST preserve the same domain rules. The live view renders the elevation profile from the current position to +5 km (clamped to route end) with detected climbs colored on top — not a climb-only screen — and shows an "OFF ROUTE" banner over the frozen last window instead of silently continuing to display stale progress when the GPS fix strays off the route.

**Tech Stack:** Monkey C (Connect IQ SDK, fr255m, Toybox.Test harness via `tools/run-monkeyc-tests.ps1`), Java (Android companion, JUnit4 + Mockito, Gradle).

## Global Constraints

- Climb definition: length ≥ **800 m** AND average gradient ≥ **3%** (0.03). Both required.
- False-flat trim: contiguous stretch averaging **< 2%** (0.02) over **≥ 200 m** trimmed off lead-in/lead-out; NEVER trim a climb below the 800 m minimum.
- Segmentation: segments of exactly **8% of the climb length** (`SEGMENT_FRACTION = 0.08` → 13 segments); do not hardcode a count of 13 in logic (only in test assertions).
- Segment color mapping (gradient fraction → index): `< 0.02` → 0, `< 0.04` → 1, `< 0.06` → 2, `< 0.08` → 3, `< 0.10` → 4, else 5.
- Climb-start alert: vibration, **once per climb**, within **50 m** of start, idempotent across GPS jitter.
- Offline-first: after the raw route has been received once, everything works with no phone connection (route persisted in `Application.Storage`).
- Android language: **Java** (no Kotlin). POJOs with `final` fields where applicable.
- Target device: Forerunner 255 Music (`fr255m`, MIP display, no touch). minApiLevel 3.2.0 like the other modules.
- Monkey C style: every `if`/`while`/`for` body uses braces; every `(:test)` function ends with `return true;` (enforced by the JVM `MonkeyCSourceGuard`).
- Test commands (Windows PowerShell 5.1, NOT pwsh):
  - Watch: `& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard` (from repo root; starts the simulator itself).
  - Android: `Set-Location android; .\gradlew.bat test --tests "<FQCN>"; Set-Location ..`
- Existing modules (`garmin`, `garmin-widget`, `garmin-surface`, `android` v3 payload path, `protocol/schema.json`) must NOT change behavior. The raw-route messages are a NEW side protocol and deliberately do not touch `schema.json`.

## New wire protocol (raw route transfer) — push-only

**Design supersedes the original pull model** (see
`docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md`).
The watch never requests anything — it only receives. All messages are CIQ
Dictionaries, phone → watch only:

| Message | Fields |
|---|---|
| `{"type":"RAW_HDR","id","name","n":<pointCount>,"tot":<chunkCount>}` | announces a transfer; `n ≤ 6000` |
| `{"type":"RAW_CHUNK","id","seq":<0-based>,"lat":[...],"lon":[...],"ele":[...]}` | ≤ 250 points per chunk; lat/lon = degrees × 100000 as Number; ele = decimeters as Number |

There are no watch → phone messages in this protocol. The transfer is
triggered by the user tapping "Verstuur naar horloge" on a route's detail
screen in the phone app (`OnboardPushService.pushRoute`), not by anything the
watch sends.

Shared constants (duplicated by design, one per side, cross-checked by tests): `MAX_RAW_POINTS = 6000`, `CHUNK_POINTS = 250`. The phone decimates by uniform stride to ≤ 6000 points and always keeps the last point. No distances are sent — the watch computes cumulative distance itself (that is the point of this module).

Sizing rationale: 6000 points ≈ **25 m spacing on a 150 km route** (dense enough that short gradient changes survive decimation). Runtime cost on the watch is 4 parallel arrays × 6000 Floats ≈ 120 KB — comfortably inside the FR255M watch-app budget (watch-apps get far more memory than the 32 KB datafields; this is exactly why the module is a watch-app). A transfer is at most 24 chunks. Persistence must NOT write one big value: ~90 KB in a single dict exceeds CIQ per-value Storage limits, so `RawRouteStore` saves the arrays in slices of `STORAGE_SLICE = 2000` points per key (~10 KB per value).

## File map

**New folder `garmin-onboard/`** (watch-app "ClimbPro Onboard", CIQ UUID `a0b1c2d3e4f50617a0b1c2d3e4f50617`):
- `manifest.xml`, `monkey.jungle`, `monkey-test.jungle`, `resources/strings/strings.xml`, `resources/drawables/drawables.xml`, `resources/drawables/launcher_icon.png` (copied from garmin-widget)
- `source/OnboardApp.mc` — app scaffold, phone-message registration, storage restore
- `source/RawRouteStore.mc` — chunk assembly, fixed-point decode, cumulative distance, Storage persistence
- `source/RouteParser.mc` — smoothing + climb detection + false-flat trim + segmentation + colors (module of pure functions)
- `source/OnboardClimbData.mc` — parallel-array results + live GPS matching (nearest point on polyline, hysteresis, off-route, climb-start alert)
- `source/OnboardCommListener.mc` — receive-only message dispatch (`RAW_HDR`/`RAW_CHUNK`)
- `source/OnboardView.mc` — single "5 km terrain window" render + Position events (no separate route-picker view; the watch never lists or requests routes)
- `test/OnboardStoreTest.mc`, `test/OnboardParserTest.mc`, `test/OnboardSegmentTest.mc`, `test/OnboardTrackTest.mc`, `test/OnboardCommTest.mc`, `test/OnboardViewTest.mc`

**Android (small additions, no changes to existing send paths):**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/RawRoutePayloadBuilder.java` (+ test)
- Create: `android/app/src/main/java/nl/paree/climbpro/service/OnboardPushService.java` (+ test) — pushes a route unprompted, triggered by the phone UI
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java` (add `ONBOARD`)
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java` (onboard IQApp, `sendMessageToOnboard` — send-only, no inbound registration)
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`, `RouteDetailViewModel.java` (button + push trigger)
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml` (button)

**Tooling/docs:** `tools/run-monkeyc-tests.ps1` (add module), `protocol/raw-route.md` (new), `Documentation/ARCHITECTURE.md`, `README.md`, `CLAUDE.md`.

---

### Task 1: Scaffold the `garmin-onboard` module + test harness hookup

**Files:**
- Create: `garmin-onboard/manifest.xml`
- Create: `garmin-onboard/monkey.jungle`
- Create: `garmin-onboard/monkey-test.jungle`
- Create: `garmin-onboard/resources/strings/strings.xml`
- Create: `garmin-onboard/resources/drawables/drawables.xml`
- Create: `garmin-onboard/resources/drawables/launcher_icon.png` (copy)
- Create: `garmin-onboard/source/OnboardApp.mc`
- Create: `garmin-onboard/source/OnboardView.mc` (placeholder, replaced in Task 8)
- Create: `garmin-onboard/test/OnboardSmokeTest.mc`
- Modify: `tools/run-monkeyc-tests.ps1:30` (default `$Modules`)

**Interfaces:**
- Produces: `OnboardApp` (App.AppBase) with public vars `store`, `climbData`, `routeIndex`, `msgCallback` — later tasks assign real objects in `getInitialView`. `OnboardDelegate` (input). App UUID `a0b1c2d3e4f50617a0b1c2d3e4f50617` — Task 10's `ConnectIqAppId.ONBOARD` must equal it.

- [ ] **Step 1: Write the manifest and jungles**

`garmin-onboard/manifest.xml`:

```xml
<?xml version="1.0"?>
<iq:manifest version="3" xmlns:iq="http://www.garmin.com/xml/connectiq">
    <iq:application
        entry="OnboardApp"
        type="watch-app"
        name="@Strings.AppName"
        id="a0b1c2d3e4f50617a0b1c2d3e4f50617"
        launcherIcon="@Drawables.LauncherIcon"
        minApiLevel="3.2.0">
        <iq:products>
            <iq:product id="fr255m"/>
        </iq:products>
        <iq:permissions>
            <iq:uses-permission id="Communications"/>
            <iq:uses-permission id="Positioning"/>
        </iq:permissions>
        <iq:languages>
            <iq:language>eng</iq:language>
        </iq:languages>
        <iq:barrels/>
    </iq:application>
</iq:manifest>
```

`garmin-onboard/monkey.jungle`:

```
project.manifest = manifest.xml

base.sourcePath = source
base.resourcePath = resources
```

`garmin-onboard/monkey-test.jungle`:

```
project.manifest = manifest.xml
base.sourcePath = source;test
base.resourcePath = resources
```

- [ ] **Step 2: Write the resources**

`garmin-onboard/resources/strings/strings.xml`:

```xml
<strings>
    <string id="AppName">ClimbPro Onboard</string>
</strings>
```

`garmin-onboard/resources/drawables/drawables.xml`:

```xml
<drawables>
    <bitmap id="LauncherIcon" filename="launcher_icon.png" />
</drawables>
```

Copy the icon:

```powershell
Copy-Item garmin-widget\resources\drawables\launcher_icon.png garmin-onboard\resources\drawables\launcher_icon.png
```

- [ ] **Step 3: Write the app scaffold and placeholder view**

`garmin-onboard/source/OnboardApp.mc`:

```
using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

class OnboardApp extends App.AppBase {

    // Assigned in getInitialView; later tasks replace the nulls with real objects.
    var store = null;        // RawRouteStore (Task 2)
    var climbData = null;    // OnboardClimbData (Task 4)
    var routeIndex = null;   // OnboardRouteIndex (Task 7)
    hidden var msgCallback = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {}
    function onStop(state) {}

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null && msgCallback != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    // Test seam: same dispatch as onPhoneMessage but takes a plain Dictionary.
    function processMessage(msg) {
        if (msgCallback != null) {
            msgCallback.onMessage(msg);
        }
    }

    // Later tasks extend this: Task 7 assigns store/climbData/routeIndex/msgCallback
    // and restores the persisted route.
    function getInitialView() {
        Sys.println("OnboardApp: started");
        return [new OnboardView(), new OnboardDelegate()];
    }
}
```

`garmin-onboard/source/OnboardView.mc` (placeholder; Task 8 rewrites it):

```
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;

class OnboardView extends Ui.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
                    "ClimbPro Onboard", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }
}

class OnboardDelegate extends Ui.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }
}
```

- [ ] **Step 4: Write a failing-then-passing smoke test**

`garmin-onboard/test/OnboardSmokeTest.mc`:

```
using Toybox.Test;
using Toybox.Graphics as Gfx;

function onbMakeDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

(:test)
function onboard_smoke_viewRenders(logger) {
    var v = new OnboardView();
    v.onUpdate(onbMakeDc());
    return true;
}
```

- [ ] **Step 5: Add the module to the test runner default list**

In `tools/run-monkeyc-tests.ps1`, change:

```powershell
    [string[]] $Modules = @("garmin", "garmin-widget", "garmin-surface")
```

to:

```powershell
    [string[]] $Modules = @("garmin", "garmin-widget", "garmin-surface", "garmin-onboard")
```

- [ ] **Step 6: Build and run the module's tests**

Run from repo root (Windows PowerShell):

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `BUILD` succeeds, then `passed=1, failed=0, errors=0` and `ALL MONKEY C TESTS PASSED`.

- [ ] **Step 7: Check the MonkeyCSourceGuard covers the new folder**

```powershell
Get-ChildItem android\app\src\test -Recurse -Filter "MonkeyCSourceGuard*.java" | Select-Object FullName
```

Open the file found and check how it enumerates module dirs. If it hardcodes `garmin`, `garmin-widget`, `garmin-surface`, add `garmin-onboard` to that list the same way. Then run it:

```powershell
Set-Location android
.\gradlew.bat test --tests "*MonkeyCSourceGuard*"
Set-Location ..
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```powershell
git add garmin-onboard tools/run-monkeyc-tests.ps1 android
git commit -m "feat(onboard): scaffold garmin-onboard watch-app module"
```

---

### Task 2: RawRouteStore — chunk assembly, fixed-point decode, distances, persistence

**Files:**
- Create: `garmin-onboard/source/RawRouteStore.mc`
- Create: `garmin-onboard/test/OnboardStoreTest.mc`

**Interfaces:**
- Produces: `class RawRouteStore` with consts `MAX_POINTS = 6000`, `CHUNK_POINTS = 250`, `STORAGE_SLICE = 2000`; vars `routeId`, `routeName`, `pointCount` (Number), `chunkTotal`, `chunksReceived`, `complete` (Boolean), parallel arrays `lat`/`lon`/`ele` (Float, degrees / meters) and `dist` (Float, cumulative meters); functions `beginRoute(id, name, n, tot)` → Boolean, `addChunk(seq, latArr, lonArr, eleArr)` → Boolean, `finalizeRoute()`, `distM(lat1, lon1, lat2, lon2)` → Float, `saveToStorage()`, `restoreFromStorage()` → Boolean.
- Note: ALL Monkey C test files in this module compile into one binary, so top-level test helpers must have unique names. This task defines `onbStoreFromEle(eles)` which later test files reuse.

- [ ] **Step 1: Write the failing tests**

`garmin-onboard/test/OnboardStoreTest.mc`:

```
using Toybox.Test;
using Toybox.Application.Storage as Storage;

// Shared helper (also used by parser/segment/track tests): builds a completed
// store with points every ~100 m of latitude (0.0009 deg * 111111 m/deg) and
// the given elevations (meters).
function onbStoreFromEle(eles) {
    var st = new RawRouteStore();
    var n = eles.size();
    st.beginRoute("t1", "Test", n, 1);
    for (var i = 0; i < n; i++) {
        st.lat[i] = 50.0 + (i * 0.0009);
        st.lon[i] = 5.0;
        st.ele[i] = eles[i].toFloat();
    }
    st.finalizeRoute();
    return st;
}

(:test)
function store_assemblesChunksAndComputesDistance(logger) {
    var st = new RawRouteStore();
    Test.assert(st.beginRoute("r1", "Rit", 3, 1));
    // 0.0009 deg lat between points => ~100 m each; ele 10.0 m, 15.0 m, 20.0 m
    var ok = st.addChunk(0,
        [5000000, 5000090, 5000180],
        [500000, 500000, 500000],
        [100, 150, 200]);
    Test.assert(ok);
    Test.assert(st.complete);
    Test.assertEqual(st.pointCount, 3);
    // fixed-point decode
    Test.assert((st.lat[1] - 50.0009).abs() < 0.00001);
    Test.assert((st.ele[2] - 20.0).abs() < 0.01);
    // cumulative distance ~100 m per step
    Test.assert(st.dist[0] == 0);
    Test.assert((st.dist[1] - 100.0).abs() < 2.0);
    Test.assert((st.dist[2] - 200.0).abs() < 4.0);
    return true;
}

(:test)
function store_outOfOrderAndDuplicateChunks(logger) {
    var st = new RawRouteStore();
    // 251 points => 2 chunks (250 + 1)
    var n = 251;
    Test.assert(st.beginRoute("r2", "Rit2", n, 2));
    var lat0 = new [250]; var lon0 = new [250]; var ele0 = new [250];
    for (var i = 0; i < 250; i++) {
        lat0[i] = 5000000 + i * 90; lon0[i] = 500000; ele0[i] = 100;
    }
    // chunk 1 first (out of order); its single point is index 250
    Test.assert(st.addChunk(1, [5022500], [500000], [100]));
    Test.assert(!st.complete);
    // duplicate seq rejected
    Test.assert(!st.addChunk(1, [5022500], [500000], [100]));
    Test.assert(st.addChunk(0, lat0, lon0, ele0));
    Test.assert(st.complete);
    Test.assertEqual(st.chunksReceived, 2);
    return true;
}

(:test)
function store_rejectsGarbage(logger) {
    var st = new RawRouteStore();
    Test.assert(!st.beginRoute("x", "X", 0, 1));      // no points
    Test.assert(!st.beginRoute("x", "X", null, 1));   // bad n
    Test.assert(st.beginRoute("x", "X", 3, 1));
    Test.assert(!st.addChunk(null, [1], [1], [1]));   // bad seq
    Test.assert(!st.addChunk(5, [1], [1], [1]));      // seq out of range
    Test.assert(!st.addChunk(0, "no", [1], [1]));     // non-array
    Test.assert(!st.complete);
    return true;
}

(:test)
function store_clampsPointCountToMax(logger) {
    var st = new RawRouteStore();
    Test.assert(st.beginRoute("big", "Big", 9999, 84));
    Test.assertEqual(st.pointCount, st.MAX_POINTS);
    return true;
}

(:test)
function store_storageRoundTrip(logger) {
    var st = onbStoreFromEle([100, 105, 110, 115]);
    st.saveToStorage();
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assert(st2.complete);
    Test.assertEqual(st2.pointCount, 4);
    Test.assertEqual(st2.routeId, "t1");
    Test.assert((st2.ele[3] - 115.0).abs() < 0.01);
    Test.assert((st2.dist[3] - st.dist[3]).abs() < 1.0);
    // empty storage => restore fails
    Storage.deleteValue("onb_raw_meta");
    var st3 = new RawRouteStore();
    Test.assert(!st3.restoreFromStorage());
    return true;
}

(:test)
function store_storageMultiSliceRoundTrip(logger) {
    // 2500 points spans two STORAGE_SLICE blocks (2000 + 500)
    var eles = new [2500];
    for (var i = 0; i < 2500; i++) { eles[i] = 100 + (i % 50); }
    var st = onbStoreFromEle(eles);
    st.saveToStorage();
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assertEqual(st2.pointCount, 2500);
    Test.assert((st2.ele[1999] - st.ele[1999]).abs() < 0.01);   // last of slice 0
    Test.assert((st2.ele[2400] - st.ele[2400]).abs() < 0.01);   // inside slice 1
    Test.assert((st2.dist[2499] - st.dist[2499]).abs() < 2.0);
    return true;
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: **BUILD FAILED** with "Undefined symbol RawRouteStore" (a compile failure is this harness's "red").

- [ ] **Step 3: Write the implementation**

`garmin-onboard/source/RawRouteStore.mc`:

```
using Toybox.Application.Storage as Storage;
using Toybox.Math as Math;
using Toybox.System as Sys;

/**
 * Assembles the raw route point stream from the phone (RAW_HDR + RAW_CHUNK
 * messages) into parallel arrays, computes cumulative distance on the watch,
 * and persists the decoded route so the app is offline-first after one sync.
 * Wire format: lat/lon degrees x 100000, ele decimeters (see protocol/raw-route.md).
 */
class RawRouteStore {

    const MAX_POINTS    = 6000;  // must match RawRoutePayloadBuilder.MAX_RAW_POINTS (~25 m spacing on 150 km)
    const CHUNK_POINTS  = 250;   // must match RawRoutePayloadBuilder.CHUNK_POINTS
    const STORAGE_SLICE = 2000;  // points per Storage value — one big value would exceed CIQ per-value limits

    var routeId = null;
    var routeName = null;
    var pointCount = 0;
    var chunkTotal = 0;
    var chunksReceived = 0;
    var complete = false;

    var lat = null;   // Float degrees
    var lon = null;   // Float degrees
    var ele = null;   // Float meters
    var dist = null;  // Float meters, cumulative from route start

    var chunksSeen = null;

    function initialize() {
    }

    function beginRoute(id, name, n, tot) {
        complete = false;
        chunksReceived = 0;
        routeId = null;
        routeName = null;
        pointCount = 0;
        chunkTotal = 0;
        if (!(n instanceof Toybox.Lang.Number) || n <= 0) { return false; }
        if (!(tot instanceof Toybox.Lang.Number) || tot <= 0) { return false; }
        if (n > MAX_POINTS) { n = MAX_POINTS; }
        routeId = id;
        routeName = name;
        pointCount = n;
        chunkTotal = tot;
        lat = new [n];
        lon = new [n];
        ele = new [n];
        dist = new [n];
        chunksSeen = new [tot];
        for (var i = 0; i < tot; i++) { chunksSeen[i] = false; }
        return true;
    }

    function addChunk(seq, latArr, lonArr, eleArr) {
        if (pointCount <= 0 || !(seq instanceof Toybox.Lang.Number)) { return false; }
        if (seq < 0 || seq >= chunkTotal || chunksSeen[seq]) { return false; }
        if (!(latArr instanceof Toybox.Lang.Array)
                || !(lonArr instanceof Toybox.Lang.Array)
                || !(eleArr instanceof Toybox.Lang.Array)) {
            return false;
        }
        var cnt = latArr.size();
        if (lonArr.size() < cnt) { cnt = lonArr.size(); }
        if (eleArr.size() < cnt) { cnt = eleArr.size(); }
        var base = seq * CHUNK_POINTS;
        for (var i = 0; i < cnt; i++) {
            var p = base + i;
            if (p >= pointCount) { break; }
            lat[p] = latArr[i].toFloat() / 100000.0;
            lon[p] = lonArr[i].toFloat() / 100000.0;
            ele[p] = eleArr[i].toFloat() / 10.0;
        }
        chunksSeen[seq] = true;
        chunksReceived++;
        if (chunksReceived >= chunkTotal) {
            finalizeRoute();
        }
        return true;
    }

    // Computes cumulative distance from the decoded coordinates and marks the
    // route complete. Any point a dropped chunk left null would crash the math,
    // so this only runs when every chunk has arrived (or from tests/restore).
    function finalizeRoute() {
        if (pointCount <= 0) { return; }
        dist[0] = 0.0;
        var d = 0.0;
        for (var i = 1; i < pointCount; i++) {
            d += distM(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            dist[i] = d;
        }
        complete = true;
    }

    // Flat-Earth approximation in meters — same formula the datafield uses.
    function distM(lat1, lon1, lat2, lon2) {
        var dlat = lat1 - lat2;
        var dlon = lon1 - lon2;
        var cosLat = Math.cos(lat1 * Math.PI / 180.0);
        return Math.sqrt((dlat * 111111.0) * (dlat * 111111.0)
                       + (dlon * 111111.0 * cosLat) * (dlon * 111111.0 * cosLat));
    }

    function totalLen() {
        if (!complete || pointCount <= 0) { return 0; }
        return dist[pointCount - 1];
    }

    // Persist in slices of STORAGE_SLICE points per key: 6000-point arrays as a
    // single value (~90 KB) would blow the per-value Storage limit; a 2000-point
    // Float slice is ~10 KB.
    function saveToStorage() {
        if (!complete) { return; }
        Storage.setValue("onb_raw_meta", {
            "id"   => routeId,
            "name" => routeName,
            "n"    => pointCount
        });
        var slices = sliceCount(pointCount);
        for (var k = 0; k < slices; k++) {
            var from = k * STORAGE_SLICE;
            var to = from + STORAGE_SLICE;
            if (to > pointCount) { to = pointCount; }
            Storage.setValue("onb_raw_lat_" + k, lat.slice(from, to));
            Storage.setValue("onb_raw_lon_" + k, lon.slice(from, to));
            Storage.setValue("onb_raw_ele_" + k, ele.slice(from, to));
        }
    }

    function restoreFromStorage() {
        var meta = Storage.getValue("onb_raw_meta");
        if (!(meta instanceof Toybox.Lang.Dictionary)) { return false; }
        var n = meta.get("n");
        if (!(n instanceof Toybox.Lang.Number) || n <= 0 || n > MAX_POINTS) { return false; }
        var la = new [n];
        var lo = new [n];
        var el = new [n];
        var slices = sliceCount(n);
        var pos = 0;
        for (var k = 0; k < slices; k++) {
            var sLat = Storage.getValue("onb_raw_lat_" + k);
            var sLon = Storage.getValue("onb_raw_lon_" + k);
            var sEle = Storage.getValue("onb_raw_ele_" + k);
            if (!(sLat instanceof Toybox.Lang.Array) || !(sLon instanceof Toybox.Lang.Array)
                    || !(sEle instanceof Toybox.Lang.Array)) {
                return false;
            }
            if (sLon.size() < sLat.size() || sEle.size() < sLat.size()) { return false; }
            for (var i = 0; i < sLat.size() && pos < n; i++) {
                la[pos] = sLat[i];
                lo[pos] = sLon[i];
                el[pos] = sEle[i];
                pos++;
            }
        }
        if (pos < n) { return false; }
        routeId = meta.get("id");
        routeName = meta.get("name");
        pointCount = n;
        chunkTotal = 1;
        chunksReceived = 1;
        lat = la;
        lon = lo;
        ele = el;
        dist = new [n];
        finalizeRoute();
        return true;
    }

    hidden function sliceCount(n) {
        return (n + STORAGE_SLICE - 1) / STORAGE_SLICE;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=7, failed=0, errors=0` (1 smoke + 6 new).

- [ ] **Step 5: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): raw route store with chunk assembly, on-watch distances, persistence"
```

---

### Task 3: RouteParser — elevation smoothing (on-watch)

**Files:**
- Create: `garmin-onboard/source/RouteParser.mc`
- Create: `garmin-onboard/test/OnboardParserTest.mc` (smoothing tests only; Task 4 adds detection tests to the same file)

**Interfaces:**
- Consumes: `RawRouteStore` (`pointCount`, `ele`, `dist`) from Task 2.
- Produces: `module RouteParser` with consts `MIN_CLIMB_LENGTH_M = 800`, `MIN_AVG_GRADIENT = 0.03`, `DOWNHILL_TOLERANCE_M = 20.0`, `FALSE_FLAT_MAX_GRADIENT = 0.02`, `FALSE_FLAT_MIN_LENGTH_M = 200`, `SEGMENT_FRACTION = 0.08`, `SMOOTH_WINDOW = 5`; function `smoothEle(store)` (in-place, centred moving average, window = 5 each side — the same window the phone uses in `RouteListActivity`/`StravaRoutesRepository`).

- [ ] **Step 1: Write the failing tests**

`garmin-onboard/test/OnboardParserTest.mc` (uses `onbStoreFromEle` from OnboardStoreTest.mc — same test binary):

```
using Toybox.Test;

(:test)
function parser_smooth_constantStaysConstant(logger) {
    var st = onbStoreFromEle([100, 100, 100, 100, 100, 100, 100]);
    RouteParser.smoothEle(st);
    for (var i = 0; i < st.pointCount; i++) {
        Test.assert((st.ele[i] - 100.0).abs() < 0.001);
    }
    return true;
}

(:test)
function parser_smooth_flattensSpike(logger) {
    // 13 points so index 6 has a full 11-point window: (12*100 + 210)/11
    var eles = [100, 100, 100, 100, 100, 100, 210, 100, 100, 100, 100, 100, 100];
    var st = onbStoreFromEle(eles);
    RouteParser.smoothEle(st);
    var expected = (10.0 * 100.0 + 210.0) / 11.0;
    Test.assert((st.ele[6] - expected).abs() < 0.01);
    // neighbours pulled up slightly, not left at 100
    Test.assert(st.ele[5] > 100.0);
    return true;
}

(:test)
function parser_smooth_edgesUseShrunkWindow(logger) {
    var st = onbStoreFromEle([100, 200, 100, 100, 100, 100, 100, 100]);
    RouteParser.smoothEle(st);
    // index 0 window = points 0..5 => (100+200+100*4)/6
    var expected = (100.0 + 200.0 + 400.0) / 6.0;
    Test.assert((st.ele[0] - expected).abs() < 0.01);
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED — "Undefined symbol RouteParser".

- [ ] **Step 3: Write the implementation**

`garmin-onboard/source/RouteParser.mc`:

```
using Toybox.Math as Math;
using Toybox.System as Sys;

/**
 * On-watch route analysis pipeline — Monkey C port of the phone's
 * ElevationSmoother / ClimbDetector / ClimbTrimmer / Segmenter / GradientColor.
 * Domain rules (CLAUDE.md, non-negotiable): climb >= 800 m AND >= 3% average;
 * false flat < 2% over >= 200 m trimmed but never below 800 m; segments are
 * 8% of climb length; color cutoffs 2/4/6/8/10%.
 */
module RouteParser {

    const MIN_CLIMB_LENGTH_M     = 800;
    const MIN_AVG_GRADIENT       = 0.03;
    const DOWNHILL_TOLERANCE_M   = 20.0;
    const FALSE_FLAT_MAX_GRADIENT = 0.02;
    const FALSE_FLAT_MIN_LENGTH_M = 200;
    const SEGMENT_FRACTION       = 0.08;
    const SMOOTH_WINDOW          = 5;   // points each side; matches the phone

    // Centred moving average over the elevation channel, in place.
    function smoothEle(store) {
        var n = store.pointCount;
        if (n <= 0) { return; }
        var src = store.ele;
        var out = new [n];
        for (var i = 0; i < n; i++) {
            var lo = i - SMOOTH_WINDOW;
            if (lo < 0) { lo = 0; }
            var hi = i + SMOOTH_WINDOW;
            if (hi > n - 1) { hi = n - 1; }
            var sum = 0.0;
            var cnt = 0;
            for (var j = lo; j <= hi; j++) {
                sum += src[j];
                cnt++;
            }
            out[i] = sum / cnt;
        }
        store.ele = out;
    }

    // Point-to-point gradient between store indices a and b (fraction).
    function grad(store, a, b) {
        var d = store.dist[b] - store.dist[a];
        if (d <= 0) { return 0.0; }
        return (store.ele[b] - store.ele[a]) / d;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=10, failed=0, errors=0`.

- [ ] **Step 5: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): on-watch elevation smoothing (port of ElevationSmoother)"
```

---

### Task 4: OnboardClimbData container + climb detection + false-flat trim

**Files:**
- Create: `garmin-onboard/source/OnboardClimbData.mc`
- Modify: `garmin-onboard/source/RouteParser.mc` (add `parse`, `detectClimbs`, `trimFalseFlat`)
- Modify: `garmin-onboard/test/OnboardParserTest.mc` (add detection tests)

**Interfaces:**
- Consumes: `RawRouteStore`, `RouteParser.grad`/`smoothEle` (Tasks 2–3).
- Produces: `class OnboardClimbData` with consts `MAX_CLIMBS = 16`, `MAX_SEGMENTS = 16`; vars `parsed` (Boolean), `store` (RawRouteStore or null), `climbCount`, and per-climb Number arrays `climbStartDist`, `climbEndDist`, `climbLength`, `climbElevGain`, `climbAvgGrad` (pct×10), `climbStartIdx`, `climbEndIdx`, `segCount`, plus per-segment arrays `segDist[][]`, `segElevGain[][]`, `segGradient[][]` (pct×10), `segColor[][]`; function `reset()`. Also `RouteParser.parse(store, data)`, `RouteParser.detectClimbs(store, data)`, `RouteParser.trimFalseFlat(store, startIdx, endIdx)` → `[startIdx, endIdx]`. Task 5 fills the seg arrays via `RouteParser.segmentClimb(store, data, c)` — `parse` already calls it, so this task adds a temporary no-op `segmentClimb` that Task 5 replaces.

- [ ] **Step 1: Write the failing tests**

Append to `garmin-onboard/test/OnboardParserTest.mc`:

```
(:test)
function detect_flatRoute_noClimbs(logger) {
    var eles = new [30];
    for (var i = 0; i < 30; i++) { eles[i] = 100; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 0);
    return true;
}

(:test)
function detect_twoKmAtFivePct_oneClimb(logger) {
    // 21 points, ~100 m apart, +5 m per point => ~2000 m at ~5%
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    Test.assert(d.climbLength[0] >= 1990 && d.climbLength[0] <= 2010);
    Test.assertEqual(d.climbElevGain[0], 100);
    // avg gradient pct*10 ~ 50
    Test.assert(d.climbAvgGrad[0] >= 48 && d.climbAvgGrad[0] <= 52);
    Test.assertEqual(d.climbStartIdx[0], 0);
    Test.assertEqual(d.climbEndIdx[0], 20);
    return true;
}

(:test)
function detect_tooShortOrTooShallow_rejected(logger) {
    // ~700 m at 6% => too short
    var shortE = new [8];
    for (var i = 0; i < 8; i++) { shortE[i] = 100 + i * 6; }
    var d1 = new OnboardClimbData();
    RouteParser.detectClimbs(onbStoreFromEle(shortE), d1);
    Test.assertEqual(d1.climbCount, 0);
    // ~2900 m at 2% => too shallow
    var shallowE = new [30];
    for (var i = 0; i < 30; i++) { shallowE[i] = 100 + i * 2; }
    var d2 = new OnboardClimbData();
    RouteParser.detectClimbs(onbStoreFromEle(shallowE), d2);
    Test.assertEqual(d2.climbCount, 0);
    return true;
}

(:test)
function detect_falseFlatLeadIn_isTrimmed(logger) {
    // 5 flat points (~400 m at 0%) then 16 points at 6% (~1500 m)
    var eles = new [21];
    for (var i = 0; i < 5; i++) { eles[i] = 100; }
    for (var i = 5; i < 21; i++) { eles[i] = 100 + (i - 4) * 6; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    // climb must start at the flat/steep boundary (~400 m), not at 0
    Test.assert(d.climbStartDist[0] >= 380 && d.climbStartDist[0] <= 420);
    Test.assert(d.climbLength[0] >= 1580 && d.climbLength[0] <= 1620);
    return true;
}

(:test)
function detect_trimNeverShrinksBelowMinimum(logger) {
    // 3 lead points at ~1% (300 m) then 6 points at 6% (600 m): total ~900 m,
    // avg ~4.3% => valid climb; trimming the 300 m lead would leave 600 m < 800 m,
    // so the trim must NOT be committed.
    var eles = [100, 101, 102, 103, 109, 115, 121, 127, 133, 139];
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    Test.assert(d.climbStartDist[0] <= 20);   // still starts at the route start
    Test.assert(d.climbLength[0] >= 880);
    return true;
}

(:test)
function detect_downhillGapSplitsClimbs(logger) {
    // climb 1: 10 pts at 6% (~900 m), then 5 pts descending 8 m each (>20 m tol),
    // then climb 2: 10 pts at 6% (~900 m) => two separate climbs
    var eles = new [26];
    for (var i = 0; i <= 9; i++) { eles[i] = 100 + i * 6; }
    for (var i = 10; i <= 14; i++) { eles[i] = eles[9] - (i - 9) * 8; }
    for (var i = 15; i < 26; i++) { eles[i] = eles[14] + (i - 14) * 6; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 2);
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED — "Undefined symbol OnboardClimbData".

- [ ] **Step 3: Write OnboardClimbData**

`garmin-onboard/source/OnboardClimbData.mc`:

```
using Toybox.System as Sys;

/**
 * Results of the on-watch parse: parallel arrays per climb/segment (same
 * layout philosophy as the datafield's ClimbData — minimal allocation).
 * Task 6 adds live GPS tracking state and matching on top of this container.
 */
class OnboardClimbData {

    const MAX_CLIMBS = 16;
    const MAX_SEGMENTS = 16;

    var parsed = false;
    var store = null;          // RawRouteStore backing this parse

    var climbCount = 0;
    var climbStartDist;   // m from route start (trimmed)
    var climbEndDist;     // m from route start (trimmed)
    var climbLength;      // m
    var climbElevGain;    // m
    var climbAvgGrad;     // pct x 10
    var climbStartIdx;    // store point index (trimmed)
    var climbEndIdx;      // store point index (trimmed)

    var segCount;
    var segDist;          // [climb][segment] m
    var segElevGain;      // [climb][segment] m
    var segGradient;      // [climb][segment] pct x 10
    var segColor;         // [climb][segment] 0-5

    function initialize() {
        climbStartDist = new [MAX_CLIMBS];
        climbEndDist   = new [MAX_CLIMBS];
        climbLength    = new [MAX_CLIMBS];
        climbElevGain  = new [MAX_CLIMBS];
        climbAvgGrad   = new [MAX_CLIMBS];
        climbStartIdx  = new [MAX_CLIMBS];
        climbEndIdx    = new [MAX_CLIMBS];
        segCount       = new [MAX_CLIMBS];
        segDist        = new [MAX_CLIMBS];
        segElevGain    = new [MAX_CLIMBS];
        segGradient    = new [MAX_CLIMBS];
        segColor       = new [MAX_CLIMBS];
        for (var i = 0; i < MAX_CLIMBS; i++) {
            segDist[i]     = new [MAX_SEGMENTS];
            segElevGain[i] = new [MAX_SEGMENTS];
            segGradient[i] = new [MAX_SEGMENTS];
            segColor[i]    = new [MAX_SEGMENTS];
        }
        reset();
    }

    function reset() {
        parsed = false;
        store = null;
        climbCount = 0;
        for (var i = 0; i < MAX_CLIMBS; i++) {
            climbStartDist[i] = 0;
            climbEndDist[i] = 0;
            climbLength[i] = 0;
            climbElevGain[i] = 0;
            climbAvgGrad[i] = 0;
            climbStartIdx[i] = 0;
            climbEndIdx[i] = 0;
            segCount[i] = 0;
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
            }
        }
    }
}
```

- [ ] **Step 4: Add parse/detect/trim to RouteParser**

Append inside `module RouteParser { ... }` in `garmin-onboard/source/RouteParser.mc`:

```
    // Full pipeline: smooth -> detect+trim -> segment. Fills `data` from `store`.
    function parse(store, data) {
        data.reset();
        if (store == null || !store.complete || store.pointCount < 2) { return; }
        data.store = store;
        smoothEle(store);
        detectClimbs(store, data);
        for (var c = 0; c < data.climbCount; c++) {
            segmentClimb(store, data, c);
        }
        data.parsed = true;
    }

    // Port of ClimbDetector.detect: sliding scan, extend to the elevation peak
    // with a 20 m downhill tolerance, validate >= 800 m and >= 3%, then trim
    // false flat and store the climb rebuilt from the trimmed indices.
    function detectClimbs(store, data) {
        var n = store.pointCount;
        if (n < 2) { return; }
        var i = 0;
        while (i < n - 1 && data.climbCount < data.MAX_CLIMBS) {
            if (grad(store, i, i + 1) < MIN_AVG_GRADIENT * 0.5) {
                i++;
                continue;
            }
            var startIdx = i;
            var peakEle = store.ele[i];
            var peakIdx = i;
            var j = i + 1;
            while (j < n) {
                var e = store.ele[j];
                if (e > peakEle) {
                    peakEle = e;
                    peakIdx = j;
                } else if (peakEle - e > DOWNHILL_TOLERANCE_M) {
                    break;
                }
                j++;
            }
            var endIdx = peakIdx;
            if (endIdx <= startIdx) {
                i++;
                continue;
            }
            var len = store.dist[endIdx] - store.dist[startIdx];
            var gain = store.ele[endIdx] - store.ele[startIdx];
            if (len < MIN_CLIMB_LENGTH_M || gain <= 0) {
                i = endIdx + 1;
                continue;
            }
            if (gain / len < MIN_AVG_GRADIENT) {
                i = endIdx + 1;
                continue;
            }
            var trimmed = trimFalseFlat(store, startIdx, endIdx);
            var s = trimmed[0];
            var e2 = trimmed[1];
            var c = data.climbCount;
            var tl = store.dist[e2] - store.dist[s];
            var tg = store.ele[e2] - store.ele[s];
            data.climbStartIdx[c]  = s;
            data.climbEndIdx[c]    = e2;
            data.climbStartDist[c] = Math.round(store.dist[s]).toNumber();
            data.climbEndDist[c]   = Math.round(store.dist[e2]).toNumber();
            data.climbLength[c]    = Math.round(tl).toNumber();
            data.climbElevGain[c]  = Math.round(tg).toNumber();
            data.climbAvgGrad[c]   = (tl > 0) ? Math.round((tg / tl) * 1000.0).toNumber() : 0;
            data.climbCount = c + 1;
            // Advance past the ORIGINAL (untrimmed) end, like the phone detector.
            i = endIdx + 1;
        }
    }

    // Port of ClimbTrimmer.trim, index-based. Returns [startIdx, endIdx].
    // A lead-in/lead-out below 2% is only trimmed when it is >= 200 m long AND
    // the remaining climb stays >= 800 m.
    function trimFalseFlat(store, startIdx, endIdx) {
        if (endIdx - startIdx < 2) { return [startIdx, endIdx]; }
        if (store.dist[endIdx] - store.dist[startIdx] <= 0) { return [startIdx, endIdx]; }
        var start = startIdx;
        var end = endIdx;
        var s = startIdx;
        while (s < endIdx && grad(store, s, s + 1) < FALSE_FLAT_MAX_GRADIENT) {
            s++;
        }
        var lead = store.dist[s] - store.dist[startIdx];
        var remainAfterLead = store.dist[endIdx] - store.dist[s];
        if (s > startIdx
                && lead >= FALSE_FLAT_MIN_LENGTH_M
                && remainAfterLead >= MIN_CLIMB_LENGTH_M) {
            start = s;
        }
        var e = endIdx;
        while (e > start && grad(store, e - 1, e) < FALSE_FLAT_MAX_GRADIENT) {
            e--;
        }
        var tail = store.dist[endIdx] - store.dist[e];
        var remainAfterTail = store.dist[e] - store.dist[start];
        if (e < endIdx
                && tail >= FALSE_FLAT_MIN_LENGTH_M
                && remainAfterTail >= MIN_CLIMB_LENGTH_M) {
            end = e;
        }
        return [start, end];
    }

    // Replaced with the real segmenter in the next task.
    function segmentClimb(store, data, c) {
    }
```

- [ ] **Step 5: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=16, failed=0, errors=0`.

- [ ] **Step 6: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): on-watch climb detection + false-flat trim (port of ClimbDetector/ClimbTrimmer)"
```

---

### Task 5: Segmentation (8% fraction) + gradient color mapping

**Files:**
- Modify: `garmin-onboard/source/RouteParser.mc` (replace the no-op `segmentClimb`, add `colorFor`, `interpEle`)
- Create: `garmin-onboard/test/OnboardSegmentTest.mc`

**Interfaces:**
- Consumes: `OnboardClimbData` (Task 4), `RawRouteStore` (Task 2).
- Produces: `RouteParser.segmentClimb(store, data, c)` filling `segCount[c]`, `segDist[c][]`, `segElevGain[c][]`, `segGradient[c][]` (pct×10), `segColor[c][]`; `RouteParser.colorFor(gradientFraction)` → 0–5; `RouteParser.interpEle(store, idx, targetDist)` → Float.

- [ ] **Step 1: Write the failing tests**

`garmin-onboard/test/OnboardSegmentTest.mc`:

```
using Toybox.Test;

(:test)
function seg_colorCutoffs(logger) {
    Test.assertEqual(RouteParser.colorFor(0.0), 0);
    Test.assertEqual(RouteParser.colorFor(-0.05), 0);   // downhill clamps to 0
    Test.assertEqual(RouteParser.colorFor(0.019), 0);
    Test.assertEqual(RouteParser.colorFor(0.02), 1);
    Test.assertEqual(RouteParser.colorFor(0.039), 1);
    Test.assertEqual(RouteParser.colorFor(0.04), 2);
    Test.assertEqual(RouteParser.colorFor(0.06), 3);
    Test.assertEqual(RouteParser.colorFor(0.08), 4);
    Test.assertEqual(RouteParser.colorFor(0.10), 5);
    Test.assertEqual(RouteParser.colorFor(0.15), 5);
    return true;
}

(:test)
function seg_thirteenSegmentsSummingToLength(logger) {
    // ~2000 m at 5% via parse() so smoothing+detection+segmentation run together
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.parse(st, d);
    Test.assert(d.parsed);
    Test.assertEqual(d.climbCount, 1);
    // ceil(1 / 0.08) = 13 segments
    Test.assertEqual(d.segCount[0], 13);
    var sum = 0;
    for (var s = 0; s < d.segCount[0]; s++) { sum += d.segDist[0][s]; }
    var diff = sum - d.climbLength[0];
    if (diff < 0) { diff = -diff; }
    Test.assert(diff <= 13);   // <= 1 m rounding per segment
    return true;
}

(:test)
function seg_constantGradient_uniformColorAndGradient(logger) {
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    // Segment WITHOUT smoothing so the 5% is exact: detect + segment directly.
    RouteParser.detectClimbs(st, d);
    RouteParser.segmentClimb(st, d, 0);
    for (var s = 0; s < d.segCount[0]; s++) {
        Test.assert(d.segGradient[0][s] >= 47 && d.segGradient[0][s] <= 53);
        Test.assertEqual(d.segColor[0][s], 2);   // 5% => 0.04..0.06 => index 2
    }
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED — "Undefined symbol colorFor" (or the 13-segment assert fails against the no-op `segmentClimb`).

- [ ] **Step 3: Implement**

In `garmin-onboard/source/RouteParser.mc`, replace the no-op `segmentClimb` with:

```
    // Port of Segmenter.segment: boundaries every SEGMENT_FRACTION of the climb
    // length (last segment may be shorter), elevation interpolated at each
    // boundary. Invariant: sum(segDist) == climbLength within rounding.
    function segmentClimb(store, data, c) {
        var sIdx = data.climbStartIdx[c];
        var eIdx = data.climbEndIdx[c];
        var startDist = store.dist[sIdx];
        var endDist = store.dist[eIdx];
        var total = endDist - startDist;
        if (total <= 0) {
            data.segCount[c] = 0;
            return;
        }
        var segLen = total * SEGMENT_FRACTION;
        var segStart = startDist;
        var segStartEle = store.ele[sIdx];
        var ptIdx = sIdx + 1;
        var s = 0;
        while (segStart < endDist - 0.5 && s < data.MAX_SEGMENTS) {
            var segEnd = segStart + segLen;
            if (segEnd > endDist) { segEnd = endDist; }
            while (ptIdx < eIdx && store.dist[ptIdx] < segEnd) {
                ptIdx++;
            }
            var endEle = interpEle(store, ptIdx, segEnd);
            var d = segEnd - segStart;
            var g = endEle - segStartEle;
            var gr = (d > 0) ? g / d : 0.0;
            data.segDist[c][s]     = Math.round(d).toNumber();
            data.segElevGain[c][s] = Math.round(g).toNumber();
            data.segGradient[c][s] = Math.round(gr * 1000.0).toNumber();
            data.segColor[c][s]    = colorFor(gr);
            s++;
            segStart = segEnd;
            segStartEle = endEle;
        }
        data.segCount[c] = s;
    }

    // Port of GradientColor.forGradient. Cutoffs are the shared color contract.
    function colorFor(gradient) {
        if (gradient < 0.02) { return 0; }
        if (gradient < 0.04) { return 1; }
        if (gradient < 0.06) { return 2; }
        if (gradient < 0.08) { return 3; }
        if (gradient < 0.10) { return 4; }
        return 5;
    }

    // Linear elevation at targetDist between store points idx-1 and idx.
    function interpEle(store, idx, targetDist) {
        if (idx <= 0) { return store.ele[0]; }
        if (idx >= store.pointCount) { return store.ele[store.pointCount - 1]; }
        var a = idx - 1;
        var span = store.dist[idx] - store.dist[a];
        if (span <= 0) { return store.ele[a]; }
        var t = (targetDist - store.dist[a]) / span;
        if (t < 0) { t = 0.0; }
        if (t > 1) { t = 1.0; }
        return store.ele[a] + t * (store.ele[idx] - store.ele[a]);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=19, failed=0, errors=0`.

- [ ] **Step 5: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): 8%-fraction segmentation + gradient color mapping on watch"
```

---

### Task 6: Live tracking — nearest-point matching, hysteresis, off-route, climb-start alert

**Files:**
- Modify: `garmin-onboard/source/OnboardClimbData.mc` (add runtime state + matching)
- Create: `garmin-onboard/test/OnboardTrackTest.mc`

**Interfaces:**
- Consumes: `OnboardClimbData` container + `RawRouteStore` geometry (this module has the FULL route polyline, so matching is a true nearest-point projection — unlike the datafield's calibration-point scheme).
- Produces: on `OnboardClimbData` — consts `HYSTERESIS_M = 20`, `OFFROUTE_M = 100`, `ALERT_RADIUS_M = 50`, `SEARCH_WINDOW_PTS = 120`; vars `routeProgress` (Float m), `lastMatchIdx`, `activeClimbIndex`, `activeSegmentIndex`, `progressInClimb` (Number m), `distToNextClimb`, `nextClimbIndex`, `offRoute` (Boolean), `alertedClimb` (Boolean[MAX_CLIMBS]), `pendingAlert` (Boolean); functions `updatePosition(latDeg, lonDeg)`, `updateProgress()`, `takeAlert()` → Boolean.

- [ ] **Step 1: Write the failing tests**

`garmin-onboard/test/OnboardTrackTest.mc`:

```
using Toybox.Test;

// Store with a climb from ~800 m to ~2800 m: 8 flat points then 21 points at 5%.
function onbTrackData() {
    var eles = new [29];
    for (var i = 0; i < 8; i++) { eles[i] = 100; }
    for (var i = 8; i < 29; i++) { eles[i] = 100 + (i - 8) * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    // No smoothing: detect + segment directly so climb bounds are exact.
    RouteParser.detectClimbs(st, d);
    RouteParser.segmentClimb(st, d, 0);
    d.store = st;
    d.parsed = true;
    return d;
}

(:test)
function track_progressAdvancesAndClimbActivates(logger) {
    var d = onbTrackData();
    var st = d.store;
    // stand on point 2 (~200 m): before the climb
    d.updatePosition(st.lat[2], st.lon[2]);
    Test.assert(!d.offRoute);
    Test.assert((d.routeProgress - st.dist[2]).abs() < 15.0);
    Test.assertEqual(d.activeClimbIndex, -1);
    Test.assertEqual(d.nextClimbIndex, 0);
    Test.assert(d.distToNextClimb > 500);
    // stand halfway up (~point 18)
    d.updatePosition(st.lat[18], st.lon[18]);
    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assert(d.progressInClimb > 800);
    Test.assert(d.activeSegmentIndex >= 0);
    return true;
}

(:test)
function track_hysteresisRejectsBackwardsJitter(logger) {
    var d = onbTrackData();
    var st = d.store;
    d.updatePosition(st.lat[10], st.lon[10]);
    var p = d.routeProgress;
    // GPS jitter: a fix ~100 m BACK must not move progress backwards
    d.updatePosition(st.lat[9], st.lon[9]);
    Test.assert(d.routeProgress >= p - 1.0);
    // but real forward movement still works afterwards
    d.updatePosition(st.lat[12], st.lon[12]);
    Test.assert(d.routeProgress > p);
    return true;
}

(:test)
function track_farFromRoute_setsOffRoute(logger) {
    var d = onbTrackData();
    var st = d.store;
    d.updatePosition(st.lat[5], st.lon[5]);
    Test.assert(!d.offRoute);
    // ~0.01 deg lon east of the line (> 700 m at lat 50)
    d.updatePosition(st.lat[5], st.lon[5] + 0.01);
    Test.assert(d.offRoute);
    return true;
}

(:test)
function track_alertFiresOncePerClimb(logger) {
    var d = onbTrackData();
    var st = d.store;
    var climbStart = d.climbStartDist[0];
    d.updatePosition(st.lat[3], st.lon[3]);          // far before: no alert
    Test.assert(!d.takeAlert());
    // point 7 is ~700 m; climb starts ~800 m => within 50 m needs a position
    // ~30 m before the start: interpolate between points 7 and 8.
    var t = ((climbStart - 30.0) - st.dist[7]) / (st.dist[8] - st.dist[7]);
    var alat = st.lat[7] + (st.lat[8] - st.lat[7]) * t;
    d.updatePosition(alat, st.lon[7]);
    Test.assert(d.takeAlert());
    Test.assert(!d.takeAlert());                     // consumed
    // drift back and re-approach: idempotent, no second alert
    d.routeProgress = st.dist[6];
    d.lastMatchIdx = 6;
    d.updatePosition(alat, st.lon[7]);
    Test.assert(!d.takeAlert());
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED — "Undefined symbol updatePosition".

- [ ] **Step 3: Implement**

In `garmin-onboard/source/OnboardClimbData.mc`: add `using Toybox.Math as Math;` at the top, add to the consts/vars section:

```
    const HYSTERESIS_M = 20;       // reject backwards progress jumps (GPS jitter)
    const OFFROUTE_M = 100;        // nearest-line distance beyond this = off route
    const ALERT_RADIUS_M = 50;     // climb-start alert window
    const SEARCH_WINDOW_PTS = 120; // vertices searched around the last match (~3 km at 25 m spacing)

    // Live tracking state
    var routeProgress = 0.0;       // m along the route
    var lastMatchIdx = -1;         // route vertex of the last match (-1 = never)
    var activeClimbIndex = -1;
    var activeSegmentIndex = -1;
    var progressInClimb = 0;
    var distToNextClimb = -1;
    var nextClimbIndex = -1;
    var offRoute = false;
    var alertedClimb;              // Boolean per climb, never cleared during a ride
    var pendingAlert = false;
```

In `initialize()` add `alertedClimb = new [MAX_CLIMBS];` before the `reset();` call. In `reset()` add:

```
        routeProgress = 0.0;
        lastMatchIdx = -1;
        activeClimbIndex = -1;
        activeSegmentIndex = -1;
        progressInClimb = 0;
        distToNextClimb = -1;
        nextClimbIndex = -1;
        offRoute = false;
        pendingAlert = false;
        for (var a = 0; a < MAX_CLIMBS; a++) { alertedClimb[a] = false; }
```

Then add these functions to the class:

```
    // Match a GPS fix to the route polyline and update progress/climb state.
    // Windowed search around the last match keeps per-tick cost low; a full
    // scan runs on the first fix or before declaring off-route.
    function updatePosition(latDeg, lonDeg) {
        if (!parsed || store == null || store.pointCount < 2) { return; }
        var n = store.pointCount;
        var lo = 0;
        var hi = n - 2;
        if (lastMatchIdx >= 0) {
            lo = lastMatchIdx - SEARCH_WINDOW_PTS;
            if (lo < 0) { lo = 0; }
            hi = lastMatchIdx + SEARCH_WINDOW_PTS;
            if (hi > n - 2) { hi = n - 2; }
        }
        var m = bestMatch(latDeg, lonDeg, lo, hi);
        if (m[0] > OFFROUTE_M && (lo > 0 || hi < n - 2)) {
            m = bestMatch(latDeg, lonDeg, 0, n - 2);   // widen before giving up
        }
        if (m[0] > OFFROUTE_M) {
            offRoute = true;
            return;
        }
        offRoute = false;
        var idx = m[1];
        var prog = store.dist[idx]
                 + (store.dist[idx + 1] - store.dist[idx]) * m[2];
        if (lastMatchIdx >= 0 && prog < routeProgress - HYSTERESIS_M) {
            return;   // backwards jump beyond the hysteresis window: jitter
        }
        if (prog > routeProgress) {
            routeProgress = prog;
        }
        lastMatchIdx = idx;
        updateProgress();
    }

    // Nearest projection of (lat,lon) onto route segments [lo..hi].
    // Returns [distanceM, segIdx, tAlongSeg].
    hidden function bestMatch(latDeg, lonDeg, lo, hi) {
        var cosLat = Math.cos(latDeg * Math.PI / 180.0);
        var best = -1.0;
        var bestIdx = lo;
        var bestT = 0.0;
        for (var i = lo; i <= hi; i++) {
            var px = (lonDeg - store.lon[i]) * 111111.0 * cosLat;
            var py = (latDeg - store.lat[i]) * 111111.0;
            var bx = (store.lon[i + 1] - store.lon[i]) * 111111.0 * cosLat;
            var by = (store.lat[i + 1] - store.lat[i]) * 111111.0;
            var len2 = bx * bx + by * by;
            var t = (len2 > 0) ? ((px * bx + py * by) / len2) : 0.0;
            if (t < 0) { t = 0.0; }
            if (t > 1) { t = 1.0; }
            var dx = px - bx * t;
            var dy = py - by * t;
            var dm = Math.sqrt(dx * dx + dy * dy);
            if (best < 0 || dm < best) {
                best = dm;
                bestIdx = i;
                bestT = t;
            }
        }
        return [best, bestIdx, bestT];
    }

    // Derive active climb/segment and next-climb info from routeProgress.
    function updateProgress() {
        activeClimbIndex = -1;
        activeSegmentIndex = -1;
        progressInClimb = 0;
        distToNextClimb = -1;
        nextClimbIndex = -1;
        for (var i = 0; i < climbCount; i++) {
            if (routeProgress > climbEndDist[i]) {
                continue;
            }
            if (routeProgress >= climbStartDist[i]) {
                activeClimbIndex = i;
                progressInClimb = (routeProgress - climbStartDist[i]).toNumber();
                var cum = 0;
                for (var s = 0; s < segCount[i]; s++) {
                    cum += segDist[i][s];
                    if (progressInClimb <= cum) {
                        activeSegmentIndex = s;
                        break;
                    }
                }
                if (activeSegmentIndex == -1) {
                    activeSegmentIndex = segCount[i] - 1;
                }
                if (!alertedClimb[i]) {
                    alertedClimb[i] = true;   // entered without passing the window
                    pendingAlert = true;
                }
                if (i + 1 < climbCount) {
                    nextClimbIndex = i + 1;
                    distToNextClimb = (climbStartDist[i + 1] - routeProgress).toNumber();
                }
                return;
            }
            nextClimbIndex = i;
            distToNextClimb = (climbStartDist[i] - routeProgress).toNumber();
            if (distToNextClimb <= ALERT_RADIUS_M && !alertedClimb[i]) {
                alertedClimb[i] = true;   // once per climb; drift-back can't re-arm
                pendingAlert = true;
            }
            return;
        }
    }

    // One-shot consumer for the view: true exactly once per triggered alert.
    function takeAlert() {
        if (pendingAlert) {
            pendingAlert = false;
            return true;
        }
        return false;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=23, failed=0, errors=0`.

- [ ] **Step 5: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): full-polyline GPS matching with hysteresis, off-route, once-per-climb alert"
```

---

### Task 7: Comm listener + app wiring (receive-only, push-only protocol)

**Files:**
- Create: `garmin-onboard/source/OnboardCommListener.mc`
- Modify: `garmin-onboard/source/OnboardApp.mc` (`getInitialView` wiring + storage restore; remove the unused `routeIndex` var from the Task 1 scaffold)
- Create: `garmin-onboard/test/OnboardCommTest.mc`

**Interfaces:**
- Consumes: `RawRouteStore`, `RouteParser.parse`, `OnboardClimbData`, `OnboardApp.processMessage` (Tasks 1–6).
- Produces: `class OnboardMessageCallback` with `onMessage(msg)` handling `RAW_HDR` / `RAW_CHUNK` only — the watch never sends `LIST_ROUTES`/`LOAD_RAW_ROUTE` and never receives `ROUTE_LIST` (push-only protocol, see the design spec referenced above). After the last chunk: parse + `saveToStorage()` automatically.

- [ ] **Step 1: Write the failing tests**

`garmin-onboard/test/OnboardCommTest.mc`:

```
using Toybox.Test;
using Toybox.Application as App;

// Encode a synthetic route (8 flat pts + 21 pts at 5%, ~100 m spacing) into
// RAW_HDR/RAW_CHUNK dictionaries exactly as the phone would push them.
function onbWireMessages() {
    var n = 29;
    var la = new [n];
    var lo = new [n];
    var el = new [n];
    for (var i = 0; i < n; i++) {
        la[i] = 5000000 + i * 90;    // 0.0009 deg steps x 1e5
        lo[i] = 500000;
        var e = (i < 8) ? 100.0 : 100.0 + (i - 8) * 5;
        el[i] = (e * 10).toNumber(); // decimeters
    }
    return [
        { "type" => "RAW_HDR", "id" => "e2e", "name" => "EndToEnd", "n" => n, "tot" => 1 },
        { "type" => "RAW_CHUNK", "id" => "e2e", "seq" => 0, "lat" => la, "lon" => lo, "ele" => el }
    ];
}

function onbApp() {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    return app;
}

(:test)
function comm_rawTransfer_parsesClimbEndToEnd(logger) {
    var app = onbApp();
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    Test.assert(!app.climbData.parsed);
    cb.onMessage(msgs[1]);
    // last chunk arrived => store complete, parse ran, route persisted
    Test.assert(app.store.complete);
    Test.assert(app.climbData.parsed);
    Test.assertEqual(app.climbData.climbCount, 1);
    Test.assert(app.climbData.climbLength[0] >= 1800);
    Test.assert(app.climbData.segCount[0] == 13);
    // persisted: a fresh store restores it
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assertEqual(st2.routeId, "e2e");
    return true;
}

(:test)
function comm_garbageMessages_ignored(logger) {
    var app = onbApp();
    var cb = new OnboardMessageCallback();
    cb.onMessage(null);
    cb.onMessage("string");
    cb.onMessage({ "type" => "UNKNOWN_TYPE" });
    cb.onMessage({ "type" => "RAW_CHUNK", "seq" => 0 });   // chunk before header
    Test.assert(!app.store.complete);
    Test.assert(!app.climbData.parsed);
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED — "Undefined symbol OnboardMessageCallback".

- [ ] **Step 3: Implement**

`garmin-onboard/source/OnboardCommListener.mc`:

```
using Toybox.Application as App;
using Toybox.System as Sys;

/**
 * Push-only protocol: the watch never transmits, it only receives RAW_HDR/
 * RAW_CHUNK from the phone (triggered by the user tapping "Verstuur naar
 * horloge" on the phone's route-detail screen). See
 * docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md.
 */
class OnboardMessageCallback {

    function initialize() {
    }

    function onMessage(msg) {
        if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) { return; }
        var t = msg.get("type");
        if (!(t instanceof Toybox.Lang.String)) { return; }
        var app = App.getApp() as OnboardApp;
        if (app.store == null || app.climbData == null) { return; }

        if (t.equals("RAW_HDR")) {
            app.climbData.reset();
            app.store.beginRoute(msg.get("id"), msg.get("name"),
                                 msg.get("n"), msg.get("tot"));
        } else if (t.equals("RAW_CHUNK")) {
            var ok = app.store.addChunk(msg.get("seq"), msg.get("lat"),
                                        msg.get("lon"), msg.get("ele"));
            if (ok && app.store.complete) {
                RouteParser.parse(app.store, app.climbData);
                app.store.saveToStorage();
                Sys.println("OnboardComm: route parsed, "
                            + app.climbData.climbCount + " climbs");
            }
        } else {
            Sys.println("OnboardComm: unknown type " + t);
        }
    }
}
```

In `garmin-onboard/source/OnboardApp.mc`: delete the `var routeIndex = null;   // OnboardRouteIndex (Task 7)` line from the Task 1 scaffold (no route index exists in the push-only design), and replace `getInitialView` with:

```
    function getInitialView() {
        store = new RawRouteStore();
        climbData = new OnboardClimbData();
        msgCallback = new OnboardMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        // Offline-first: re-parse the last synced route from storage.
        if (store.restoreFromStorage()) {
            RouteParser.parse(store, climbData);
        }
        Sys.println("OnboardApp: started");
        return [new OnboardView(), new OnboardDelegate()];
    }
```

No picker view or stub is needed — `OnboardView`/`OnboardDelegate` already exist from Task 1 (Task 8 replaces `OnboardView`'s placeholder body with the real terrain-window render).

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=25, failed=0, errors=0`.

- [ ] **Step 5: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): receive-only comm listener + app wiring, push-only raw transfer end-to-end"
```

---

### Task 8: View — single "5 km terrain window"

**Files:**
- Modify: `garmin-onboard/source/OnboardView.mc` (full implementation)
- Create: `garmin-onboard/test/OnboardViewTest.mc`

**Interfaces:**
- Consumes: `OnboardApp` vars (`store`, `climbData`), `OnboardClimbData` (`updatePosition`, `takeAlert`, `activeClimbIndex`, `activeSegmentIndex`, `progressInClimb`, `distToNextClimb`, `nextClimbIndex`, `offRoute`, `routeProgress`, `climbCount`, `segCount`/`segDist`/`segElevGain`/`segColor`/`segGradient`, `climbStartDist`/`climbEndDist`/`climbLength`/`climbElevGain`), `RawRouteStore` (`complete`, `ele`, `dist`, `pointCount`, `totalLen()`, `routeName`), `RouteParser.interpEle` (Task 5).
- Produces: `OnboardView` — a single view (no route picker; the watch is receive-only, see Task 7) with hidden `drawWindow(dc, store, data)`; `OnboardDelegate` unchanged from Task 1 (`onBack`); `OnboardView.onPosition(info)` public for tests.

There is no more "active climb" vs. "between climbs" screen split. One view
renders the elevation profile from `routeProgress` to `routeProgress + 5000`
(clamped to route end), with climb segments colored on top of the raw
terrain line — so a short rise that doesn't meet the climb definition (a
Dutch dike, a rolling stretch under 800 m) is still visible in the profile
shape even though it isn't colored. When `offRoute` is true the last computed
window keeps rendering (progress marker frozen) with an "OFF ROUTE" banner on
top, instead of a blank screen — see
`docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md`.

- [ ] **Step 1: Write the failing render tests**

`garmin-onboard/test/OnboardViewTest.mc`:

```
using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;

(:test)
function view_rendersWindowStates(logger) {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    var dc = onbMakeDc();
    var v = new OnboardView();
    // 1: no data
    v.onUpdate(dc);
    // 2: parsed, before the climb (neutral terrain in window)
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);
    app.climbData.updatePosition(app.store.lat[2], app.store.lon[2]);
    v.onUpdate(dc);
    // 3: on the climb (colored segment inside the window)
    app.climbData.updatePosition(app.store.lat[18], app.store.lon[18]);
    Test.assert(app.climbData.activeClimbIndex == 0);
    v.onUpdate(dc);
    // 4: at the route's last point (window clamps to zero length)
    var n = app.store.pointCount;
    app.climbData.updatePosition(app.store.lat[n - 1], app.store.lon[n - 1]);
    v.onUpdate(dc);
    return true;
}

(:test)
function view_offRoute_bannerDrawnWithoutCrash(logger) {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    var dc = onbMakeDc();
    var v = new OnboardView();
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);
    app.climbData.updatePosition(app.store.lat[5], app.store.lon[5]);
    v.onUpdate(dc);
    // ~0.01 deg lon east of the line (> 700 m at lat 50) => off route
    app.climbData.updatePosition(app.store.lat[5], app.store.lon[5] + 0.01);
    Test.assert(app.climbData.offRoute);
    v.onUpdate(dc);   // window still renders the frozen progress + banner
    return true;
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: BUILD FAILED (the Task 1 placeholder `OnboardView` has no `drawWindow`/window-clamp/off-route-banner behavior yet — `view_rendersWindowStates`/`view_offRoute_bannerDrawnWithoutCrash` exercise the real implementation written in Step 3).

- [ ] **Step 3: Implement the terrain-window view**

Replace `garmin-onboard/source/OnboardView.mc` entirely:

```
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Position as Position;
using Toybox.Attention as Attention;
using Toybox.System as Sys;

/**
 * Single "5 km terrain window" view fed by the ON-WATCH parse: a doorlopend
 * elevation profile from the current position to +5 km (clamped to route
 * end), with detected-climb segments colored on top of the raw terrain line.
 * Terrain that never meets the climb definition (short rises, a dike) is
 * still visible via the profile shape, just not colored. offRoute keeps the
 * last window on screen (progress marker frozen) with a banner overlay
 * instead of going blank. Battery rule: redraw only when the active
 * segment/climb/off-route state changes or progress moved more than
 * REDRAW_DELTA_M.
 */
class OnboardView extends Ui.View {

    hidden const COLORS = [
        0x99FF99, 0xFFFF00, 0xFFAA00, 0xFF5500, 0xFF0000, 0xAA0000,
    ];
    hidden const REDRAW_DELTA_M = 10.0;
    hidden const WINDOW_M = 5000.0;

    hidden var lastDrawnProgress = -1.0;
    hidden var lastDrawnSegment = -2;
    hidden var lastDrawnClimb = -2;
    hidden var lastDrawnOffRoute = false;

    function initialize() {
        View.initialize();
    }

    function onShow() {
        Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
    }

    function onHide() {
        Position.enableLocationEvents(Position.LOCATION_DISABLE, method(:onPosition));
    }

    function onPosition(info as Position.Info) as Void {
        var data = (App.getApp() as OnboardApp).climbData;
        if (data == null || !data.parsed || info == null || info.position == null) {
            return;
        }
        var ll = info.position.toDegrees();
        data.updatePosition(ll[0], ll[1]);
        if (data.takeAlert()) {
            if (Attention has :vibrate) {
                Attention.vibrate([new Attention.VibeProfile(50, 1000)]);
            }
            if (Attention has :playTone) {
                Attention.playTone(Attention.TONE_ALERT_HI);
            }
            Ui.requestUpdate();
            markDrawn(data);
            return;
        }
        // Redraw only on segment/climb/off-route change or a significant move (battery).
        if (data.activeClimbIndex != lastDrawnClimb
                || data.activeSegmentIndex != lastDrawnSegment
                || data.offRoute != lastDrawnOffRoute
                || (data.routeProgress - lastDrawnProgress).abs() > REDRAW_DELTA_M) {
            Ui.requestUpdate();
            markDrawn(data);
        }
    }

    hidden function markDrawn(data) {
        lastDrawnProgress = data.routeProgress;
        lastDrawnSegment = data.activeSegmentIndex;
        lastDrawnClimb = data.activeClimbIndex;
        lastDrawnOffRoute = data.offRoute;
    }

    function onUpdate(dc) {
        var app = App.getApp() as OnboardApp;
        var data = app.climbData;
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        if (data == null || !data.parsed) {
            dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
                        "Geen route", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }
        drawWindow(dc, app.store, data);
    }

    // The one and only live screen: elevation profile from routeProgress to
    // routeProgress + WINDOW_M (clamped to route end), climbs colored on top.
    hidden function drawWindow(dc, store, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.drawText(w / 2, 12, Gfx.FONT_TINY,
                    (store != null && store.routeName != null) ? store.routeName : "Route",
                    Gfx.TEXT_JUSTIFY_CENTER);
        if (store == null || !store.complete || store.pointCount < 2) { return; }

        var total = store.totalLen();
        if (total <= 0) { return; }

        var winStart = data.routeProgress;
        if (winStart < 0) { winStart = 0.0; }
        if (winStart > total) { winStart = total; }
        var winEnd = winStart + WINDOW_M;
        if (winEnd > total) { winEnd = total; }
        var winLen = winEnd - winStart;

        var profX = 10;
        var profW = w - 20;
        var profY = h / 2 - 20;
        var profH = h / 4;
        var baseline = profY + profH;

        if (winLen <= 1.0) {
            dc.drawText(w / 2, baseline + 18, Gfx.FONT_SMALL, "Einde route bereikt",
                        Gfx.TEXT_JUSTIFY_CENTER);
            drawOffRouteBanner(dc, data, w);
            return;
        }

        var startIdx = indexAtDist(store, winStart);
        var endIdx = indexAtDist(store, winEnd);
        var minE = store.ele[startIdx];
        var maxE = store.ele[startIdx];
        for (var i = startIdx; i <= endIdx; i++) {
            if (store.ele[i] < minE) { minE = store.ele[i]; }
            if (store.ele[i] > maxE) { maxE = store.ele[i]; }
        }
        var span = maxE - minE;
        if (span <= 0) { span = 1.0; }

        // Raw terrain outline, one column per pixel — this is what makes a
        // sub-threshold rise (e.g. a dike) visible even though it's never colored.
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        var pi = startIdx;
        for (var px = 0; px < profW; px++) {
            var target = winStart + winLen * px / profW;
            while (pi < endIdx && store.dist[pi + 1] < target) {
                pi++;
            }
            var e = RouteParser.interpEle(store, pi + 1, target);
            var y = baseline - (((e - minE) * profH) / span).toNumber();
            dc.drawLine(profX + px, baseline, profX + px, y);
        }

        // Detected climbs overlapping the window, colored by worst segment.
        for (var c = 0; c < data.climbCount; c++) {
            if (data.climbEndDist[c] <= winStart || data.climbStartDist[c] >= winEnd) {
                continue;
            }
            var cs = clampToWin(data.climbStartDist[c], winStart, winEnd);
            var ce = clampToWin(data.climbEndDist[c], winStart, winEnd);
            var x1 = profX + (((cs - winStart) * profW) / winLen).toNumber();
            var x2 = profX + (((ce - winStart) * profW) / winLen).toNumber();
            var worst = 0;
            for (var s = 0; s < data.segCount[c]; s++) {
                if (data.segColor[c][s] > worst) { worst = data.segColor[c][s]; }
            }
            dc.setColor(COLORS[worst], Gfx.COLOR_TRANSPARENT);
            dc.fillRectangle(x1, baseline + 3, (x2 - x1 > 0) ? x2 - x1 : 1, 4);
        }

        // Position marker
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        var mx = profX + (((data.routeProgress - winStart) * profW) / winLen).toNumber();
        dc.drawLine(mx, profY - 4, mx, baseline + 8);

        var msg;
        if (data.activeClimbIndex >= 0 && data.activeSegmentIndex >= 0) {
            var g10 = data.segGradient[data.activeClimbIndex][data.activeSegmentIndex];
            msg = (g10 / 10) + "." + (g10 % 10).abs() + "%";
        } else if (data.nextClimbIndex >= 0 && data.distToNextClimb >= 0
                && data.climbStartDist[data.nextClimbIndex] < winEnd) {
            msg = "Klim " + (data.nextClimbIndex + 1) + " over " + fmtKm(data.distToNextClimb);
        } else {
            msg = "Komende " + fmtKm(winLen.toNumber());
        }
        dc.drawText(w / 2, baseline + 18, Gfx.FONT_SMALL, msg, Gfx.TEXT_JUSTIFY_CENTER);

        drawOffRouteBanner(dc, data, w);
    }

    hidden function drawOffRouteBanner(dc, data, w) {
        if (!data.offRoute) { return; }
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_RED);
        dc.fillRectangle(0, 0, w, 20);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_TINY, "OFF ROUTE", Gfx.TEXT_JUSTIFY_CENTER);
    }

    // First store index whose cumulative distance is >= d (linear scan; the
    // window is only recomputed on redraw, not every GPS tick).
    hidden function indexAtDist(store, d) {
        var n = store.pointCount;
        var i = 0;
        while (i < n - 1 && store.dist[i + 1] < d) { i++; }
        return i;
    }

    hidden function clampToWin(d, lo, hi) {
        if (d < lo) { return lo; }
        if (d > hi) { return hi; }
        return d;
    }

    hidden function fmtKm(m) {
        if (m >= 1000) {
            var tenths = ((m % 1000) / 100).toNumber();
            return (m / 1000).toNumber() + "." + tenths + " km";
        }
        return m.toNumber() + " m";
    }
}

class OnboardDelegate extends Ui.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
```

`OnboardDelegate` already exists from Task 1 — this replaces the whole file, so there's only ever one copy.

- [ ] **Step 4: Run tests to verify they pass**

```powershell
& .\tools\run-monkeyc-tests.ps1 -Modules garmin-onboard
```

Expected: `passed=27, failed=0, errors=0`.

- [ ] **Step 5: Manual simulator sanity check (no phone needed)**

The simulator can feed GPS. Build and launch the real app once:

```powershell
$sdk = (Get-Content "$env:APPDATA\Garmin\ConnectIQ\current-sdk.cfg" -Raw).Trim()
& "$sdk\bin\monkeyc.bat" -f garmin-onboard\monkey.jungle -o build\garmin-onboard.prg -y "$env:APPDATA\Garmin\ConnectIQ\developer_key.der" -d fr255m
& "$sdk\bin\monkeydo.bat" build\garmin-onboard.prg fr255m
```

Expected: app opens showing "Geen route" (no phone in the sim, nothing persisted yet). This is a smoke check only; message flow and rendering were covered by unit tests.

- [ ] **Step 6: Commit**

```powershell
git add garmin-onboard
git commit -m "feat(onboard): single 5km terrain-window view with off-route banner"
```

---

### Task 9: Android — RawRoutePayloadBuilder (decimation, fixed-point, chunking)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/RawRoutePayloadBuilder.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/RawRoutePayloadBuilderTest.java`

**Interfaces:**
- Consumes: `StoredRoute` (`lats`, `lons`, `elevations` double arrays, `routeId`, `name`, `userDisplayName` — see `data/route/StoredRoute.java`).
- Produces: `public final class RawRoutePayloadBuilder` with `public static final int MAX_RAW_POINTS = 6000`, `public static final int CHUNK_POINTS = 250`, and `public List<Map<String, Object>> buildMessages(StoredRoute route)` returning `[RAW_HDR, RAW_CHUNK...]` exactly as specified in the "New wire protocol" section. Task 10's `OnboardPushService` calls this.

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/nl/paree/climbpro/service/RawRoutePayloadBuilderTest.java`:

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RawRoutePayloadBuilderTest {

    private static StoredRoute route(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Rit";
        r.lats = new double[n];
        r.lons = new double[n];
        r.elevations = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50.0 + i * 0.0009;
            r.lons[i] = 5.0;
            r.elevations[i] = 100.0 + i;
        }
        return r;
    }

    @Test
    public void smallRoute_headerPlusOneChunk_fixedPointValues() {
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(3));
        assertEquals(2, msgs.size());

        Map<String, Object> hdr = msgs.get(0);
        assertEquals("RAW_HDR", hdr.get("type"));
        assertEquals("r1", hdr.get("id"));
        assertEquals("Rit", hdr.get("name"));
        assertEquals(3, hdr.get("n"));
        assertEquals(1, hdr.get("tot"));

        Map<String, Object> chunk = msgs.get(1);
        assertEquals("RAW_CHUNK", chunk.get("type"));
        assertEquals(0, chunk.get("seq"));
        List<Integer> lat = (List<Integer>) chunk.get("lat");
        List<Integer> ele = (List<Integer>) chunk.get("ele");
        assertEquals(3, lat.size());
        assertEquals(Integer.valueOf(5000000), lat.get(0));
        assertEquals(Integer.valueOf(5000090), lat.get(1));   // 50.0009 * 1e5
        assertEquals(Integer.valueOf(1000), ele.get(0));       // 100.0 m -> decimeters
        assertEquals(Integer.valueOf(1020), ele.get(2));
    }

    @Test
    public void chunking_251Points_twoChunks() {
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(251));
        assertEquals(3, msgs.size());
        assertEquals(2, msgs.get(0).get("tot"));
        assertEquals(250, ((List<?>) msgs.get(1).get("lat")).size());
        assertEquals(1, ((List<?>) msgs.get(2).get("lat")).size());
        assertEquals(1, msgs.get(2).get("seq"));
    }

    @Test
    public void noDecimation_below_max_allPointsKept() {
        // 4000 < MAX_RAW_POINTS: every point must ship
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(route(4000));
        assertEquals(4000, msgs.get(0).get("n"));
        assertEquals(16, msgs.get(0).get("tot"));
    }

    @Test
    public void decimation_capsAtMaxAndKeepsLastPoint() {
        StoredRoute big = route(15000);
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(big);
        int n = (Integer) msgs.get(0).get("n");
        assertTrue("decimated to <= MAX_RAW_POINTS", n <= RawRoutePayloadBuilder.MAX_RAW_POINTS);
        assertTrue("did not over-decimate", n > RawRoutePayloadBuilder.MAX_RAW_POINTS / 2);
        // gather all lat values across chunks; the LAST original point must survive
        List<Integer> all = new java.util.ArrayList<>();
        for (int i = 1; i < msgs.size(); i++) {
            all.addAll((List<Integer>) msgs.get(i).get("lat"));
        }
        assertEquals(n, all.size());
        int expectedLast = (int) Math.round(big.lats[14999] * 100000.0);
        assertEquals(Integer.valueOf(expectedLast), all.get(all.size() - 1));
    }

    @Test
    public void nanElevation_replacedWithPreviousFinite() {
        StoredRoute r = route(3);
        r.elevations[1] = Double.NaN;
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(r);
        List<Integer> ele = (List<Integer>) msgs.get(1).get("ele");
        assertEquals(ele.get(0), ele.get(1));   // NaN carried forward
    }

    @Test
    public void userDisplayName_preferredOverName() {
        StoredRoute r = route(2);
        r.userDisplayName = "Mijn rit";
        List<Map<String, Object>> msgs = new RawRoutePayloadBuilder().buildMessages(r);
        assertEquals("Mijn rit", msgs.get(0).get("name"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
Set-Location android
.\gradlew.bat test --tests "nl.paree.climbpro.service.RawRoutePayloadBuilderTest"
Set-Location ..
```

Expected: compilation FAILS — `RawRoutePayloadBuilder` does not exist.

- [ ] **Step 3: Implement**

`android/app/src/main/java/nl/paree/climbpro/service/RawRoutePayloadBuilder.java`:

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.data.route.StoredRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the raw-route message sequence for the "ClimbPro Onboard" watch app
 * (garmin-onboard), which does ALL route analysis on the watch. Deliberately
 * ships only geometry — no distances, climbs, or segments.
 *
 * Wire contract (protocol/raw-route.md, mirrored by RawRouteStore.mc):
 *   RAW_HDR  {type,id,name,n,tot}
 *   RAW_CHUNK{type,id,seq,lat[],lon[],ele[]}  lat/lon = deg*1e5, ele = decimeters
 * Constants MUST stay equal to RawRouteStore.MAX_POINTS / CHUNK_POINTS.
 */
public final class RawRoutePayloadBuilder {

    public static final int MAX_RAW_POINTS = 6000;   // ~25 m spacing on 150 km
    public static final int CHUNK_POINTS   = 250;

    public List<Map<String, Object>> buildMessages(StoredRoute route) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            throw new IllegalArgumentException("route has no points");
        }
        int total = route.lats.length;
        // Uniform stride decimation; the last point is always kept so the
        // watch sees the full route length.
        int stride = (total + MAX_RAW_POINTS - 1) / MAX_RAW_POINTS;
        List<Integer> lat = new ArrayList<>();
        List<Integer> lon = new ArrayList<>();
        List<Integer> ele = new ArrayList<>();
        double lastFiniteEle = 0.0;
        for (int i = 0; i < total; i += stride) {
            lastFiniteEle = addPoint(route, i, lat, lon, ele, lastFiniteEle);
        }
        if ((total - 1) % stride != 0) {
            addPoint(route, total - 1, lat, lon, ele, lastFiniteEle);
        }

        int n = lat.size();
        int chunks = (n + CHUNK_POINTS - 1) / CHUNK_POINTS;

        List<Map<String, Object>> messages = new ArrayList<>(chunks + 1);
        Map<String, Object> hdr = new LinkedHashMap<>();
        hdr.put("type", "RAW_HDR");
        hdr.put("id",   route.routeId);
        hdr.put("name", route.userDisplayName != null ? route.userDisplayName : route.name);
        hdr.put("n",    n);
        hdr.put("tot",  chunks);
        messages.add(hdr);

        for (int c = 0; c < chunks; c++) {
            int from = c * CHUNK_POINTS;
            int to = Math.min(from + CHUNK_POINTS, n);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("type", "RAW_CHUNK");
            chunk.put("id",   route.routeId);
            chunk.put("seq",  c);
            chunk.put("lat",  new ArrayList<>(lat.subList(from, to)));
            chunk.put("lon",  new ArrayList<>(lon.subList(from, to)));
            chunk.put("ele",  new ArrayList<>(ele.subList(from, to)));
            messages.add(chunk);
        }
        return messages;
    }

    private static double addPoint(StoredRoute route, int i,
                                   List<Integer> lat, List<Integer> lon, List<Integer> ele,
                                   double lastFiniteEle) {
        lat.add((int) Math.round(route.lats[i] * 100000.0));
        lon.add((int) Math.round(route.lons[i] * 100000.0));
        double e = route.elevations != null && i < route.elevations.length
                ? route.elevations[i] : Double.NaN;
        if (Double.isNaN(e)) {
            e = lastFiniteEle;   // carry the previous finite sample forward
        }
        ele.add((int) Math.round(e * 10.0));
        return e;
    }
}
```

- [ ] **Step 4: Run to verify pass**

```powershell
Set-Location android
.\gradlew.bat test --tests "nl.paree.climbpro.service.RawRoutePayloadBuilderTest"
Set-Location ..
```

Expected: BUILD SUCCESSFUL, 6 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add android
git commit -m "feat(android): raw route payload builder for the onboard watch app"
```

---

### Task 10: Android — ONBOARD app id, send-only client channel, OnboardPushService, "Verstuur naar horloge" button

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/service/OnboardPushService.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/OnboardPushServiceTest.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailViewModel.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`
- Modify: `android/app/src/main/res/layout/activity_route_detail.xml`
- Modify: `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqAppIdTest.java` (add ONBOARD check, same style as the existing asserts)

**Interfaces:**
- Consumes: `RawRoutePayloadBuilder.buildMessages` (Task 9), `RouteRepository.loadRoute(id)` (throws `IOException`), `ConnectIqClient.sendMessageToOnboard`.
- Produces: `ConnectIqAppId.ONBOARD = "a0b1c2d3e4f50617a0b1c2d3e4f50617"` (MUST equal `garmin-onboard/manifest.xml` id); `ConnectIqClient.sendMessageToOnboard(Map)` — send-only, no inbound registration, since the watch never transmits (push-only protocol, Task 7); `OnboardPushService.pushRoute(StoredRoute route)` → `boolean`, sending `RAW_HDR` then every `RAW_CHUNK` in order and stopping at the first failed send; `RouteDetailViewModel.sendToOnboard(String routeId)` + `onboardPushMessage()` LiveData for the toast; a "Verstuur naar horloge" button on the route detail screen.

- [ ] **Step 1: Write the failing push-service test**

`android/app/src/test/java/nl/paree/climbpro/service/OnboardPushServiceTest.java`:

```java
package nl.paree.climbpro.service;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class OnboardPushServiceTest {

    @Mock ConnectIqClient mockClient;

    private static StoredRoute route(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Rit";
        r.lats = new double[n];
        r.lons = new double[n];
        r.elevations = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50.0 + i * 0.0009;
            r.lons[i] = 5.0;
            r.elevations[i] = 100.0;
        }
        return r;
    }

    @Test
    public void pushRoute_sendsHeaderThenChunksInOrder() {
        when(mockClient.sendMessageToOnboard(any())).thenReturn(true);
        OnboardPushService svc = new OnboardPushService(mockClient);
        boolean ok = svc.pushRoute(route(251));
        assertTrue(ok);
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient, times(3)).sendMessageToOnboard(captor.capture());
        List<Map> sent = captor.getAllValues();
        assertEquals("RAW_HDR", sent.get(0).get("type"));
        assertEquals("RAW_CHUNK", sent.get(1).get("type"));
        assertEquals(0, sent.get(1).get("seq"));
        assertEquals(1, sent.get(2).get("seq"));
    }

    @Test
    public void pushRoute_nullRoute_sendsNothingAndReturnsFalse() {
        OnboardPushService svc = new OnboardPushService(mockClient);
        assertFalse(svc.pushRoute(null));
        verifyNoInteractions(mockClient);
    }

    @Test
    public void pushRoute_sendFailure_stopsAndReturnsFalse() {
        when(mockClient.sendMessageToOnboard(any())).thenReturn(true, false, true);
        OnboardPushService svc = new OnboardPushService(mockClient);
        boolean ok = svc.pushRoute(route(251));
        assertFalse(ok);
        verify(mockClient, times(2)).sendMessageToOnboard(any());
    }
}
```

- [ ] **Step 2: Run to verify failure**

```powershell
Set-Location android
.\gradlew.bat test --tests "nl.paree.climbpro.service.OnboardPushServiceTest"
Set-Location ..
```

Expected: compilation FAILS — `OnboardPushService` / `ConnectIqClient.sendMessageToOnboard` do not exist.

- [ ] **Step 3: Implement the app-id, client channel, and push service**

In `ConnectIqAppId.java` add below `SURFACE_FIELD`:

```java
    /** "ClimbPro Onboard" watch app (on-watch route parsing). MUST equal the id in garmin-onboard/manifest.xml. */
    public static final String ONBOARD = "a0b1c2d3e4f50617a0b1c2d3e4f50617";
```

In `ConnectIqClient.java`, next to the other `IQApp` fields (`ConnectIqClient.java:60-62`) add:

```java
    private final IQApp onboardApp = new IQApp(ConnectIqAppId.ONBOARD);
```

and next to `sendPayloadToSurfaceField` add:

```java
    /** Send a Map to the ClimbPro Onboard watch app (raw-route push channel). No
     *  inbound registration is needed — the watch never transmits anything back. */
    public boolean sendMessageToOnboard(Map<String, Object> message) {
        return sendMessageTo(onboardApp, message);
    }
```

Create `android/app/src/main/java/nl/paree/climbpro/service/OnboardPushService.java`:

```java
package nl.paree.climbpro.service;

import android.util.Log;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.List;
import java.util.Map;

/**
 * Pushes a route's raw geometry to the "ClimbPro Onboard" watch app,
 * unprompted. The watch never requests routes — the phone decides when to
 * send, triggered by the user tapping "Verstuur naar horloge" on a route's
 * detail screen (RouteDetailActivity).
 */
public final class OnboardPushService {

    private static final String TAG = "OnboardPushService";

    private final ConnectIqClient connectIqClient;
    private final RawRoutePayloadBuilder builder = new RawRoutePayloadBuilder();

    public OnboardPushService(ConnectIqClient connectIqClient) {
        this.connectIqClient = connectIqClient;
    }

    /** Sends RAW_HDR then every RAW_CHUNK in order; stops at the first failed send. */
    public boolean pushRoute(StoredRoute route) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            Log.w(TAG, "pushRoute: route has no geometry");
            return false;
        }
        List<Map<String, Object>> messages = builder.buildMessages(route);
        for (Map<String, Object> m : messages) {
            if (!connectIqClient.sendMessageToOnboard(m)) {
                Log.w(TAG, "pushRoute: send failed for " + m.get("type"));
                return false;
            }
        }
        Log.i(TAG, "Pushed route " + route.routeId + " in " + (messages.size() - 1) + " chunks");
        return true;
    }
}
```

- [ ] **Step 4: Run the push-service test**

```powershell
Set-Location android
.\gradlew.bat test --tests "nl.paree.climbpro.service.OnboardPushServiceTest"
Set-Location ..
```

Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 5: Wire the "Verstuur naar horloge" button**

In `android/app/src/main/res/layout/activity_route_detail.xml`, add a button right after the existing `btn_select_route` (before `btn_share_to_garmin`):

```xml
            <Button
                android:id="@+id/btn_send_to_onboard"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="Verstuur naar horloge" />
```

In `RouteDetailViewModel.java`, add the imports `nl.paree.climbpro.ClimbProApplication` and `nl.paree.climbpro.service.OnboardPushService`, a field + LiveData, construct the service in the constructor, and add `sendToOnboard`:

```java
    private final OnboardPushService onboardPushService;
    private final MutableLiveData<String> onboardPushMessage = new MutableLiveData<>();
```

```java
    public RouteDetailViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        riderRepo = new RiderProfileRepository(app);
        onboardPushService = new OnboardPushService(
                ((ClimbProApplication) app).connectIqClient());
    }
```

```java
    public LiveData<String> onboardPushMessage() { return onboardPushMessage; }

    /** Loads the route fresh and pushes its raw geometry to the onboard watch app. */
    public void sendToOnboard(String routeId) {
        executor.execute(() -> {
            try {
                StoredRoute r = routeRepo.loadRoute(routeId);
                boolean ok = onboardPushService.pushRoute(r);
                onboardPushMessage.postValue(
                        ok ? "Verstuurd naar horloge" : "Versturen naar horloge mislukt");
            } catch (Exception e) {
                onboardPushMessage.postValue("Versturen mislukt: " + e.getMessage());
            }
        });
    }
```

In `RouteDetailActivity.java`, next to the existing `binding.btnSelectRoute.setOnClickListener(...)` add:

```java
        binding.btnSendToOnboard.setOnClickListener(v -> viewModel.sendToOnboard(routeId));
```

and next to the existing `viewModel.saved().observe(...)` add:

```java
        viewModel.onboardPushMessage().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
```

In `ConnectIqAppIdTest.java`, add one test in the file's existing style:

```java
    @Test
    public void onboardId_matchesGarminOnboardManifest() {
        assertEquals("a0b1c2d3e4f50617a0b1c2d3e4f50617", ConnectIqAppId.ONBOARD);
    }
```

(If the existing tests read the manifest XML from disk instead of hardcoding, copy that pattern with path `garmin-onboard/manifest.xml`.)

- [ ] **Step 6: Run the Android test suite**

```powershell
Set-Location android
.\gradlew.bat test
Set-Location ..
```

Expected: BUILD SUCCESSFUL — new push-service/app-id tests pass, all existing tests still green.

- [ ] **Step 7: Commit**

```powershell
git add android
git commit -m "feat(android): onboard app channel + push service + send-to-watch button"
```

---

### Task 11: Protocol doc, architecture docs, full-suite verification

**Files:**
- Create: `protocol/raw-route.md`
- Modify: `Documentation/ARCHITECTURE.md` (new module section)
- Modify: `README.md` (module list)
- Modify: `CLAUDE.md` (module list + test-runner notes)

**Interfaces:**
- Consumes: everything above. No code changes — docs must match what was built (constants, message shapes, file names).

- [ ] **Step 1: Write `protocol/raw-route.md`**

```markdown
# Raw-route side protocol (garmin-onboard) — push-only

Separate from the v3 packed climb payload in `schema.json` — this channel
ships UNANALYSED geometry to the "ClimbPro Onboard" watch app
(CIQ id `a0b1c2d3e4f50617a0b1c2d3e4f50617`), which runs the full climb
pipeline on the watch (RouteParser.mc). Producers/consumers:
`service/RawRoutePayloadBuilder.java` (called from `service/OnboardPushService.java`)
<-> `garmin-onboard/source/RawRouteStore.mc`.

Push-only: the watch never transmits anything. The phone decides when to
send, triggered by the user tapping "Verstuur naar horloge" on a route's
detail screen. Phone -> watch only:
- `{"type":"RAW_HDR","id","name","n":<pointCount>,"tot":<chunkCount>}`
- `{"type":"RAW_CHUNK","id","seq":<0-based>,"lat":[...],"lon":[...],"ele":[...]}`

Encoding: lat/lon = degrees x 100000 (Number), ele = decimeters (Number).
No distances on the wire — the watch computes cumulative distance itself.

Shared constants (change BOTH sides together):
- MAX_RAW_POINTS = 6000 (`RawRoutePayloadBuilder` / `RawRouteStore.MAX_POINTS`)
  => ~25 m point spacing on a 150 km route
- CHUNK_POINTS   = 250  (`RawRoutePayloadBuilder` / `RawRouteStore.CHUNK_POINTS`)
  => at most 24 chunks per transfer

Phone decimates by uniform stride to <= MAX_RAW_POINTS and always keeps the
last point. Chunks may arrive out of order; duplicates are ignored; the watch
parses + persists when the last chunk lands. The watch persists the arrays in
slices of 2000 points per Storage key (per-value size limits).

The watch's only live screen is a "5 km terrain window": the elevation
profile from the current position to +5 km (clamped to route end), with
detected-climb segments colored on top of the raw terrain line so
sub-threshold terrain (a short rise, a dike) is still visible in the shape
even when it's never colored. When the GPS fix strays more than 100 m from
the route (`OFFROUTE_M`), the last computed window keeps rendering with an
"OFF ROUTE" banner instead of freezing silently — see
`docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md`.
```

- [ ] **Step 2: Update `Documentation/ARCHITECTURE.md`**

Add a section (next to the existing module descriptions) titled **"garmin-onboard — on-watch parsing experiment"** stating: fourth watch module, watch-app type, own CIQ UUID; receives raw geometry via `protocol/raw-route.md`, **push-only** — the phone pushes unprompted via a "Verstuur naar horloge" button, the watch never requests or lists routes; runs the full pipeline on-watch (`RouteParser.mc` ports `ElevationSmoother`/`ClimbDetector`/`ClimbTrimmer`/`Segmenter`/`GradientColor` — domain rules identical, listed in the Global Constraints of this plan); live tracking uses full-polyline nearest-point matching with 20 m hysteresis instead of calibration points; the single live screen is a 5 km terrain window (not a climb-only view) with an off-route banner overlay instead of silently-stale data; deliberately inverts the "heavy compute on the phone" rule as a self-contained experiment; the three existing modules and the v3 payload are untouched.

- [ ] **Step 3: Update `README.md` and `CLAUDE.md`**

- `README.md`: add `garmin-onboard/` to the module/folder overview with one line: "watch-app that receives a pushed raw route and does the entire ClimbPro analysis on the watch, rendering a 5 km terrain window".
- `CLAUDE.md`:
  - In the Connect IQ bullet, extend the module enumeration `(garmin, garmin-widget, garmin-surface)` to include `garmin-onboard`, and update the test-count sentence to reflect the new totals from Step 4 (run first, then write the real numbers).
  - In "Locked-in technical decisions", append one bullet: "**garmin-onboard exception**: the onboard module intentionally parses the raw route on the watch (experiment); the heavy-compute-on-phone rule still governs the other modules. Push-only wire contract in `protocol/raw-route.md` (phone → watch, triggered by a phone-side button — the watch never requests), constants duplicated in `RawRoutePayloadBuilder`/`RawRouteStore` — change together."

- [ ] **Step 4: Full verification — all four watch suites + Android**

```powershell
& .\tools\run-monkeyc-tests.ps1
Set-Location android
.\gradlew.bat test
Set-Location ..
```

Expected: `ALL MONKEY C TESTS PASSED` with garmin=89, widget=38, surface=30, onboard=27 (total 184), and Android `BUILD SUCCESSFUL`. Write the actual onboard/total numbers into CLAUDE.md (Step 3).

- [ ] **Step 5: Commit**

```powershell
git add protocol Documentation README.md CLAUDE.md
git commit -m "docs: push-only raw-route protocol + terrain-window architecture notes"
```

---

## Self-review notes (already applied)

- **Spec coverage:** separate folder ✔ (Task 1); whole route parsed on the Garmin ✔ (Tasks 2–5: distances, smoothing, detection, trim, segmentation, colors all on-watch); "een soort ClimbPro" ✔ (Task 8 terrain-window live view + Task 6 tracking/alert); route delivery ✔ (Tasks 7, 9, 10 — CIQ apps cannot read course files, so raw points come from the phone); phone-only push (no watch-side picker/request) ✔ (Task 7 receive-only comm listener, Task 10 `OnboardPushService` + button); off-route fallback so displayed data can't go silently stale ✔ (Task 6 `offRoute` flag unchanged, Task 8 banner overlay keeps it visibly flagged instead of freezing silently); "toon ook wat niet als klim telt" (e.g. a dike) ✔ (Task 8 raw terrain line drawn under the colored climb overlay for the whole 5 km window, not just detected climbs).
- **Domain rules:** 800 m/3%, 2%-over-200 m trim with 800 m floor, 8% segments, color cutoffs, 50 m once-per-climb alert, offline-first via Storage — each has a dedicated test.
- **Type consistency:** `RawRouteStore.MAX_POINTS/CHUNK_POINTS` = `RawRoutePayloadBuilder.MAX_RAW_POINTS/CHUNK_POINTS` (6000/250, Storage sliced per 2000 points); UUID `a0b1c2d3e4f50617a0b1c2d3e4f50617` appears in manifest (Task 1) and `ConnectIqAppId.ONBOARD` (Task 10); `segGradient` pct×10 everywhere; helper names `onbStoreFromEle`/`onbWireMessages`/`onbMakeDc`/`onbApp` defined once and reused (single test binary); `OnboardView.WINDOW_M = 5000.0` is a rendering constant only — it does not affect what the watch parses or persists (the full route is always parsed and stored).
- **Known judgment calls for the implementer:** exact expected test totals may shift by a few if the simulator flags trivia; `OnboardPushService.pushRoute` treats any failed `sendMessageToOnboard` call as a hard stop (fire-and-forget send, so this only catches the "not connected" / SDK-exception case, not silent watch-side drops — acceptable for a user-triggered, retryable action); if `ConnectIqAppIdTest` reads manifests from disk, follow that pattern instead of the hardcoded assert; the terrain-window "Komende Xkm" fallback message (Task 8, when no climb is active or upcoming in the window) is a placeholder judgment call — swap for something more useful once real routes are tested in the simulator.



