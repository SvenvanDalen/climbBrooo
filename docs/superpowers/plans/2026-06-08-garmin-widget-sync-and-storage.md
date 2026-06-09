# Garmin Widget: Phone Sync + Watch Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ClimbPro widget fetches available routes from the Android companion app on open, lets the user browse and save routes/climbs to watch storage, and shows saved items offline when no phone is connected.

**Architecture:** Widget opens with `SyncView` which sends `LIST_ROUTES` to the phone; on response it switches to `RouteListView` showing two sections (phone routes + saved items). Selecting a phone route sends `LOAD_ROUTE`; the phone responds with the existing v3 payload. Watch storage uses `Application.Storage` keyed dictionaries. Android side adds `WatchRequestHandler` that reads `catalog.json` and route files to answer watch requests; wired into `ConnectIqClient` as stubs pending full CIQ SDK integration.

**Tech Stack:** Monkey C + Connect IQ SDK 9.1.0 (fr255m, minApiLevel 3.2.0), Java 11, JUnit 4 + Mockito, Gradle Groovy DSL.

---

> **Prerequisites:** Complete the GlanceView plan at `docs/superpowers/plans/2026-06-08-garmin-widget-glance-and-datafield.md` first (adds `ClimbGlanceView.mc` and `getGlanceView()` — not yet implemented).

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `garmin-widget/source/StorageManager.mc` | **Create** | Module: read/write/delete routes and climbs in Application.Storage |
| `garmin-widget/source/PhoneRouteIndex.mc` | **Create** | Class: holds the list of routes received from the phone |
| `garmin-widget/source/SyncView.mc` | **Create** | Loading screen shown at widget open; sends LIST_ROUTES, switches on response or timeout |
| `garmin-widget/source/CommListener.mc` | **Modify** | Add `type` dispatcher; handle ROUTE_LIST; save raw v3 payload ref to app |
| `garmin-widget/source/ClimbWidgetApp.mc` | **Modify** | Add `phoneRouteIndex`, `lastReceivedPayload`; expose `processMessage()`; change initial view to SyncView |
| `garmin-widget/source/RouteView.mc` | **Modify** | Rewrite classes as `RouteListView` + `RouteListDelegate` with phone + saved sections |
| `garmin-widget/source/ClimbListView.mc` | **Modify** | Constructor takes `(routeId, sourceType)`; add loading state for LOAD_ROUTE; add save/delete route action row |
| `garmin-widget/source/ClimbDetailView.mc` | **Modify** | Add `toggleSave()` and SELECT handler for save/delete individual climb |
| `android/.../connectiq/WatchRequestHandler.java` | **Create** | Handles LIST_ROUTES → ROUTE_LIST and LOAD_ROUTE → v3 payload |
| `android/.../connectiq/ConnectIqClient.java` | **Modify** | Add `sendMessage()` stub + `setWatchRequestHandler()` hook |
| `android/.../connectiq/WatchRequestHandlerTest.java` | **Create** | Unit tests for WatchRequestHandler |

**Build command (run from `garmin-widget/`):**
```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

---

## Task 1: StorageManager Module

**Files:**
- Create: `garmin-widget/source/StorageManager.mc`

**Background:** `Application.Storage` is a key/value store available on FR255 (API 2.3.0+). Module-level functions in Monkey C act as static utilities — no instance needed, call as `StorageManager.saveRoute(id, dict)`.

- [ ] **Step 1: Create StorageManager.mc**

```java
using Toybox.Application.Storage as Storage;

module StorageManager {

    function getSavedRouteIds() {
        var ids = Storage.getValue("saved_route_ids");
        return (ids instanceof Toybox.Lang.Array) ? ids : [];
    }

    function getSavedClimbKeys() {
        var keys = Storage.getValue("saved_climb_ids");
        return (keys instanceof Toybox.Lang.Array) ? keys : [];
    }

    function isRouteSaved(routeId) {
        var ids = getSavedRouteIds();
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i].equals(routeId)) { return true; }
        }
        return false;
    }

    function saveRoute(routeId, payloadDict) {
        var ids = getSavedRouteIds();
        if (!isRouteSaved(routeId)) { ids.add(routeId); }
        Storage.setValue("saved_route_ids", ids);
        Storage.setValue("route_" + routeId, payloadDict);
    }

    function deleteRoute(routeId) {
        var ids = getSavedRouteIds();
        var newIds = [];
        for (var i = 0; i < ids.size(); i++) {
            if (!ids[i].equals(routeId)) { newIds.add(ids[i]); }
        }
        Storage.setValue("saved_route_ids", newIds);
        Storage.deleteValue("route_" + routeId);
    }

    function loadRoute(routeId) {
        return Storage.getValue("route_" + routeId);
    }

    function isClimbSaved(routeId, climbIdx) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        for (var i = 0; i < keys.size(); i++) {
            if (keys[i].equals(key)) { return true; }
        }
        return false;
    }

    function saveClimb(routeId, climbIdx, climbDict) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        var found = false;
        for (var i = 0; i < keys.size(); i++) {
            if (keys[i].equals(key)) { found = true; break; }
        }
        if (!found) { keys.add(key); }
        Storage.setValue("saved_climb_ids", keys);
        Storage.setValue("climb_" + key, climbDict);
    }

    function deleteClimb(routeId, climbIdx) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        var newKeys = [];
        for (var i = 0; i < keys.size(); i++) {
            if (!keys[i].equals(key)) { newKeys.add(keys[i]); }
        }
        Storage.setValue("saved_climb_ids", newKeys);
        Storage.deleteValue("climb_" + key);
    }

    function loadClimb(routeId, climbIdx) {
        return Storage.getValue("climb_" + routeId + "_" + climbIdx);
    }
}
```

- [ ] **Step 2: Build to verify no errors**

```powershell
cd garmin-widget
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Expected: No errors, `ClimbBrowse.prg` produced.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/StorageManager.mc
git commit -m "feat(widget): add StorageManager for Application.Storage persistence"
```

---

## Task 2: PhoneRouteIndex Class

**Files:**
- Create: `garmin-widget/source/PhoneRouteIndex.mc`

**Background:** Holds the route list from the phone's `ROUTE_LIST` response. The app holds one instance; `received` flips to true when a valid response arrives. `CommListener` (Task 3) calls `populate()`.

- [ ] **Step 1: Create PhoneRouteIndex.mc**

```java
using Toybox.System as Sys;

