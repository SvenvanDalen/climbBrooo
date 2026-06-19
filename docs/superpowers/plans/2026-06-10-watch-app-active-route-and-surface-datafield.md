# Watch App + Active Route Selection + Surface-Sections Datafield Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the ClimbPro browse widget into a real Connect IQ device app (with glance) on the FR255M, let the user pick a saved route/climb on the watch as the *active* one for the existing ClimbPro datafield (via a phone relay), and add a brand-new datafield that shows the user-defined surface sections ("ondergrond-stukken") during a ride.

**Architecture:** Three Connect IQ apps + the Android companion. (1) `garmin-widget/` changes `type=widget → watch-app` and gains a glance; its route list already loads climb data lazily on select. (2) Because Connect IQ apps have isolated storage, "set active route/climb" is relayed through the phone: the watch app sends `SET_ACTIVE_ROUTE`/`SET_ACTIVE_CLIMB`, the Android `WatchRequestHandler` builds the payload and pushes it to the datafield app IDs, then acks the watch app with `ACTIVE_SET`. The existing datafield (`garmin/`) gets one minimal change: persist the last received payload to `Application.Storage` and restore it at start, so a route chosen before the ride survives into the activity with no phone present. (3) A new datafield module `garmin-surface/` receives a lean payload with packed `surfSec` triples (user-defined surface sections, phone-side `StoredRoute.surfaceSections`) and renders "current surface + remaining distance + next section". The wire change is made schema-first in `protocol/schema.json` per CLAUDE.md.

**Tech Stack:** Monkey C (Connect IQ SDK 9.1.0, device `fr255m`, developer key `C:\Users\svenv\Documents\CLIMBPRODEF\developer_key`), Java/Android (Gradle Groovy, JUnit 4 + Mockito), JSON Schema in `protocol/`.

**User decisions locked in (2026-06-10):**
- Convert the existing widget to a device app (same app ID `fedcba...`); the widget type disappears.
- Active-route selection goes through the phone relay; the ride itself must work offline (datafield persists the payload).
- The new datafield shows **current section + next section** (compact text), not a route bar.
- The new datafield shows **only the user-defined surface sections** (`StoredRoute.surfaceSections`), *not* the auto-detected flat segments.

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `garmin-widget/manifest.xml` | Modify | `type="widget"` → `type="watch-app"` |
| `garmin-widget/source/ClimbWidgetApp.mc` | Modify | Lazy init (glance-safe `onStart`), `getGlanceView`, `activeAck` field |
| `garmin-widget/source/ClimbGlanceView.mc` | Create | Compact glance for the up/down loop |
| `garmin-widget/source/StorageManager.mc` | Modify | `(:glance)` annotation + lightweight saved-route meta index |
| `garmin-widget/source/RouteView.mc` | Modify | Read names/climb counts from the meta index (lazy) |
| `garmin-widget/source/ClimbListView.mc` | Modify | Extra action row "Zet actief" → `SET_ACTIVE_ROUTE` |
| `garmin-widget/source/ClimbDetailView.mc` | Modify | Original-climb-index plumbing + MENU → `SET_ACTIVE_CLIMB` |
| `garmin-widget/source/CommListener.mc` | Modify | Handle `ACTIVE_SET` ack |
| `garmin-widget/source/ActiveSetView.mc` | Create | "Versturen… / Actief gezet / Mislukt" feedback view |
| `garmin/source/ClimbProApp.mc` | Modify | Restore persisted payload at start (display code untouched) |
| `garmin/source/CommListener.mc` | Modify | Persist parsed payload to Storage |
| `garmin-surface/manifest.xml` | Create | New datafield app (`id 00112233...`) |
| `garmin-surface/monkey.jungle` | Create | Build config |
| `garmin-surface/resources/strings/strings.xml` | Create | App name |
| `garmin-surface/resources/drawables/drawables.xml` + `launcher_icon.png` | Create | Copied from `garmin/` |
| `garmin-surface/source/SurfaceData.mc` | Create | Parallel-array store + progress logic |
| `garmin-surface/source/SurfaceFieldApp.mc` | Create | App entry, phone messages, Storage persistence |
| `garmin-surface/source/SurfaceFieldView.mc` | Create | Current/next section rendering |
| `protocol/schema.json` | Modify | Add `surfaceSections` (logical) — schema-first per CLAUDE.md |
| `protocol/schema.md` | Modify | Document `surfSec` wire key |
| `protocol/examples/route_mode_full.json` | Modify | Add example sections |
| `android/.../connectiq/ConnectIqAppId.java` | Modify | Add `DATAFIELD` + `SURFACE_FIELD` IDs |
| `android/.../connectiq/ConnectIqClient.java` | Modify | Multi-app send targets |
| `android/.../connectiq/WatchRequestHandler.java` | Modify | `SET_ACTIVE_ROUTE` / `SET_ACTIVE_CLIMB` + ack |
| `android/.../service/ClimbPayloadBuilder.java` | Modify | `buildSingleClimbPayload`, `buildSurfaceSectionPayload` |
| `android/.../connectiq/WatchRequestHandlerTest.java` | Modify (test) | New handler tests |
| `android/.../service/ClimbPayloadBuilderActivePayloadTest.java` | Create (test) | New builder tests |
| `Documentation/ARCHITECTURE.md`, `README.md` | Modify | Document new flows + wire key |

**Commands used throughout (PowerShell, run from the repo root unless stated):**

- Connect IQ build (run **from inside the module dir** — `garmin-widget/`, `garmin/`, or `garmin-surface/`; adjust `-o` name per module):

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

- Android tests: `cd android` then `.\gradlew.bat test --tests <FQCN>` (bash: `./gradlew test --tests <FQCN>`).
- There is no Monkey C unit-test infrastructure in this repo; watch-side verification = clean compile + the manual checks in Task 12.

---

## Task 1: Convert the widget to a device app with glance-safe startup

**Files:**
- Modify: `garmin-widget/manifest.xml`
- Modify: `garmin-widget/source/ClimbWidgetApp.mc`

**Background:** On the FR255, pressing UP/DOWN scrolls the glance loop. A `watch-app` with a glance appears there *and* stays open when launched (widgets auto-time-out). Glances run in a tiny memory budget and execute `AppBase.onStart`, so all heavy init (`ClimbData` allocates ~4KB of arrays) must move out of `onStart` into `getInitialView`, which only runs for the full app. The app class must carry the `(:glance)` annotation to be loadable in glance scope.

- [ ] **Step 1: Change the app type in the manifest**

In `garmin-widget/manifest.xml` line 5, change:

```xml
        type="widget"
```

to:

```xml
        type="watch-app"
```

- [ ] **Step 2: Rewrite ClimbWidgetApp.mc**

Replace the entire content of `garmin-widget/source/ClimbWidgetApp.mc` with:

```java
using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

(:glance)
class ClimbWidgetApp extends App.AppBase {

    var climbData;
    var phoneRouteIndex;
    var lastReceivedPayload;
    var activeAck;            // last ACTIVE_SET ack Dictionary from the phone, or null
    hidden var msgCallback;

    function initialize() {
        AppBase.initialize();
    }

    // Keep onStart minimal: it also runs in the glance scope, where the memory
    // budget is tiny. Full initialisation happens in getInitialView, which only
    // runs when the full app is launched.
    function onStart(state) {}

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null && msgCallback != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    function processMessage(msg) {
        if (msgCallback != null) {
            msgCallback.onMessage(msg);
            Ui.requestUpdate();
        }
    }

    function onStop(state) {}

    function getGlanceView() {
        return [ new ClimbGlanceView() ];
    }

    function getInitialView() {
        climbData        = new ClimbData();
        climbData.initialize();
        phoneRouteIndex  = new PhoneRouteIndex();
        msgCallback      = new PhoneMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        Sys.println("ClimbApp: started");
        return [new SyncView(), new SyncDelegate()];
    }
}
```