class PhoneRouteIndex {

    var routes;
    var received;

    function initialize() {
        routes   = [];
        received = false;
    }

    function populate(routeList) {
        if (routeList instanceof Toybox.Lang.Array) {
            routes = routeList;
        } else {
            routes = [];
        }
        received = true;
        Sys.println("PhoneRouteIndex: " + routes.size() + " routes");
    }

    function getCount() { return routes.size(); }

    function getId(i) {
        var r = routes[i];
        return (r instanceof Toybox.Lang.Dictionary) ? r.get("id") : null;
    }

    function getName(i) {
        var r = routes[i];
        if (!(r instanceof Toybox.Lang.Dictionary)) { return "Route"; }
        var n = r.get("name");
        return (n instanceof Toybox.Lang.String) ? n : "Route";
    }

    function getClimbCount(i) {
        var r = routes[i];
        if (!(r instanceof Toybox.Lang.Dictionary)) { return 0; }
        var c = r.get("climbCount");
        return (c instanceof Toybox.Lang.Number) ? c.toNumber() : 0;
    }
}
```

- [ ] **Step 2: Build**

Same command as Task 1 Step 2. Expected: No errors.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/PhoneRouteIndex.mc
git commit -m "feat(widget): add PhoneRouteIndex to hold LIST_ROUTES response"
```

---

## Task 3: CommListener — Type Dispatcher

**Files:**
- Modify: `garmin-widget/source/CommListener.mc`

**Background:** The phone can now send two message shapes:
- `{"type": "ROUTE_LIST", "routes": [...]}` — no `v` field
- `{v: 3, mode: "route", ...}` — existing v3 payload, no `type` field

`onMessage()` must branch on the `type` field before checking `v`. Also save the raw v3 payload to `App.getApp().lastReceivedPayload` so ClimbListView can store it later.

- [ ] **Step 1: Replace the top of `onMessage()` in CommListener.mc**

Replace the current `onMessage` function (lines 25–59) with the full function below. The `parseClimb` and `getInt` helpers at the bottom of the file remain **unchanged**.

```java
    function onMessage(msg) {
        if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) {
            Sys.println("CommListener: null or invalid message");
            return;
        }

        // Dispatch on optional type field
        var msgType = msg.get("type");
        if (msgType instanceof Toybox.Lang.String) {
            if (msgType.equals("ROUTE_LIST")) {
                handleRouteList(msg);
            } else {
                Sys.println("CommListener: unknown type: " + msgType);
            }
            return;
        }

        // No type → v3 route payload
        var version = msg.get("v");
        if (version == null || version != 3) {
            Sys.println("CommListener: unsupported version " + version);
            return;
        }

        // Save raw payload so views can persist it to storage
        App.getApp().lastReceivedPayload = msg;

        var data = App.getApp().climbData;
        if (data == null) { return; }

        data.mode      = msg.get("mode");
        data.routeId   = msg.get("routeId");
        data.routeName = msg.get("name");

        var climbs = msg.get("climbs");
        if (climbs != null && climbs instanceof Toybox.Lang.Array) {
            var max = data.MAX_CLIMBS < climbs.size() ? data.MAX_CLIMBS : climbs.size();
            data.climbCount = max;
            for (var i = 0; i < max; i++) {
                parseClimb(data, i, climbs[i]);
            }
        } else {
            data.climbCount = 0;
        }

        data.payloadReceived = true;
        for (var i = 0; i < data.climbCount; i++) { data.calibIdx[i] = 0; }
        Sys.println("CommListener: v3 parsed, " + data.climbCount + " climbs");
    }

    hidden function handleRouteList(msg) {
        var index = App.getApp().phoneRouteIndex;
        if (index == null) { return; }
        index.populate(msg.get("routes"));
    }
```

- [ ] **Step 2: Build**

Expected: No errors. The existing `parseClimb` and `getInt` methods are unchanged below this.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/CommListener.mc
git commit -m "feat(widget): add ROUTE_LIST dispatcher to CommListener"
```

---

## Task 4: ClimbWidgetApp — Shared State + processMessage

**Files:**
- Modify: `garmin-widget/source/ClimbWidgetApp.mc`

**Background:** Three additions:
1. `phoneRouteIndex` — public var, created in `onStart()`
2. `lastReceivedPayload` — public var, populated by CommListener (Task 3)
3. `processMessage(msg)` — public wrapper so delegates can replay a saved payload through CommListener without accessing the `hidden` `msgCallback`

`getInitialView()` changes to SyncView in Task 9 (after SyncView and RouteListView exist).

- [ ] **Step 1: Replace ClimbWidgetApp.mc with the updated version**

```java
using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