- [ ] **Step 3: Do not build yet**

The build will fail until `ClimbGlanceView` exists (Task 2) — that's expected. Continue.

---

## Task 2: ClimbGlanceView + glance-scope StorageManager

**Files:**
- Create: `garmin-widget/source/ClimbGlanceView.mc`
- Modify: `garmin-widget/source/StorageManager.mc:3`

**Background:** The glance shows the app name plus how many routes/climbs are saved on the watch — data it reads from `Application.Storage`, so `StorageManager` must also carry `(:glance)`. Glance area on FR255 is full width, ~60-70 px tall, left-aligned by convention.

- [ ] **Step 1: Annotate StorageManager for glance scope**

In `garmin-widget/source/StorageManager.mc` line 3, change:

```java
module StorageManager {
```

to:

```java
(:glance)
module StorageManager {
```

- [ ] **Step 2: Create ClimbGlanceView.mc**

Create `garmin-widget/source/ClimbGlanceView.mc`:

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.System as Sys;

(:glance)
class ClimbGlanceView extends Ui.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(0, h / 4, Gfx.FONT_XTINY, "ClimbPro", Gfx.TEXT_JUSTIFY_LEFT);

        // Connection dot: green = phone reachable, red = no connection
        var connected = Sys.getDeviceSettings().phoneConnected;
        dc.setColor(connected ? 0x00AA00 : Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
        dc.fillCircle(w - 8, h / 4, 5);

        var routeCount = StorageManager.getSavedRouteIds().size();
        var climbCount = StorageManager.getSavedClimbKeys().size();
        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(0, (h * 3) / 4, Gfx.FONT_XTINY,
            routeCount + " routes, " + climbCount + " klimmen",
            Gfx.TEXT_JUSTIFY_LEFT);
    }
}
```

- [ ] **Step 3: Build the watch app**

Run from `garmin-widget/`:

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Expected: no errors. If you see `Symbol not found` for a glance-scope symbol, a class used by the glance is missing its `(:glance)` annotation.

- [ ] **Step 4: Commit**

```bash
git add garmin-widget/manifest.xml garmin-widget/source/ClimbWidgetApp.mc garmin-widget/source/ClimbGlanceView.mc garmin-widget/source/StorageManager.mc
git commit -m "feat(watch-app): convert widget to device app with glance"
```

---

## Task 3: Lazy saved-route names via a meta index

**Files:**
- Modify: `garmin-widget/source/StorageManager.mc`
- Modify: `garmin-widget/source/RouteView.mc:26-47`

**Background:** `RouteListView.refreshData` currently loads the **full payload** of every saved route just to show its name and climb count. To make the list truly lazy (climb data only loads on click), `saveRoute` also writes a tiny meta entry `{name, climbCount}` into one `saved_route_meta` dictionary, and the list reads that. Routes saved before this change have no meta entry → fall back to the old full load once and backfill.

- [ ] **Step 1: Add meta read/write to StorageManager**

In `garmin-widget/source/StorageManager.mc`, add directly after `getSavedClimbKeys` (after line 13 of the original numbering — i.e. after the function's closing brace):

```java
    function getSavedRouteMeta() {
        var meta = Storage.getValue("saved_route_meta");
        return (meta instanceof Toybox.Lang.Dictionary) ? meta : {};
    }
```

Replace the existing `saveRoute` function with:

```java
    function saveRoute(routeId, payloadDict) {
        var ids = getSavedRouteIds();
        var found = false;
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i].equals(routeId)) { found = true; break; }
        }
        if (!found) { ids.add(routeId); }
        Storage.setValue("saved_route_ids", ids);
        Storage.setValue("route_" + routeId, payloadDict);

        // Lightweight meta so the route list never loads full payloads.
        var name = routeId;
        var climbCount = 0;
        if (payloadDict instanceof Toybox.Lang.Dictionary) {
            var n = payloadDict.get("name");
            if (n instanceof Toybox.Lang.String) { name = n; }
            var cls = payloadDict.get("climbs");
            if (cls instanceof Toybox.Lang.Array) { climbCount = cls.size(); }
        }
        var meta = getSavedRouteMeta();
        meta.put(routeId, { "name" => name, "climbCount" => climbCount });
        Storage.setValue("saved_route_meta", meta);
    }
```

Replace the existing `deleteRoute` function with:

```java
    function deleteRoute(routeId) {
        var ids = getSavedRouteIds();
        var newIds = [];
        for (var i = 0; i < ids.size(); i++) {
            if (!ids[i].equals(routeId)) { newIds.add(ids[i]); }
        }
        Storage.setValue("saved_route_ids", newIds);
        Storage.deleteValue("route_" + routeId);

        var meta = getSavedRouteMeta();
        meta.remove(routeId);
        Storage.setValue("saved_route_meta", meta);
    }