class ClimbWidgetApp extends App.AppBase {

    var climbData;
    var phoneRouteIndex;
    var lastReceivedPayload;
    hidden var msgCallback;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        climbData        = new ClimbData();
        climbData.initialize();
        phoneRouteIndex  = new PhoneRouteIndex();
        msgCallback      = new PhoneMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        Sys.println("ClimbWidget: started");
    }

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    function processMessage(msg) {
        msgCallback.onMessage(msg);
        Ui.requestUpdate();
    }

    function onStop(state) {}

    function getInitialView() {
        return [new RouteView(), new RouteDelegate()];
    }
}
```

*(Note: `getInitialView` still returns `RouteView`/`RouteDelegate` — changed to `SyncView`/`SyncDelegate` in Task 9 after those classes exist.)*

- [ ] **Step 2: Build**

Expected: No errors.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/ClimbWidgetApp.mc
git commit -m "feat(widget): add phoneRouteIndex, lastReceivedPayload, processMessage to app"
```

---

## Task 5: SyncView

**Files:**
- Create: `garmin-widget/source/SyncView.mc`

**Background:** First screen shown when the widget opens. Sends `LIST_ROUTES` to the phone via `Comm.transmit()`, then waits. When `phoneRouteIndex.received` flips (CommListener populates it → app calls `Ui.requestUpdate()` → `onUpdate()` is called → transition happens). A 10-second `Timer` handles no-phone case.

`Ui.switchToView` (API 2.0.0+) replaces the current view without pushing to the back stack, so BACK in RouteListView exits the widget rather than returning to the loading screen.

- [ ] **Step 1: Create SyncView.mc**

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.Timer as Timer;

class SyncView extends Ui.View {

    hidden var timer;
    hidden var switched = false;

    function initialize() { View.initialize(); }

    function onShow() {
        App.getApp().phoneRouteIndex.received = false;
        Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
        timer = new Timer.Timer();
        timer.start(method(:onTimeout), 10000, false);
    }

    function onHide() {
        if (timer != null) { timer.stop(); timer = null; }
    }

    function onTimeout() {
        if (!switched) {
            switched = true;
            Ui.switchToView(new RouteListView(), new RouteListDelegate(), Ui.SLIDE_LEFT);
        }
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        if (!switched) {
            var phoneIndex = App.getApp().phoneRouteIndex;
            if (phoneIndex != null && phoneIndex.received) {
                switched = true;
                if (timer != null) { timer.stop(); timer = null; }
                Ui.switchToView(new RouteListView(), new RouteListDelegate(), Ui.SLIDE_LEFT);
                return;
            }
        }

        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
            "Connecting...",
            Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }
}

class SyncDelegate extends Ui.BehaviorDelegate {
    function initialize() { BehaviorDelegate.initialize(); }
    function onBack() { return true; }  // prevent back-out during sync
}
```

- [ ] **Step 2: Build**

Expected: No errors. (RouteListView/RouteListDelegate don't exist yet — if build fails with "Symbol not found: RouteListView", continue to Task 6 first then come back to verify.)

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/SyncView.mc
git commit -m "feat(widget): add SyncView with 10s timeout and LIST_ROUTES transmit"
```

---

## Task 6: RouteListView (rewrite RouteView.mc)

**Files:**
- Modify: `garmin-widget/source/RouteView.mc`

**Background:** Replaces the old `RouteView`/`RouteDelegate` (single-route summary) with a two-section scrollable list. Phone section reads from `PhoneRouteIndex`; saved section reads from `StorageManager`. Route names and climb counts for saved routes are cached in `refreshData()` to avoid Storage reads inside `onUpdate()`.

Selectable items: indices `0..phoneCount-1` = phone routes; `phoneCount..phoneCount+savedRouteCount-1` = saved routes; beyond that = saved climbs (key `"{routeId}_{idx}"`).

Saved climb keys look like `"routeAbc_2"`. To open a saved climb: parse the key, load the climb dict, wrap in a minimal v3 payload, call `App.getApp().processMessage()` to populate ClimbData, push ClimbDetailView.

- [ ] **Step 1: Replace RouteView.mc entirely**

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

class RouteListView extends Ui.View {

    var selectedIndex = 0;
    var phoneCount;
    var savedRouteIds;
    var savedClimbKeys;
    hidden var savedRouteNames;
    hidden var savedRouteClimbCounts;

    function initialize() {
        View.initialize();
        refreshData();
    }