```

- [ ] **Step 2: Use the meta index in RouteListView.refreshData**

In `garmin-widget/source/RouteView.mc`, replace the body of `refreshData` (lines 26-47) with:

```java
    function refreshData() {
        var app        = App.getApp();
        var phoneIndex = app.phoneRouteIndex;
        phoneCount     = (phoneIndex != null && phoneIndex.received) ? phoneIndex.getCount() : 0;
        savedRouteIds  = StorageManager.getSavedRouteIds();
        savedClimbKeys = StorageManager.getSavedClimbKeys();

        var meta = StorageManager.getSavedRouteMeta();
        savedRouteNames       = new [savedRouteIds.size()];
        savedRouteClimbCounts = new [savedRouteIds.size()];
        for (var i = 0; i < savedRouteIds.size(); i++) {
            var m = meta.get(savedRouteIds[i]);
            if (m instanceof Toybox.Lang.Dictionary) {
                savedRouteNames[i]       = m.get("name");
                savedRouteClimbCounts[i] = m.get("climbCount");
            } else {
                // Route saved before the meta index existed: load once and backfill.
                var p = StorageManager.loadRoute(savedRouteIds[i]);
                if (p instanceof Toybox.Lang.Dictionary) {
                    StorageManager.saveRoute(savedRouteIds[i], p);
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
    }
```

- [ ] **Step 3: Add phone-connection dot to RouteListView**

In `garmin-widget/source/RouteView.mc`, add `using Toybox.System as Sys;` next to the other `using` lines at the top:

```java
using Toybox.System as Sys;
```

In `RouteListView.onUpdate`, directly after `dc.clear()` and before the header text block, add:

```java
        // Phone-connection indicator: green dot = verbonden, rood = geen verbinding
        var phoneConnected = Sys.getDeviceSettings().phoneConnected;
        dc.setColor(phoneConnected ? 0x00AA00 : Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
        dc.fillCircle(w - 10, 10, 5);
```

This draws a small circle (radius 5) in the top-right corner of the route-list screen. Green means the phone is connected via Bluetooth; red means it is out of range or Bluetooth is off. The indicator is visible at all times in the route list so the user knows before tapping "Zet actief" whether the relay will work.

- [ ] **Step 4: Build to verify**

Same `monkeyc` command as Task 2 Step 3, from `garmin-widget/`. Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add garmin-widget/source/StorageManager.mc garmin-widget/source/RouteView.mc
git commit -m "feat(watch-app): lazy saved-route list via meta index + phone-connection indicator"
```

---

## Task 4: Protocol — `surfaceSections` in schema + examples (schema-first)

**Files:**
- Modify: `protocol/schema.json`
- Modify: `protocol/schema.md`
- Modify: `protocol/examples/route_mode_full.json`

**Background:** CLAUDE.md requires wire-format changes to start in `protocol/schema.json`. The schema describes the *logical* model (`segments` as objects) while the actual wire uses packed int arrays (`segs`); we follow that convention: logical `surfaceSections` objects in the schema, packed `surfSec` triples on the wire, documented in `schema.md`.

- [ ] **Step 1: Add the property and definition to schema.json**

In `protocol/schema.json`, inside `"properties"` add after the `"name"` property (after line 31, before `"climbs"`):

```json
    "surfaceSections": {
      "description": "Optional user-defined surface overrides for arbitrary stretches of the route (route mode only), ordered by startDistance. Wire encoding: packed int array 'surfSec' of [startDistance, endDistance, surfaceType, ...] triples.",
      "type": "array",
      "minItems": 0,
      "maxItems": 32,
      "items": { "$ref": "#/definitions/SurfaceSection" }
    },
```

Inside `"definitions"` add after the closing brace of `"Segment"` (before the final `}` of definitions), preceded by a comma on the `Segment` block:

```json
    "SurfaceSection": {
      "type": "object",
      "additionalProperties": false,
      "required": ["startDistance", "endDistance", "surfaceType"],
      "properties": {
        "startDistance": {
          "description": "Metres from route start where the section begins.",
          "type": "integer",
          "minimum": 0
        },
        "endDistance": {
          "description": "Metres from route start where the section ends. Must exceed startDistance.",
          "type": "integer",
          "minimum": 1
        },
        "surfaceType": {
          "description": "SurfaceType constant: 0=asphalt 1=gravel 2=dirt 3=cobblestone 4=mixed 5=unknown.",
          "type": "integer",
          "minimum": 0,
          "maximum": 5
        }
      }
    }
```

- [ ] **Step 2: Add example data**

In `protocol/examples/route_mode_full.json`, add a top-level key directly after the `"name"` key (keep distances within the example route's length — inspect the file's last climb `endDistance` and stay below it):

```json
  "surfaceSections": [
    { "startDistance": 1000, "endDistance": 2500, "surfaceType": 1 },
    { "startDistance": 6000, "endDistance": 7000, "surfaceType": 3 }
  ],
```

- [ ] **Step 3: Document the wire key in schema.md**

In `protocol/schema.md`, in the section that documents the wire keys (where `segs` / `calib` / `surf` are described), add a row/paragraph in the same style:

```markdown
- `surfSec` — packed int array of user-defined surface sections: `[startDistance, endDistance, surfaceType, …]`, 3 ints per section, ordered by startDistance. Route mode only. Sent in a dedicated lean payload (with `"climbs": []`) to the surface datafield app, not in the climb datafield payload.
```

- [ ] **Step 4: Run the Android test suite to catch round-trip/codegen breaks**

Run: `cd android` then `.\gradlew.bat test`
Expected: `BUILD SUCCESSFUL`. If a generated-POJO or schema round-trip test fails, fix the schema JSON syntax (trailing commas are the usual culprit).

- [ ] **Step 5: Commit**

```bash
git add protocol/schema.json protocol/schema.md protocol/examples/route_mode_full.json
git commit -m "feat(protocol): surfaceSections payload property + surfSec wire key"
```

---

## Task 5: ClimbPayloadBuilder — single-climb and surface payloads (TDD)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`
- Create: `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderActivePayloadTest.java`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderActivePayloadTest.java`:

```java
package nl.paree.climbpro.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClimbPayloadBuilderActivePayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static StoredRoute routeWithTwoClimbs() {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        route.climbs.add(climb("Climb A", 1000));
        route.climbs.add(climb("Climb B", 5000));
        return route;
    }

    private static StoredClimb climb(String name, int startDistance) {
        StoredClimb c   = new StoredClimb();
        c.name          = name;
        c.startDistance = startDistance;
        c.endDistance   = startDistance + 900;
        c.length        = 900;
        c.elevationGain = 45;
        c.avgGradient   = 0.05;
        c.segments      = new ArrayList<>();
        StoredSegment s = new StoredSegment();
        s.distance      = 900;
        s.elevationGain = 45;
        s.gradient      = 0.05;
        s.colorIndex    = 2;
        c.segments.add(s);
        return c;
    }

    @Test
    public void singleClimbPayload_containsOnlyRequestedClimb() throws Exception {
        byte[] payload = new ClimbPayloadBuilder(mapper)
                .buildSingleClimbPayload(routeWithTwoClimbs(), 1);

        Map<?, ?> decoded = mapper.readValue(payload, Map.class);
        assertEquals(3, decoded.get("v"));
        assertEquals("route", decoded.get("mode"));
        List<?> climbs = (List<?>) decoded.get("climbs");
        assertEquals(1, climbs.size());
        assertEquals("Climb B", ((Map<?, ?>) climbs.get(0)).get("n"));
    }

    @Test
    public void singleClimbPayload_indexOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClimbPayloadBuilder(mapper)
                        .buildSingleClimbPayload(routeWithTwoClimbs(), 7));
    }

    @Test
    public void surfacePayload_packsSectionsAsTriples() throws Exception {
        StoredRoute route = routeWithTwoClimbs();
        route.surfaceSections = new ArrayList<>();
        StoredSurfaceSection s1 = new StoredSurfaceSection();
        s1.startDistance = 1000; s1.endDistance = 2500; s1.surfaceType = SurfaceType.GRAVEL;
        StoredSurfaceSection s2 = new StoredSurfaceSection();
        s2.startDistance = 6000; s2.endDistance = 7000; s2.surfaceType = SurfaceType.COBBLESTONE;
        route.surfaceSections.add(s1);
        route.surfaceSections.add(s2);

        byte[] payload = new ClimbPayloadBuilder(mapper).buildSurfaceSectionPayload(route);
        Map<?, ?> decoded = mapper.readValue(payload, Map.class);

        assertEquals(3, decoded.get("v"));
        assertEquals("route", decoded.get("mode"));
        assertEquals("r1", decoded.get("routeId"));
        assertTrue("surface payload must not carry climbs",
                ((List<?>) decoded.get("climbs")).isEmpty());
        assertEquals(Arrays.asList(1000, 2500, SurfaceType.GRAVEL,
                                   6000, 7000, SurfaceType.COBBLESTONE),
                decoded.get("surfSec"));
    }

    @Test
    public void surfacePayload_noSections_hasEmptySurfSec() throws Exception {
        byte[] payload = new ClimbPayloadBuilder(mapper)
                .buildSurfaceSectionPayload(routeWithTwoClimbs());
        Map<?, ?> decoded = mapper.readValue(payload, Map.class);
        assertTrue(((List<?>) decoded.get("surfSec")).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android` then `.\gradlew.bat test --tests nl.paree.climbpro.service.ClimbPayloadBuilderActivePayloadTest`
Expected: FAIL — compilation error `cannot find symbol: method buildSingleClimbPayload`.

- [ ] **Step 3: Implement both builder methods**

In `android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java`, add an import next to the other `data.route` imports:

```java
import nl.paree.climbpro.data.route.StoredSurfaceSection;
```

Add directly after `buildRadiusPayload` (after line 63):

```java
    /** Route-mode payload containing exactly one climb (watch "set active climb"). */
    public byte[] buildSingleClimbPayload(StoredRoute route, int climbIndex) throws IOException {
        if (route.climbs == null || climbIndex < 0 || climbIndex >= route.climbs.size()) {
            throw new IllegalArgumentException("climbIndex out of range: " + climbIndex);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        List<Map<String, Object>> climbs = new ArrayList<>(1);
        climbs.add(buildRouteClimb(route.climbs.get(climbIndex)));
        payload.put("climbs", climbs);
        return mapper.writeValueAsBytes(payload);
    }

    /**
     * Lean payload for the surface-sections datafield: no climbs, packed
     * surfSec triples [startDistance, endDistance, surfaceType, ...].
     * An empty surfSec is sent deliberately so a stale route on the watch is cleared.
     */
    public byte[] buildSurfaceSectionPayload(StoredRoute route) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v",       SCHEMA_VERSION);
        payload.put("mode",    "route");
        payload.put("routeId", route.routeId);
        String name = route.userDisplayName != null ? route.userDisplayName : route.name;
        if (name != null && name.length() <= 32) payload.put("name", name);
        payload.put("climbs", new ArrayList<>());
        List<StoredSurfaceSection> sections = route.surfaceSections;
        int count = sections == null ? 0 : sections.size();
        int[] packed = new int[count * 3];
        for (int i = 0; i < count; i++) {
            StoredSurfaceSection s = sections.get(i);
            packed[i * 3]     = s.startDistance;
            packed[i * 3 + 1] = s.endDistance;
            packed[i * 3 + 2] = nl.paree.climbpro.domain.segment.SurfaceType.fromInt(s.surfaceType);
        }
        payload.put("surfSec", packed);
        return mapper.writeValueAsBytes(payload);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd android` then `.\gradlew.bat test --tests nl.paree.climbpro.service.ClimbPayloadBuilderActivePayloadTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java android/app/src/test/java/nl/paree/climbpro/service/ClimbPayloadBuilderActivePayloadTest.java
git commit -m "feat(android): single-climb + surface-section payload builders"
```

---

## Task 6: ConnectIqClient — send to the two datafield app IDs

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java`
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`

**Background:** The client is hardwired to the watch-app ID (`fedcba...`). The relay must also push payloads to the climb datafield (`0123...`) and the new surface datafield. Incoming app events stay registered on the watch-app only — datafields never send to the phone.

- [ ] **Step 1: Add the two app-ID constants**

Replace the body of `ConnectIqAppId.java` (keep the class javadoc) so the class reads:

```java
public final class ConnectIqAppId {
    private ConnectIqAppId() {}

    /** Watch app (browse/select UI; was the widget). Counterpart for incoming messages. */
    public static final String VALUE = "fedcba9876543210fedcba9876543210";

    /** Existing ClimbPro datafield — receives the active route/climb payload. */
    public static final String DATAFIELD = "0123456789abcdef0123456789abcdef";

    /** Surface-sections datafield. MUST equal the id in garmin-surface/manifest.xml. */
    public static final String SURFACE_FIELD = "00112233445566770011223344556677";
}
```

- [ ] **Step 2: Generalise the send path in ConnectIqClient**

In `ConnectIqClient.java`, add two fields directly under the existing `iqApp` field (line 39):

```java
    private final IQApp iqApp        = new IQApp(ConnectIqAppId.VALUE);
    private final IQApp datafieldApp = new IQApp(ConnectIqAppId.DATAFIELD);
    private final IQApp surfaceApp   = new IQApp(ConnectIqAppId.SURFACE_FIELD);
```

Replace the existing `sendMessage` method (lines 133-154) with:

```java
    /** Fire-and-forget send of a Map to the watch app (serialised to a Dictionary by the SDK). */
    public boolean sendMessage(Map<String, Object> message) {
        return sendMessageTo(iqApp, message);
    }

    private boolean sendMessageTo(IQApp targetApp, Map<String, Object> message) {
        final IQDevice d = device;
        if (!connected || d == null) {
            Log.w(TAG, "sendMessageTo: not connected");
            return false;
        }
        try {
            stateLd.postValue(ConnectIqState.SENDING);
            connectIQ.sendMessage(d, targetApp, message, (dev, app, status) -> {
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Log.e(TAG, "sendMessageTo status: " + status);
                }
                stateLd.postValue(ConnectIqState.CONNECTED);
            });
            return true;
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "sendMessageTo failed", e);
            stateLd.postValue(ConnectIqState.ERROR);
            return false;
        }
    }
```

Replace the existing `sendPayload` method (lines 156-164) with:

```java
    /** Decode JSON payload to a Map and send it to the watch app. */
    public boolean sendPayload(byte[] payload) {
        return sendPayloadTo(iqApp, payload);
    }

    /** Send a route/climb payload to the ClimbPro datafield app. */
    public boolean sendPayloadToDatafield(byte[] payload) {
        return sendPayloadTo(datafieldApp, payload);
    }

    /** Send a surface-sections payload to the surface datafield app. */
    public boolean sendPayloadToSurfaceField(byte[] payload) {
        return sendPayloadTo(surfaceApp, payload);
    }

    private boolean sendPayloadTo(IQApp targetApp, byte[] payload) {
        try {
            return sendMessageTo(targetApp, PayloadCodec.decode(payload));
        } catch (IOException e) {
            Log.e(TAG, "sendPayloadTo: cannot parse payload JSON", e);
            return false;
        }
    }
```

- [ ] **Step 3: Verify compilation + existing tests**

Run: `cd android` then `.\gradlew.bat test`
Expected: `BUILD SUCCESSFUL` — behaviour of the existing send paths is unchanged.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java
git commit -m "feat(android): ConnectIqClient sends to datafield and surface-field app IDs"
```

---

## Task 7: WatchRequestHandler — SET_ACTIVE_ROUTE / SET_ACTIVE_CLIMB (TDD)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java`
- Modify: `android/app/src/test/java/nl/paree/climbpro/connectiq/WatchRequestHandlerTest.java`

**Wire contract (watch → phone):**
- `{"type":"SET_ACTIVE_ROUTE","id":"<routeId>"}` → phone sends full route payload to the climb datafield **and** the surface payload to the surface datafield, then acks.
- `{"type":"SET_ACTIVE_CLIMB","id":"<routeId>","climbIdx":<n>}` → phone sends a single-climb payload to the climb datafield only (surface sections are route-distance-relative and meaningless on a standalone climb ride), then acks.
- Ack (phone → watch app): `{"type":"ACTIVE_SET","ok":true|false,"name":"<display name, when ok>"}`.

- [ ] **Step 1: Add the failing tests**

Append inside `WatchRequestHandlerTest` (before the `msg` helper):

```java
    @Test
    public void setActiveRoute_sendsToBothDatafields_andAcksOk() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        when(mockClient.sendPayloadToDatafield(any(byte[].class))).thenReturn(true);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "SET_ACTIVE_ROUTE");
        request.put("id",   "r1");
        handler.handleMessage(request);

        verify(mockClient).sendPayloadToDatafield(any(byte[].class));
        verify(mockClient).sendPayloadToSurfaceField(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ACTIVE_SET", captor.getValue().get("type"));
        assertEquals(Boolean.TRUE, captor.getValue().get("ok"));
        assertEquals("Test route", captor.getValue().get("name"));
    }

    @Test
    public void setActiveRoute_repoThrows_acksFailure() throws IOException {
        when(mockRepo.loadRoute("missing")).thenThrow(new IOException("not found"));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "SET_ACTIVE_ROUTE");
        request.put("id",   "missing");
        handler.handleMessage(request);

        verify(mockClient, never()).sendPayloadToDatafield(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ACTIVE_SET", captor.getValue().get("type"));
        assertEquals(Boolean.FALSE, captor.getValue().get("ok"));
    }

    @Test
    public void setActiveClimb_sendsSingleClimb_noSurfacePayload() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        nl.paree.climbpro.data.route.StoredClimb a = new nl.paree.climbpro.data.route.StoredClimb();
        a.name = "A";
        nl.paree.climbpro.data.route.StoredClimb b = new nl.paree.climbpro.data.route.StoredClimb();
        b.name = "B";
        route.climbs.add(a);
        route.climbs.add(b);
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        when(mockClient.sendPayloadToDatafield(any(byte[].class))).thenReturn(true);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type",     "SET_ACTIVE_CLIMB");
        request.put("id",       "r1");
        request.put("climbIdx", 1);
        handler.handleMessage(request);

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockClient).sendPayloadToDatafield(payloadCaptor.capture());
        verify(mockClient, never()).sendPayloadToSurfaceField(any(byte[].class));
        Map<?, ?> decoded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(payloadCaptor.getValue(), Map.class);
        List<?> climbs = (List<?>) decoded.get("climbs");
        assertEquals(1, climbs.size());
        assertEquals("B", ((Map<?, ?>) climbs.get(0)).get("n"));

        ArgumentCaptor<Map> ackCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(ackCaptor.capture());
        assertEquals(Boolean.TRUE, ackCaptor.getValue().get("ok"));
        assertEquals("B", ackCaptor.getValue().get("name"));
    }

    @Test
    public void setActiveClimb_badIndex_acksFailure() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type",     "SET_ACTIVE_CLIMB");
        request.put("id",       "r1");
        request.put("climbIdx", 5);
        handler.handleMessage(request);

        verify(mockClient, never()).sendPayloadToDatafield(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().get("ok"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd android` then `.\gradlew.bat test --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest`
Expected: FAIL — `cannot find symbol: method sendPayloadToDatafield` resolves now (Task 6), so the failure is the handler ignoring the new types: assertion errors / `Wanted but not invoked: sendMessage(...)`.

- [ ] **Step 3: Implement the handlers**

In `WatchRequestHandler.java`, add an import:

```java
import nl.paree.climbpro.data.route.StoredClimb;
```

Extend `handleMessage` — replace the `if/else` chain with:

```java
        if ("LIST_ROUTES".equals(type)) {
            handleListRoutes();
        } else if ("LOAD_ROUTE".equals(type)) {
            handleLoadRoute((String) message.get("id"));
        } else if ("SET_ACTIVE_ROUTE".equals(type)) {
            handleSetActiveRoute((String) message.get("id"));
        } else if ("SET_ACTIVE_CLIMB".equals(type)) {
            Object idx = message.get("climbIdx");
            handleSetActiveClimb((String) message.get("id"),
                    idx instanceof Number ? ((Number) idx).intValue() : -1);
        } else {
            Log.w(TAG, "Unknown message type from watch: " + type);
        }
```

Add these methods after `handleLoadRoute`:

```java
    private void handleSetActiveRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            ackActiveSet(false, null);
            return;
        }
        try {
            StoredRoute route = routeRepo.loadRoute(routeId);
            ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper);
            boolean ok = connectIqClient.sendPayloadToDatafield(builder.buildRoutePayload(route));
            // Always push the surface payload — an empty surfSec clears stale sections.
            connectIqClient.sendPayloadToSurfaceField(builder.buildSurfaceSectionPayload(route));
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            ackActiveSet(ok, name);
            Log.i(TAG, "SET_ACTIVE_ROUTE " + routeId + " ok=" + ok);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "SET_ACTIVE_ROUTE failed for " + routeId, e);
            ackActiveSet(false, null);
        }
    }

    private void handleSetActiveClimb(String routeId, int climbIndex) {
        if (routeId == null || routeId.isEmpty() || climbIndex < 0) {
            ackActiveSet(false, null);
            return;
        }
        try {
            StoredRoute route = routeRepo.loadRoute(routeId);
            byte[] payload = new ClimbPayloadBuilder(mapper)
                    .buildSingleClimbPayload(route, climbIndex);
            boolean ok = connectIqClient.sendPayloadToDatafield(payload);
            StoredClimb climb = route.climbs.get(climbIndex);
            String name = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
            ackActiveSet(ok, name);
            Log.i(TAG, "SET_ACTIVE_CLIMB " + routeId + "[" + climbIndex + "] ok=" + ok);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "SET_ACTIVE_CLIMB failed for " + routeId + "[" + climbIndex + "]", e);
            ackActiveSet(false, null);
        }
    }

    private void ackActiveSet(boolean ok, String name) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "ACTIVE_SET");
        ack.put("ok",   ok);
        if (name != null) ack.put("name", name);
        connectIqClient.sendMessage(ack);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd android` then `.\gradlew.bat test --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest`
Expected: PASS (10 tests: 6 existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/WatchRequestHandler.java android/app/src/test/java/nl/paree/climbpro/connectiq/WatchRequestHandlerTest.java
git commit -m "feat(android): SET_ACTIVE_ROUTE/SET_ACTIVE_CLIMB relay with ACTIVE_SET ack"
```

---

## Task 8: Watch app — "Zet actief" actions + ack feedback view

**Files:**
- Create: `garmin-widget/source/ActiveSetView.mc`
- Modify: `garmin-widget/source/CommListener.mc:33-39`
- Modify: `garmin-widget/source/ClimbListView.mc`
- Modify: `garmin-widget/source/ClimbDetailView.mc`
- Modify: `garmin-widget/source/RouteView.mc:202` (push-site of ClimbDetailView)

- [ ] **Step 1: Create ActiveSetView.mc**

Create `garmin-widget/source/ActiveSetView.mc`:

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

// Shown right after sending SET_ACTIVE_ROUTE / SET_ACTIVE_CLIMB to the phone.
// Displays "Versturen..." until PhoneMessageCallback stores the phone's
// ACTIVE_SET ack in App.getApp().activeAck.
class ActiveSetView extends Ui.View {

    function initialize() {
        View.initialize();
    }

    function onShow() {
        App.getApp().activeAck = null;
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var ack = App.getApp().activeAck;

        if (ack == null) {
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Versturen...",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        if (ack.get("ok") == true) {
            dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2 - 14, Gfx.FONT_SMALL, "Actief gezet",
                Gfx.TEXT_JUSTIFY_CENTER);
            var name = ack.get("name");
            if (name instanceof Toybox.Lang.String) {
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, h / 2 + 10, Gfx.FONT_XTINY, name,
                    Gfx.TEXT_JUSTIFY_CENTER);
            }
        } else {
            dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Mislukt",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class ActiveSetDelegate extends Ui.BehaviorDelegate {
    function initialize() { BehaviorDelegate.initialize(); }
    function onBack()   { Ui.popView(Ui.SLIDE_RIGHT); return true; }
    function onSelect() { Ui.popView(Ui.SLIDE_RIGHT); return true; }
}
```

- [ ] **Step 2: Handle the ACTIVE_SET ack in CommListener**

In `garmin-widget/source/CommListener.mc`, inside `onMessage`, replace the type-dispatch block (lines 33-39):

```java
        if (msgType instanceof Toybox.Lang.String) {
            if (msgType.equals("ROUTE_LIST")) {
                handleRouteList(msg);
            } else if (msgType.equals("ACTIVE_SET")) {
                App.getApp().activeAck = msg;
            } else {
                Sys.println("CommListener: unknown type: " + msgType);
            }
            return;
        }
```

(`App.getApp().onPhoneMessage` already calls `Ui.requestUpdate()` after every message, so the waiting `ActiveSetView` redraws.)

- [ ] **Step 3: Add the "Zet actief" action row to ClimbListView**

In `garmin-widget/source/ClimbListView.mc`:

a) Line 67 — change the total to two action rows:

```java
        var totalItems = data.climbCount + 2;  // last items: save/delete + set-active
```

b) Replace the `if (i < data.climbCount) { ... } else { ... }` row-rendering block (lines 93-110) with:

```java
            if (i < data.climbCount) {
                var name = data.climbName[i];
                if (name == null) { name = "Climb " + (i + 1); }
                dc.drawText(w / 2, yPos + 4, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

                var grad     = data.climbAvgGrad[i];
                var absGrad  = grad < 0 ? -grad : grad;
                var sign     = grad < 0 ? "-" : "";
                var gradStr  = sign + (absGrad / 10) + "." + (absGrad % 10) + "%  " + formatDist(data.climbLength[i]);
                dc.drawText(w / 2, yPos + 20, Gfx.FONT_XTINY, gradStr, Gfx.TEXT_JUSTIFY_CENTER);
            } else if (i == data.climbCount) {
                if (routeSaved == null) { refreshRouteSaved(); }
                var isSaved = routeSaved;
                var label   = isSaved ? "Delete route" : "Save route";
                var color   = isSaved ? Gfx.COLOR_RED : Gfx.COLOR_GREEN;
                dc.setColor(i == selectedIndex ? Gfx.COLOR_WHITE : color, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, yPos + 14, Gfx.FONT_XTINY, label, Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                dc.setColor(i == selectedIndex ? Gfx.COLOR_WHITE : Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, yPos + 14, Gfx.FONT_XTINY, "Zet actief", Gfx.TEXT_JUSTIFY_CENTER);
            }
```

c) In `ClimbListDelegate.onNextPage` (line 134), change `var total = data.climbCount + 1;` to:

```java
            var total = data.climbCount + 2;
```

d) Replace `ClimbListDelegate.onSelect`'s branch logic (lines 158-176) with:

```java
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
            view.refreshRouteSaved();
            Ui.requestUpdate();
        } else if (view.selectedIndex == data.climbCount + 1) {
            Comm.transmit({ "type" => "SET_ACTIVE_ROUTE", "id" => view.getRouteId() },
                          null, new CommListener());
            Ui.pushView(new ActiveSetView(), new ActiveSetDelegate(), Ui.SLIDE_LEFT);
        } else {
            Ui.pushView(
                new ClimbDetailView(view.selectedIndex, view.selectedIndex),
                new ClimbDetailDelegate(),
                Ui.SLIDE_LEFT
            );
        }
        return true;
```

- [ ] **Step 4: Add MENU → set-active-climb to ClimbDetailView**

In `garmin-widget/source/ClimbDetailView.mc`:

a) Add `using Toybox.Communications as Comm;` to the `using` block at the top.

b) The constructor gains the **original** climb index (a saved single climb is rendered as payload-index 0 but its real index in the route differs). Replace lines 7-15 with:

```java
    hidden var climbIndex;          // index into the currently loaded payload
    hidden var originalClimbIndex;  // index of this climb in the original route
    hidden var drawer;
    hidden var climbSaved = null;

    function initialize(ci, origIdx) {
        View.initialize();
        climbIndex         = ci;
        originalClimbIndex = origIdx;
        drawer = new ProfileDrawer();
    }

    function getOriginalClimbIndex() { return originalClimbIndex; }
```