    function refreshData() {
        var app        = App.getApp();
        var phoneIndex = app.phoneRouteIndex;
        phoneCount     = (phoneIndex != null && phoneIndex.received) ? phoneIndex.getCount() : 0;
        savedRouteIds  = StorageManager.getSavedRouteIds();
        savedClimbKeys = StorageManager.getSavedClimbKeys();

        savedRouteNames       = new [savedRouteIds.size()];
        savedRouteClimbCounts = new [savedRouteIds.size()];
        for (var i = 0; i < savedRouteIds.size(); i++) {
            var p = StorageManager.loadRoute(savedRouteIds[i]);
            if (p instanceof Toybox.Lang.Dictionary) {
                var n = p.get("name");
                savedRouteNames[i] = (n instanceof Toybox.Lang.String) ? n : savedRouteIds[i];
                var cls = p.get("climbs");
                savedRouteClimbCounts[i] = (cls instanceof Toybox.Lang.Array) ? cls.size() : 0;
            } else {
                savedRouteNames[i]       = savedRouteIds[i];
                savedRouteClimbCounts[i] = 0;
            }
        }
    }

    function getTotalCount() {
        return phoneCount + savedRouteIds.size() + savedClimbKeys.size();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w     = dc.getWidth();
        var h     = dc.getHeight();
        var total = getTotalCount();

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY,
            "Routes (" + (selectedIndex + 1) + "/" + total + ")",
            Gfx.TEXT_JUSTIFY_CENTER);

        if (total == 0) {
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
                "No routes",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var itemH        = 40;
        var listTop      = 24;
        var visibleCount = 3;

        var startIdx = selectedIndex - 1;
        if (startIdx < 0) { startIdx = 0; }
        if (startIdx + visibleCount > total) { startIdx = total - visibleCount; }
        if (startIdx < 0) { startIdx = 0; }

        var app        = App.getApp();
        var phoneIndex = app.phoneRouteIndex;
        var savedRouteCount = savedRouteIds.size();

        for (var i = startIdx; i < total && i < startIdx + visibleCount; i++) {
            var yPos = listTop + (i - startIdx) * itemH;

            // Draw divider between phone and saved sections
            if (i == phoneCount && phoneCount > 0 && (savedRouteCount + savedClimbKeys.size()) > 0) {
                dc.setColor(0x555555, Gfx.COLOR_TRANSPARENT);
                dc.drawLine(20, yPos - 2, w - 20, yPos - 2);
            }

            var name     = "";
            var subtitle = "";

            if (i < phoneCount) {
                name     = phoneIndex.getName(i);
                subtitle = phoneIndex.getClimbCount(i) + " climbs";
            } else {
                var savedIdx = i - phoneCount;
                if (savedIdx < savedRouteCount) {
                    name     = savedRouteNames[savedIdx];
                    subtitle = savedRouteClimbCounts[savedIdx] + " climbs  saved";
                } else {
                    var ck = savedClimbKeys[savedIdx - savedRouteCount];
                    name     = ck;
                    subtitle = "climb  saved";
                }
            }

            if (i == selectedIndex) {
                dc.setColor(0x003366, Gfx.COLOR_TRANSPARENT);
                dc.fillRectangle(8, yPos, w - 16, itemH - 4);
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            } else {
                dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
            }

            dc.drawText(w / 2, yPos + 4,  Gfx.FONT_XTINY, name,     Gfx.TEXT_JUSTIFY_CENTER);
            dc.setColor(i == selectedIndex ? 0xAAAAAA : Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, yPos + 22, Gfx.FONT_XTINY, subtitle, Gfx.TEXT_JUSTIFY_CENTER);
        }
    }
}

class RouteListDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onNextPage() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof RouteListView) {
            var total = view.getTotalCount();
            if (view.selectedIndex < total - 1) {
                view.selectedIndex++;
                Ui.requestUpdate();
            }
        }
        return true;
    }

    function onPreviousPage() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof RouteListView && view.selectedIndex > 0) {
            view.selectedIndex--;
            Ui.requestUpdate();
        }
        return true;
    }

    function onSelect() {
        var view = Ui.getCurrentView()[0];
        if (!(view instanceof RouteListView)) { return true; }

        var i               = view.selectedIndex;
        var phoneCount      = view.phoneCount;
        var savedRouteIds   = view.savedRouteIds;
        var savedClimbKeys  = view.savedClimbKeys;
        var savedRouteCount = savedRouteIds.size();

        if (i < phoneCount) {
            var routeId = App.getApp().phoneRouteIndex.getId(i);
            Ui.pushView(new ClimbListView(routeId, "phone"),
                        new ClimbListDelegate(), Ui.SLIDE_LEFT);
        } else {
            var savedIdx = i - phoneCount;
            if (savedIdx < savedRouteCount) {
                var routeId = savedRouteIds[savedIdx];
                Ui.pushView(new ClimbListView(routeId, "saved_route"),
                            new ClimbListDelegate(), Ui.SLIDE_LEFT);
            } else {
                var climbKey = savedClimbKeys[savedIdx - savedRouteCount];
                openSavedClimb(climbKey);
            }
        }
        return true;
    }

    hidden function openSavedClimb(climbKey) {
        var parts = splitOnLastUnderscore(climbKey);
        if (parts == null) { return; }
        var routeId  = parts[0];
        var climbIdx = parts[1].toNumber();
        var climbDict = StorageManager.loadClimb(routeId, climbIdx);
        if (climbDict == null) { return; }
        var fakePayload = {
            "v"       => 3,
            "mode"    => "route",
            "routeId" => routeId,
            "name"    => "Saved Climb",
            "climbs"  => [climbDict]
        };
        App.getApp().processMessage(fakePayload);
        Ui.pushView(new ClimbDetailView(0), new ClimbDetailDelegate(), Ui.SLIDE_LEFT);
    }

    hidden function splitOnLastUnderscore(s) {
        var last = -1;
        for (var i = 0; i < s.length(); i++) {
            if (s.substring(i, i + 1).equals("_")) { last = i; }
        }
        if (last < 0) { return null; }
        return [s.substring(0, last), s.substring(last + 1, s.length())];
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
```

- [ ] **Step 2: Build**

Expected: No errors.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/RouteView.mc
git commit -m "feat(widget): rewrite RouteView as RouteListView with phone + saved sections"
```

---

## Task 7: ClimbListView — LOAD_ROUTE + Save/Delete Route

**Files:**
- Modify: `garmin-widget/source/ClimbListView.mc`

**Background:** The view now takes `(routeId, sourceType)` as constructor params.
- `"phone"`: resets `climbData.payloadReceived`, sends `LOAD_ROUTE`, shows "Loading..." until a new payload arrives (CommListener sets `payloadReceived = true` → `Ui.requestUpdate()` → `onUpdate()` sees it).
- `"saved_route"`: calls `processMessage(StorageManager.loadRoute(routeId))` immediately.

The last item in the list (index `data.climbCount`) is always a "Save route" / "Delete route" action row. SELECT on it toggles storage. SELECT on a climb pushes `ClimbDetailView`.

- [ ] **Step 1: Replace ClimbListView.mc entirely**

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Communications as Comm;

class ClimbListView extends Ui.View {

    var selectedIndex = 0;
    hidden var routeId;
    hidden var sourceType;
    hidden var loading;
    hidden var loadError;

    function initialize(aRouteId, aSourceType) {
        View.initialize();
        routeId    = aRouteId;
        sourceType = aSourceType;
        loading    = false;
        loadError  = false;

        if (aSourceType.equals("phone")) {
            loading = true;
            App.getApp().climbData.payloadReceived = false;
            Comm.transmit({ "type" => "LOAD_ROUTE", "id" => aRouteId }, null, new CommListener());
        } else if (aSourceType.equals("saved_route")) {
            var payload = StorageManager.loadRoute(aRouteId);
            if (payload != null) {
                App.getApp().processMessage(payload);
            } else {
                loadError = true;
            }
        }
    }

    function getRouteId() { return routeId; }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();

        if (loading) {
            if (App.getApp().climbData.payloadReceived) {
                loading = false;
            } else {
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
                    "Loading...",
                    Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
                return;
            }
        }

        if (loadError) {
            dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
                "Load failed",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var data       = App.getApp().climbData;
        var totalItems = data.climbCount + 1;  // last item = save/delete action

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 6, Gfx.FONT_XTINY,
            "Climbs (" + (selectedIndex + 1) + "/" + totalItems + ")",
            Gfx.TEXT_JUSTIFY_CENTER);

        var startIdx = selectedIndex - 1;
        if (startIdx < 0) { startIdx = 0; }
        if (startIdx + 4 > totalItems) { startIdx = totalItems - 4; }
        if (startIdx < 0) { startIdx = 0; }

        var itemH   = 36;
        var listTop = 30;

        for (var i = startIdx; i < totalItems && i < startIdx + 4; i++) {
            var yPos = listTop + (i - startIdx) * itemH;

            if (i == selectedIndex) {
                dc.setColor(Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);
                dc.fillRectangle(10, yPos - 2, w - 20, itemH - 4);
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            } else {
                dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
            }

            if (i < data.climbCount) {
                var name = data.climbName[i];
                if (name == null) { name = "Climb " + (i + 1); }
                dc.drawText(w / 2, yPos + 4, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

                var grad     = data.climbAvgGrad[i];
                var gradFrac = grad % 10;
                if (gradFrac < 0) { gradFrac = -gradFrac; }
                var gradStr  = (grad / 10) + "." + gradFrac + "%  " + formatDist(data.climbLength[i]);
                dc.drawText(w / 2, yPos + 20, Gfx.FONT_XTINY, gradStr, Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                var isSaved = StorageManager.isRouteSaved(routeId);
                var label   = isSaved ? "Delete route" : "Save route";
                var color   = isSaved ? Gfx.COLOR_RED : Gfx.COLOR_GREEN;
                dc.setColor(i == selectedIndex ? Gfx.COLOR_WHITE : color, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, yPos + 14, Gfx.FONT_XTINY, label, Gfx.TEXT_JUSTIFY_CENTER);
            }
        }
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }
}

class ClimbListDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onNextPage() {
        var view = Ui.getCurrentView()[0];
        var data = App.getApp().climbData;
        if (view instanceof ClimbListView) {
            var total = data.climbCount + 1;
            if (view.selectedIndex < total - 1) {
                view.selectedIndex++;
                Ui.requestUpdate();
            }
        }
        return true;
    }

    function onPreviousPage() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof ClimbListView && view.selectedIndex > 0) {
            view.selectedIndex--;
            Ui.requestUpdate();
        }
        return true;
    }

    function onSelect() {
        var view = Ui.getCurrentView()[0];
        if (!(view instanceof ClimbListView)) { return true; }

        var data = App.getApp().climbData;

        if (view.selectedIndex == data.climbCount) {
            var routeId = view.getRouteId();
            if (StorageManager.isRouteSaved(routeId)) {
                StorageManager.deleteRoute(routeId);
            } else {
                var payload = App.getApp().lastReceivedPayload;
                if (payload != null) {
                    StorageManager.saveRoute(routeId, payload);
                }
            }
            Ui.requestUpdate();
        } else {
            Ui.pushView(
                new ClimbDetailView(view.selectedIndex),
                new ClimbDetailDelegate(),
                Ui.SLIDE_LEFT
            );
        }
        return true;
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
```

- [ ] **Step 2: Build**

Expected: No errors. Note: the old `initialize()` with no args is replaced — any remaining call sites should have been removed in Task 6.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/ClimbListView.mc
git commit -m "feat(widget): ClimbListView supports LOAD_ROUTE, save/delete route action row"
```

---

## Task 8: ClimbDetailView — Save/Delete Climb

**Files:**
- Modify: `garmin-widget/source/ClimbDetailView.mc`

**Background:** SELECT (handled by `ClimbDetailDelegate.onSelect()`) toggles whether the displayed climb is saved individually. The climb dict comes from `lastReceivedPayload.get("climbs")[climbIndex]` — this is the same raw dict that was originally parsed. Drawn label `"SELECT: Save"` / `"SELECT: Remove"` at the very bottom of the screen (below existing stats at `h-22`).

- [ ] **Step 1: Add toggleSave() method and save label to ClimbDetailView**

Add inside `class ClimbDetailView`, after the `formatDist` method:

```java
    function toggleSave() {
        var data = App.getApp().climbData;
        var rId  = data.routeId;
        if (rId == null) { return; }
        if (StorageManager.isClimbSaved(rId, climbIndex)) {
            StorageManager.deleteClimb(rId, climbIndex);
        } else {
            var payload = App.getApp().lastReceivedPayload;
            if (payload != null) {
                var climbs = payload.get("climbs");
                if (climbs instanceof Toybox.Lang.Array && climbIndex < climbs.size()) {
                    StorageManager.saveClimb(rId, climbIndex, climbs[climbIndex]);
                }
            }
        }
    }
```

At the end of `onUpdate()`, after drawing the stats line (the three drawText calls at `statsY`), add:

```java
        var rId = data.routeId;
        if (rId != null) {
            var saved = StorageManager.isClimbSaved(rId, ci);
            dc.setColor(saved ? Gfx.COLOR_RED : Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h - 8, Gfx.FONT_XTINY,
                saved ? "SELECT: Remove" : "SELECT: Save",
                Gfx.TEXT_JUSTIFY_CENTER);
        }
```

- [ ] **Step 2: Add onSelect() to ClimbDetailDelegate**

Replace the existing `ClimbDetailDelegate` class:

```java
class ClimbDetailDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onSelect() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof ClimbDetailView) {
            view.toggleSave();
            Ui.requestUpdate();
        }
        return true;
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
```

- [ ] **Step 3: Build**

Expected: No errors.

- [ ] **Step 4: Commit**

```
git add garmin-widget/source/ClimbDetailView.mc
git commit -m "feat(widget): ClimbDetailView toggleSave for per-climb storage"
```

---

## Task 9: ClimbWidgetApp — Set SyncView as Initial View

**Files:**
- Modify: `garmin-widget/source/ClimbWidgetApp.mc`

- [ ] **Step 1: Change getInitialView() in ClimbWidgetApp.mc**

Replace:
```java
    function getInitialView() {
        return [new RouteView(), new RouteDelegate()];
    }
```

With:
```java
    function getInitialView() {
        return [new SyncView(), new SyncDelegate()];
    }
```

- [ ] **Step 2: Build**

Expected: No errors, `ClimbBrowse.prg` updated.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/ClimbWidgetApp.mc
git commit -m "feat(widget): open with SyncView instead of RouteView"
```

---

## Task 10: Android WatchRequestHandler (TDD)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java` (add `sendMessage` stub)
- Create: `android/app/src/test/java/nl/paree/climbpro/connectiq/WatchRequestHandlerTest.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java`

**Background:** `WatchRequestHandler.handleMessage()` is the entry point. `LIST_ROUTES` reads `catalog.json` via `RouteRepository.loadCatalog()` and sends a `ROUTE_LIST` message. `LOAD_ROUTE` loads the full route and sends a v3 payload via `ClimbPayloadBuilder`. Both responses go through `ConnectIqClient.sendMessage()` (new stub) or the existing `sendPayload()`. The CIQ SDK registration that calls `handleMessage()` is left as a comment — it will be wired when the SDK is integrated.

- [ ] **Step 1: Add sendMessage stub to ConnectIqClient.java**

Add these imports at the top of `ConnectIqClient.java`:
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
```

Add the following two methods to the `ConnectIqClient` class (after `sendPayload`):

```java
    /**
     * Serialize a response map to JSON bytes and send to the watch.
     * Stub until Garmin CIQ SDK is linked.
     */
    public boolean sendMessage(Map<String, Object> message) {
        try {
            byte[] bytes = new ObjectMapper().writeValueAsBytes(message);
            return sendPayload(bytes);
        } catch (Exception e) {
            Log.e(TAG, "sendMessage serialization failed", e);
            return false;
        }
    }

    /**
     * Register a handler for messages received FROM the watch.
     * Replace the Log.w body with the actual CIQ SDK registration when the SDK is linked:
     *
     *   connectIQ.registerForAppMessages(device, app, (iqDevice, iqApp, msg) -> {
     *       if (requestHandler != null) requestHandler.handleMessage((Map<?,?>) msg);
     *   });
     */
    public void setWatchRequestHandler(WatchRequestHandler requestHandler) {
        Log.w(TAG, "setWatchRequestHandler: CIQ SDK not linked — incoming watch messages will be ignored.");
    }
```

- [ ] **Step 2: Write WatchRequestHandlerTest.java**

```java
package nl.paree.climbpro.connectiq;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class WatchRequestHandlerTest {

    @Mock RouteRepository  mockRepo;
    @Mock ConnectIqClient  mockClient;

    // ---- LIST_ROUTES --------------------------------------------------------

    @Test
    public void listRoutes_emptyRepo_sendsRouteListWithEmptyArray() {
        when(mockRepo.loadCatalog()).thenReturn(Collections.emptyList());
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        handler.handleMessage(msg("type", "LIST_ROUTES"));

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ROUTE_LIST", captor.getValue().get("type"));
        assertTrue(((List<?>) captor.getValue().get("routes")).isEmpty());
    }

    @Test
    public void listRoutes_withEntries_includesIdNameClimbCount() {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId    = "abc123";
        e.name       = "Mont Ventoux";
        e.climbCount = 2;
        when(mockRepo.loadCatalog()).thenReturn(Collections.singletonList(e));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        handler.handleMessage(msg("type", "LIST_ROUTES"));

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        List<Map<?, ?>> routes = (List<Map<?, ?>>) captor.getValue().get("routes");
        assertEquals(1,          routes.size());
        assertEquals("abc123",   routes.get(0).get("id"));
        assertEquals("Mont Ventoux", routes.get(0).get("name"));
        assertEquals(2,          routes.get(0).get("climbCount"));
    }

    @Test
    public void listRoutes_prefersUserDisplayName_overName() {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId         = "r1";
        e.name            = "original";
        e.userDisplayName = "renamed";
        e.climbCount      = 0;
        when(mockRepo.loadCatalog()).thenReturn(Collections.singletonList(e));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        handler.handleMessage(msg("type", "LIST_ROUTES"));

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        List<Map<?, ?>> routes = (List<Map<?, ?>>) captor.getValue().get("routes");
        assertEquals("renamed", routes.get(0).get("name"));
    }

    // ---- LOAD_ROUTE ---------------------------------------------------------

    @Test
    public void loadRoute_existingRoute_sendsV3Payload() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "LOAD_ROUTE");
        request.put("id",   "r1");
        handler.handleMessage(request);

        verify(mockClient).sendPayload(any(byte[].class));
    }

    @Test
    public void loadRoute_missingId_doesNotSend() {
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        handler.handleMessage(msg("type", "LOAD_ROUTE"));  // no "id" key

        verifyNoInteractions(mockClient);
    }

    @Test
    public void loadRoute_repoThrows_doesNotSend() throws IOException {
        when(mockRepo.loadRoute(any())).thenThrow(new IOException("not found"));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "LOAD_ROUTE");
        request.put("id",   "missing");
        handler.handleMessage(request);

        verify(mockClient, never()).sendPayload(any());
    }

    // ---- Unknown type -------------------------------------------------------

    @Test
    public void unknownType_doesNotSend() {
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "BOGUS"));
        verifyNoInteractions(mockClient);
    }

    // ---- Helper -------------------------------------------------------------

    private static Map<String, Object> msg(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
```

- [ ] **Step 3: Run test — expect compile failure (WatchRequestHandler missing)**

```powershell
cd android
.\gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest
```

Expected: Compilation error `cannot find symbol: class WatchRequestHandler`.

- [ ] **Step 4: Create WatchRequestHandler.java**

```java
package nl.paree.climbpro.connectiq;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.ClimbPayloadBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatchRequestHandler {

    private static final String TAG = "WatchRequestHandler";

    private final RouteRepository routeRepo;
    private final ConnectIqClient connectIqClient;
    private final ObjectMapper    mapper;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient) {
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
    }

    public void handleMessage(Map<String, Object> message) {
        if (message == null) return;
        String type = (String) message.get("type");
        if ("LIST_ROUTES".equals(type)) {
            handleListRoutes();
        } else if ("LOAD_ROUTE".equals(type)) {
            handleLoadRoute((String) message.get("id"));
        } else {
            Log.w(TAG, "Unknown message type from watch: " + type);
        }
    }

    private void handleListRoutes() {
        List<RouteCatalogEntry>      catalog = routeRepo.loadCatalog();
        List<Map<String, Object>>    routes  = new ArrayList<>(catalog.size());
        for (RouteCatalogEntry e : catalog) {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("id",         e.routeId);
            route.put("name",       e.userDisplayName != null ? e.userDisplayName : e.name);
            route.put("climbCount", e.climbCount);
            routes.add(route);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type",   "ROUTE_LIST");
        response.put("routes", routes);
        connectIqClient.sendMessage(response);
        Log.i(TAG, "Sent ROUTE_LIST with " + routes.size() + " routes");
    }

    private void handleLoadRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            Log.w(TAG, "LOAD_ROUTE: missing routeId");
            return;
        }
        try {
            StoredRoute route   = routeRepo.loadRoute(routeId);
            byte[]      payload = new ClimbPayloadBuilder(mapper).buildRoutePayload(route);
            connectIqClient.sendPayload(payload);
            Log.i(TAG, "Sent route payload for " + routeId + " (" + payload.length + " bytes)");
        } catch (IOException e) {
            Log.e(TAG, "LOAD_ROUTE failed for " + routeId, e);
        }
    }
}
```

- [ ] **Step 5: Run test — expect all pass**

```powershell
.\gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 6: Commit**