c) In `onUpdate`, change the footer hint (lines 53-56) to also mention MENU:

```java
            dc.setColor(climbSaved ? Gfx.COLOR_RED : Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h - 8, Gfx.FONT_XTINY,
                (climbSaved ? "SELECT: Remove" : "SELECT: Save") + "  MENU: Actief",
                Gfx.TEXT_JUSTIFY_CENTER);
```

d) Add `onMenu` to `ClimbDetailDelegate` (after `onSelect`):

```java
    function onMenu() {
        var data = App.getApp().climbData;
        var rId  = data.routeId;
        var view = Ui.getCurrentView()[0];
        if (rId != null && view instanceof ClimbDetailView) {
            Comm.transmit({ "type" => "SET_ACTIVE_CLIMB", "id" => rId,
                            "climbIdx" => view.getOriginalClimbIndex() },
                          null, new CommListener());
            Ui.pushView(new ActiveSetView(), new ActiveSetDelegate(), Ui.SLIDE_LEFT);
        }
        return true;
    }
```

- [ ] **Step 5: Update the remaining ClimbDetailView push-site**

In `garmin-widget/source/RouteView.mc`, in `openSavedClimb` (line 202), the saved climb's payload index is 0 but its original route index is `climbIdx`:

```java
        Ui.pushView(new ClimbDetailView(0, climbIdx), new ClimbDetailDelegate(), Ui.SLIDE_LEFT);
```