```
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java
git add android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java
git add android/app/src/test/java/nl/paree/climbpro/connectiq/WatchRequestHandlerTest.java
git commit -m "feat(android): add WatchRequestHandler for LIST_ROUTES and LOAD_ROUTE"
```

---

## Task 11: Android — Wire WatchRequestHandler in ClimbProApplication

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`

**Background:** `WatchRequestHandler` needs to be reachable from wherever the CIQ SDK delivers incoming watch messages. The cleanest place is `ClimbProApplication` (already the application class). Register it via `ConnectIqClient.setWatchRequestHandler()` — the method is currently a stub-with-comment; when the SDK is integrated, that method body becomes the real SDK registration call. No behaviour changes at runtime until the SDK is linked.

- [ ] **Step 1: Read ClimbProApplication.java**

```
Read: android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
```

- [ ] **Step 2: Add WatchRequestHandler wiring**

In `ClimbProApplication.onCreate()` (or wherever `ConnectIqClient` is first instantiated), add:

```java
ConnectIqClient ciqClient = new ConnectIqClient(this);
RouteRepository routeRepo = new RouteRepository(this);
ciqClient.setWatchRequestHandler(new WatchRequestHandler(routeRepo, ciqClient));
```

*(If `ConnectIqClient` is already constructed elsewhere as a singleton/field, add the `setWatchRequestHandler` call right after that construction rather than creating a second instance.)*

- [ ] **Step 3: Build Android**

```powershell
.\gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```
git add android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "feat(android): wire WatchRequestHandler into ConnectIqClient"
```