- [ ] **Step 6: Build the watch app**

Same `monkeyc` command as Task 2 Step 3, from `garmin-widget/`. Expected: no errors. A `Too many arguments` error on `ClimbDetailView` means a push-site wasn't updated (there are exactly two: `ClimbListView.mc` and `RouteView.mc`).

- [ ] **Step 7: Commit**

```bash
git add garmin-widget/source/ActiveSetView.mc garmin-widget/source/CommListener.mc garmin-widget/source/ClimbListView.mc garmin-widget/source/ClimbDetailView.mc garmin-widget/source/RouteView.mc
git commit -m "feat(watch-app): set active route/climb via phone relay with ack view"
```

---

## Task 9: Existing datafield — persist + restore the active payload

**Files:**
- Modify: `garmin/source/CommListener.mc`
- Modify: `garmin/source/ClimbProApp.mc`

**Background:** This is the *only* change to the existing datafield — display code is untouched per the user's requirement. Persisting the parsed payload means a route set before the ride survives a datafield restart and works fully offline during the activity. `Storage.setValue` throws if a value exceeds ~8KB, so the write is guarded; the in-memory payload still works for the current session if persistence fails.

- [ ] **Step 1: Persist incoming payloads in the datafield's CommListener**

In `garmin/source/CommListener.mc` (the datafield's copy — not the widget's):

a) Add to the `using` block at the top:

```java
using Toybox.Application.Storage as Storage;
```

b) In `PhoneMessageCallback.onMessage`, directly after `data.payloadReceived = true;` and the `calibIdx` reset loop, add:

```java
        try {
            Storage.setValue("active_payload", msg);
        } catch (e) {
            Sys.println("CommListener: payload too large to persist");
        }
```

- [ ] **Step 2: Restore the payload at datafield start**

In `garmin/source/ClimbProApp.mc`:

a) Add to the `using` block:

```java
using Toybox.Application.Storage as Storage;
```

b) In `onStart`, after `Comm.registerForPhoneAppMessages(...)` and before the final `Sys.println`, add:

```java
        var saved = Storage.getValue("active_payload");
        if (saved != null) {
            msgCallback.onMessage(saved);
            Sys.println("ClimbPro: restored active payload from storage");
        }
```

- [ ] **Step 3: Build the datafield**

Run from `garmin/`:

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbPro.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Expected: no errors, `ClimbPro.prg` rebuilt.

- [ ] **Step 4: Commit**

```bash
git add garmin/source/CommListener.mc garmin/source/ClimbProApp.mc
git commit -m "feat(datafield): persist and restore active payload via Storage"
```

---

## Task 10: New surface-sections datafield (`garmin-surface/`)

**Files:**
- Create: `garmin-surface/manifest.xml`
- Create: `garmin-surface/monkey.jungle`
- Create: `garmin-surface/resources/strings/strings.xml`
- Create: `garmin-surface/resources/drawables/drawables.xml`
- Create: `garmin-surface/resources/drawables/launcher_icon.png` (copy)
- Create: `garmin-surface/source/SurfaceData.mc`
- Create: `garmin-surface/source/SurfaceFieldApp.mc`
- Create: `garmin-surface/source/SurfaceFieldView.mc`

- [ ] **Step 1: Scaffold the module**

Create `garmin-surface/manifest.xml`:

```xml
<?xml version="1.0"?>
<iq:manifest version="3" xmlns:iq="http://www.garmin.com/xml/connectiq">
    <iq:application
        entry="SurfaceFieldApp"
        type="datafield"
        name="@Strings.AppName"
        id="00112233445566770011223344556677"
        launcherIcon="@Drawables.LauncherIcon"
        minApiLevel="3.2.0">
        <iq:products>
            <iq:product id="fr255m"/>
        </iq:products>
        <iq:permissions>
            <iq:uses-permission id="Communications"/>
        </iq:permissions>
        <iq:languages>
            <iq:language>eng</iq:language>
        </iq:languages>
        <iq:barrels/>
    </iq:application>
</iq:manifest>
```

Create `garmin-surface/monkey.jungle`:

```
project.manifest = manifest.xml
base.sourcePath = source
base.resourcePath = resources
```

Create `garmin-surface/resources/strings/strings.xml`:

```xml
<strings>
    <string id="AppName">Ondergrond</string>
</strings>
```

Copy the icon resources from the existing datafield (run from the repo root):

```powershell
New-Item -ItemType Directory -Force garmin-surface\resources\drawables
Copy-Item garmin\resources\drawables\drawables.xml  garmin-surface\resources\drawables\drawables.xml
Copy-Item garmin\resources\drawables\launcher_icon.png garmin-surface\resources\drawables\launcher_icon.png
New-Item -ItemType Directory -Force garmin-surface\source
```

- [ ] **Step 2: Create SurfaceData.mc**

Create `garmin-surface/source/SurfaceData.mc`:

```java
using Toybox.System as Sys;

// Parallel-array store for the user-defined surface sections of the active
// route. Filled from the phone payload's packed "surfSec" triples:
// [startDistance, endDistance, surfaceType, ...].
class SurfaceData {

    const MAX_SECTIONS = 32;

    var payloadReceived = false;
    var routeId = null;
    var routeName = null;

    var count = 0;
    var secStart;   // metres from route start
    var secEnd;     // metres from route start
    var secType;    // SurfaceType constant 0..5

    // Runtime state, refreshed by updateProgress()
    var currentIdx = -1;        // section the rider is in (-1 = none)
    var nextIdx = -1;           // first section ahead (-1 = none)
    var remainingInSection = 0; // metres left in current section
    var distToNext = -1;        // metres to next section start

    function initialize() {
        secStart = new [MAX_SECTIONS];
        secEnd   = new [MAX_SECTIONS];
        secType  = new [MAX_SECTIONS];
    }

    // Parses a phone payload dictionary. Returns true when it carried surfSec.
    function parse(msg) {
        if (!(msg instanceof Toybox.Lang.Dictionary)) { return false; }
        var version = msg.get("v");
        if (version == null || version != 3) {
            Sys.println("SurfaceData: unsupported version " + version);
            return false;
        }
        var surfSec = msg.get("surfSec");
        if (!(surfSec instanceof Toybox.Lang.Array)) { return false; }

        routeId   = msg.get("routeId");
        routeName = msg.get("name");

        var n = surfSec.size() / 3;
        if (n > MAX_SECTIONS) { n = MAX_SECTIONS; }
        count = n;
        for (var i = 0; i < n; i++) {
            secStart[i] = surfSec[i * 3];
            secEnd[i]   = surfSec[i * 3 + 1];
            var t = surfSec[i * 3 + 2];
            secType[i]  = (t instanceof Toybox.Lang.Number && t >= 0 && t <= 5) ? t : 5;
        }
        payloadReceived = true;
        currentIdx = -1;
        nextIdx = -1;
        Sys.println("SurfaceData: " + count + " sections");
        return true;
    }

    // elapsed = activity elapsedDistance in metres. Sections are sorted by
    // startDistance (the phone keeps them sorted), so the first section that
    // starts beyond the rider is "next".
    function updateProgress(elapsed) {
        currentIdx = -1;
        nextIdx = -1;
        remainingInSection = 0;
        distToNext = -1;
        for (var i = 0; i < count; i++) {
            if (elapsed >= secStart[i] && elapsed < secEnd[i]) {
                currentIdx = i;
                remainingInSection = secEnd[i] - elapsed;
            } else if (secStart[i] > elapsed) {
                nextIdx = i;
                distToNext = secStart[i] - elapsed;
                break;
            }
        }
    }
}
```

- [ ] **Step 3: Create SurfaceFieldApp.mc**

Create `garmin-surface/source/SurfaceFieldApp.mc`:

```java
using Toybox.Application as App;
using Toybox.Application.Storage as Storage;
using Toybox.Communications as Comm;
using Toybox.System as Sys;
using Toybox.WatchUi as Ui;

class SurfaceFieldApp extends App.AppBase {

    var surfaceData;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        surfaceData = new SurfaceData();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));

        var saved = Storage.getValue("surface_payload");
        if (saved != null) {
            surfaceData.parse(saved);
        }
        Sys.println("SurfaceField: started");
    }

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg == null || msg.data == null) { return; }
        if (surfaceData.parse(msg.data)) {
            try {
                Storage.setValue("surface_payload", msg.data);
            } catch (e) {
                Sys.println("SurfaceField: payload too large to persist");
            }
            Ui.requestUpdate();
        }
    }

    function onStop(state) {}

    function getInitialView() {
        return [ new SurfaceFieldView() ];
    }
}
```

- [ ] **Step 4: Create SurfaceFieldView.mc**

Create `garmin-surface/source/SurfaceFieldView.mc`:

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

// Datafield showing the user-defined surface section the rider is currently
// in (surface name + remaining distance) plus a preview of the next section.
class SurfaceFieldView extends Ui.DataField {