---

## Task 12: Full Build + Sideload Verification

**Manual steps — requires FR255 with USB cable.**

- [ ] **Step 1: Final build of widget**

```powershell
cd garmin-widget
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Expected: No errors, `ClimbBrowse.prg` produced.

- [ ] **Step 2: Enable Developer Mode on the watch**

On FR255: `Settings` → `System` → `Developer Mode` → toggle ON.

- [ ] **Step 3: Copy .prg to watch**

```powershell
# Replace E: with actual drive letter of the Forerunner 255
Copy-Item "garmin-widget\ClimbBrowse.prg" "E:\GARMIN\Apps\ClimbBrowse.prg"
```

Eject safely.

- [ ] **Step 4: Verify widget behaviour (no phone connected)**

- Open widget: should show "Connecting..." for 10 seconds, then switch to RouteListView
- RouteListView with no phone routes and no saved items: should show "No routes"
- Widget glance (swipe through widgets): should show "ClimbPro" from the GlanceView (from the GlanceView plan)

- [ ] **Step 5: Verify widget behaviour (phone connected, companion app running)**

*Note: this requires the Android companion app to handle LIST_ROUTES and LOAD_ROUTE, which is only fully functional once the Garmin CIQ SDK AAR is linked (currently stubs). In the interim, mock the phone by having the phone send a hardcoded ROUTE_LIST message to the widget app ID `fedcba9876543210fedcba9876543210`.*

Expected flow:
- Widget opens → "Connecting..."
- ROUTE_LIST arrives → switches to RouteListView showing phone routes
- SELECT phone route → sends LOAD_ROUTE → "Loading..." → climb list appears
- Scroll to last list item → "Save route" in green
- SELECT it → item label changes to "Delete route" in red
- BACK → RouteListView now shows the saved route in "On watch" section
- Reopen widget without phone → same saved route visible offline

---

## Spec Coverage Check

| Spec requirement | Task |
|---|---|
| SyncView — shows "Connecting..." on open | Task 5 |
| SyncView — sends LIST_ROUTES | Task 5 |
| SyncView → RouteListView on ROUTE_LIST received | Tasks 3, 5 |
| SyncView → RouteListView on 10s timeout | Task 5 |
| RouteListView — Phone section from PhoneRouteIndex | Task 6 |
| RouteListView — On watch section from StorageManager | Tasks 1, 6 |
| RouteListView — visual divider between sections | Task 6 |
| RouteListView — UP/DOWN/SELECT/BACK navigation | Task 6 |
| Phone route → ClimbListView → LOAD_ROUTE | Task 7 |
| Saved route → loads from Application.Storage | Tasks 1, 7 |
| Saved climb → ClimbDetailView directly | Task 6 |
| "Save route" / "Delete route" in ClimbListView | Task 7 |
| "SELECT: Save" / "SELECT: Remove" in ClimbDetailView | Task 8 |
| Storage keys: saved_route_ids, route_{id}, etc. | Task 1 |
| ROUTE_LIST dispatch in CommListener | Task 3 |
| Raw payload saved for storage | Tasks 3, 4 |
| Android: LIST_ROUTES → catalog → ROUTE_LIST | Task 10 |
| Android: LOAD_ROUTE → route file → v3 payload | Task 10 |
| Android: prefers userDisplayName | Task 10 |
| Android: sendMessage stub | Tasks 10, 11 |
| Initial view = SyncView | Task 9 |