    // Indices match SurfaceType constants 0..5
    hidden const SURF_NAMES = ["Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "?"];
    hidden const SURF_COLORS = [
        0x404040,  // 0: asphalt     — dark grey
        0xC8A050,  // 1: gravel      — sandy yellow
        0x8B4513,  // 2: dirt        — brown
        0x909090,  // 3: cobblestone — medium grey
        0x9060C0,  // 4: mixed       — purple
        0xAAAAAA   // 5: unknown     — light grey
    ];

    function initialize() {
        DataField.initialize();
    }

    function compute(info) {
        var data = App.getApp().surfaceData;
        if (data == null || !data.payloadReceived) { return; }
        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        data.updateProgress(elapsed);
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().surfaceData;

        if (data == null || !data.payloadReceived || data.count == 0) {
            dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Geen secties",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        if (data.currentIdx >= 0) {
            drawCurrentSection(dc, data, w, h);
        } else if (data.nextIdx >= 0) {
            drawNextOnly(dc, data, w, h);
        } else {
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Geen secties meer",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
        }
    }

    hidden function drawCurrentSection(dc, data, w, h) {
        var t = data.secType[data.currentIdx];

        // colour swatch above the surface name
        dc.setColor(SURF_COLORS[t], Gfx.COLOR_TRANSPARENT);
        dc.fillRectangle(w / 2 - 50, 6, 100, 8);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_MEDIUM, SURF_NAMES[t], Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
            "nog " + formatDist(data.remainingInSection), Gfx.TEXT_JUSTIFY_CENTER);

        if (data.nextIdx >= 0) {
            var nt = data.secType[data.nextIdx];
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_XTINY,
                "dan: " + SURF_NAMES[nt], Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    hidden function drawNextOnly(dc, data, w, h) {
        var nt = data.secType[data.nextIdx];
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_XTINY, "VOLGENDE", Gfx.TEXT_JUSTIFY_CENTER);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 10, Gfx.FONT_MEDIUM, SURF_NAMES[nt], Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_SMALL,
            "in " + formatDist(data.distToNext), Gfx.TEXT_JUSTIFY_CENTER);
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }
}
```

- [ ] **Step 5: Build the surface datafield**

Run from `garmin-surface/`:

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o Surface.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Expected: no errors, `Surface.prg` produced.

- [ ] **Step 6: Commit**

```bash
git add garmin-surface/
git commit -m "feat(surface-field): new datafield for user-defined surface sections"
```

---

## Task 11: Documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`

Per CLAUDE.md, architecture and wire-format changes must land in the docs in the same change.

- [ ] **Step 1: Update ARCHITECTURE.md**

Add a section (next to the existing watch/sync description) with this content:

```markdown
### Watch app, active-route relay & surface datafield (2026-06-10)

The browse widget is now a **device app** (`garmin-widget/`, manifest type
`watch-app`, same app ID) with a glance in the FR255 up/down loop. Heavy init
happens in `getInitialView` so the glance stays within its memory budget. The
saved-route list reads a lightweight `saved_route_meta` Storage index
(`{routeId → {name, climbCount}}`) instead of loading full payloads; climb
data is loaded only when a route is opened.

**Active route/climb selection.** Connect IQ apps have isolated storage, so the
watch app cannot hand a payload to a datafield directly. Selection is relayed
through the phone: watch app sends `SET_ACTIVE_ROUTE {id}` or
`SET_ACTIVE_CLIMB {id, climbIdx}`; `WatchRequestHandler` builds the payload and
pushes it to the datafield app IDs (`ConnectIqAppId.DATAFIELD`,
`ConnectIqAppId.SURFACE_FIELD`); the phone acks the watch app with
`ACTIVE_SET {ok, name}`. The climb datafield persists every received payload
under Storage key `active_payload` and restores it at `onStart`, so the ride
itself is fully offline. The phone must be reachable only at selection time.

**Surface-sections datafield** (`garmin-surface/`, app ID `00112233...`): shows
the user-defined surface section the rider is in (surface + remaining metres)
and the next one. It receives a dedicated lean payload `{v:3, mode:"route",
routeId, name, climbs:[], surfSec:[start,end,type, ...]}` built by
`ClimbPayloadBuilder.buildSurfaceSectionPayload` from
`StoredRoute.surfaceSections` (see `protocol/schema.json` `surfaceSections`).
An empty `surfSec` is sent on purpose to clear stale sections. Auto-detected
flat segments are deliberately **not** included (user decision 2026-06-10).
Single-climb activation sends no surface payload — sections are route-relative.
```

- [ ] **Step 2: Update README.md**

In the section of `README.md` that describes the watch components, replace the mention of the widget with this overview paragraph (adjust surrounding list formatting to match the file):

```markdown
The watch side consists of three Connect IQ apps for the Forerunner 255 Music:
the **ClimbPro app** (`garmin-widget/`, a device app with a glance in the
up/down loop) for browsing routes/climbs, saving them to the watch, and marking
one as *active*; the **ClimbPro datafield** (`garmin/`) that renders the active
climb during a ride (fully offline — the active payload is persisted on the
watch); and the **Ondergrond datafield** (`garmin-surface/`) that shows the
user-defined surface section you are riding plus the next one. Setting the
active route/climb from the watch requires the phone to be reachable at that
moment; the ride itself does not.
```

- [ ] **Step 3: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: watch app conversion, active-route relay, surface datafield"
```

---

## Task 12: Sideload + manual verification (manual — needs USB cable + phone)

- [ ] **Step 1: Copy the three .prg files to the watch**

```powershell
# Replace E: with the FR255's drive letter
Copy-Item garmin\ClimbPro.prg            E:\GARMIN\Apps\ClimbPro.prg
Copy-Item garmin-widget\ClimbBrowse.prg  E:\GARMIN\Apps\ClimbBrowse.prg
Copy-Item garmin-surface\Surface.prg     E:\GARMIN\Apps\Surface.prg
```

Safely eject.

- [ ] **Step 2: Verify on the watch**

1. **Glance/app:** press UP/DOWN from the watchface — "ClimbPro" glance with saved counts appears; opening it launches the full app (it must *not* time out after a few seconds like the old widget).
2. **Lazy list:** the route list shows phone + saved route names instantly; climb data loads only after selecting a route ("Loading…").
3. **Set active route:** open a route's climb list → scroll to "Zet actief" → SELECT → "Versturen…" then "Actief gezet: <name>" (phone must be connected with the ClimbPro app running).
4. **Set active climb:** open a climb detail → press MENU (long UP) → same ack flow.
5. **Datafield offline:** turn Bluetooth off on the phone, start a ride activity with the ClimbPro datafield — it must show the chosen route's climbs (restored from Storage), not "No data".
6. **Surface datafield:** add the "Ondergrond" datafield to a data screen. With a route active that has ondergrond-stukken, it shows the section/`VOLGENDE` view; with none, "Geen secties".

- [ ] **Step 3: Record any deviations**

If a manual check fails, file it as a bug note in the plan directory rather than patching ad-hoc — the failing area tells you which task to revisit.

---

## Final verification

- [ ] Run the full Android suite: `cd android` then `.\gradlew.bat test` → `BUILD SUCCESSFUL`.
- [ ] All three Connect IQ modules compile (`garmin/`, `garmin-widget/`, `garmin-surface/` — commands in Tasks 9, 2, 10).
- [ ] `git log --oneline` shows one commit per task.

---

## Self-review notes

- **Spec coverage:** echte app i.p.v. widget → Tasks 1-2; routes op naam + lazy kliminfo → bestaand gedrag + Task 3; klimmen handmatig op het horloge laden → bestaande Save route/Save climb blijft ongewijzigd; opgeslagen routes/klimmen als actief kiezen bij rit-start → Tasks 5-9; bestaand datafield vrijwel ongemoeid (alleen Storage-persistentie, geen renderwijziging) → Task 9; nieuw datafield voor handmatige ondergrond-stukken → Tasks 4, 5, 10.
- **Type consistency:** `buildSingleClimbPayload(StoredRoute, int)`, `buildSurfaceSectionPayload(StoredRoute)`, `sendPayloadToDatafield(byte[])`, `sendPayloadToSurfaceField(byte[])`, Storage keys `active_payload` / `surface_payload` / `saved_route_meta`, wire types `SET_ACTIVE_ROUTE` / `SET_ACTIVE_CLIMB` / `ACTIVE_SET`, and `ClimbDetailView.initialize(ci, origIdx)` are used identically across all tasks.
- **Known limitations (accepted):** selection requires phone reachability (user decision); `Comm.transmit` from the watch can succeed while the phone app is closed — the ActiveSetView then stays on "Versturen…" until the user backs out; surface sections beyond 32 are truncated watch-side.
