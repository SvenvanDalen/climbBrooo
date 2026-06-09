# ClimbPro: Widget Glance + Datafield Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the ClimbPro widget appear in the Forerunner 255 widget loop (like Notifications/Weather) and make the ClimbPro datafield render climb data during dev/testing without needing a phone connection.

**Architecture:** Two separate Connect IQ apps — `garmin-widget/` (type=widget, id=fedcba...) for browsing climbs outside of activities, and `garmin/` (type=datafield, id=0123...) for rendering during activities. Both receive data independently from the Android companion app via separate `registerForPhoneAppMessages` channels. The Android app sends payloads to each app ID separately (not in scope here — watch-side only). Fake data in each app enables standalone dev/testing.

**Tech Stack:** Monkey C, Connect IQ SDK 9.1.0, target device fr255m (API 3.2.0+), developer key at `C:\Users\svenv\Documents\CLIMBPRODEF\developer_key`

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `garmin-widget/source/ClimbWidgetApp.mc` | Modify | Add `getGlanceView()` method |
| `garmin-widget/source/ClimbGlanceView.mc` | Create | Compact summary shown in widget glance loop |

---

## Task 1: Create ClimbGlanceView

**Files:**
- Create: `garmin-widget/source/ClimbGlanceView.mc`

**Background:** On FR255 with SDK 3.2.0+, `WatchUi.GlanceView` is the compact view shown in the widget glance strip when the user swipes through widgets (same strip as Notifications, Weather). Without it, the widget still exists but shows nothing in the strip until opened. The glance area on FR255 is the full 240px wide but roughly 60-70px tall.

- [ ] **Step 1: Create ClimbGlanceView.mc**

Create `garmin-widget/source/ClimbGlanceView.mc` with:

```java
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

class ClimbGlanceView extends Ui.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().climbData;

        if (data == null || !data.payloadReceived) {
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(
                w / 2, h / 2,
                Gfx.FONT_XTINY,
                "ClimbPro",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER
            );
            return;
        }

        var name = data.routeName != null ? data.routeName : "Route";

        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 3, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(
            w / 2, (h * 2 / 3),
            Gfx.FONT_XTINY,
            data.climbCount + " climbs",
            Gfx.TEXT_JUSTIFY_CENTER
        );
    }
}
```

- [ ] **Step 2: Build to check for compile errors**

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Run from: `garmin-widget/`

Expected: No errors. If `Symbol not found: GlanceView` appears, the minApiLevel in manifest needs to be at least 3.2.0 (it already is — check `garmin-widget/manifest.xml` line 9).

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/ClimbGlanceView.mc
git commit -m "feat(widget): add GlanceView for widget glance loop on FR255"
```

---

## Task 2: Register GlanceView in Widget App

**Files:**
- Modify: `garmin-widget/source/ClimbWidgetApp.mc`

**Background:** The `AppBase` class in SDK 3.2.0+ supports an optional `getGlanceView()` method. When present, the system calls it to display the compact glance. Data comes exclusively from the phone via `registerForPhoneAppMessages` — no fake data.

- [ ] **Step 1: Add getGlanceView() to ClimbWidgetApp.mc**

Add the following method between `onStop` and `getInitialView`:

```java
    function getGlanceView() {
        return [ new ClimbGlanceView() ];
    }
```

- [ ] **Step 2: Build widget**

```powershell
& "C:\Users\svenv\AppData\Roaming\Garmin\ConnectIQ\Sdks\connectiq-sdk-win-9.1.0-2026-03-09-6a872a80b\bin\monkeyc.bat" `
  -f monkey.jungle -d fr255m -o ClimbBrowse.prg `
  -y "C:\Users\svenv\Documents\CLIMBPRODEF\developer_key"
```

Run from: `garmin-widget/`

Expected: No errors, `ClimbBrowse.prg` updated.

- [ ] **Step 3: Commit**

```
git add garmin-widget/source/ClimbWidgetApp.mc
git commit -m "feat(widget): register GlanceView"
```

---

## Task 3: Sideload Both Apps to FR255

**Steps (manual — requires USB cable):**

- [ ] **Step 1: Enable Developer Mode on the watch**

On the FR255: `Settings` → `System` → `Developer Mode` → toggle ON

- [ ] **Step 2: Connect FR255 via USB and copy both .prg files**

```powershell
# Replace E: with the actual drive letter of your Forerunner 255
Copy-Item "garmin\ClimbPro.prg"          "E:\GARMIN\Apps\ClimbPro.prg"
Copy-Item "garmin-widget\ClimbBrowse.prg" "E:\GARMIN\Apps\ClimbBrowse.prg"
```

- [ ] **Step 3: Safely eject and verify**

Eject the USB drive from Windows. On the watch:
- Swipe through widgets — you should see "ClimbPro" with route name "Dev Route" and "2 climbs" in the glance strip
- Open the widget by pressing the middle button — should show "Dev Route / 2 climbs / SELECT to browse"
- Press SELECT — should show climb list with "Col du Tourmalet" and "Col d'Aspin"
- Press on a climb — should show the elevation profile with colored bars

For the datafield: start an activity, go to a data screen, long-press to customize the field, and select "ClimbPro" as a data field. It should show the climb profile immediately (not "No data") because of the fake data.

---

## Spec Coverage Check

| Requirement | Task |
|---|---|
| Widget appears in widget loop like Notifications | Task 1 + 2 (GlanceView) |
| Widget shows route name and climb count in glance | Task 1 (ClimbGlanceView) |
| Widget opens to full browse on press | Existing RouteView/ClimbListView/ClimbDetailView |
| Datafield renders climb data from phone | Phone sends v3 payload → CommListener parses |
| Data comes from phone, not fake | Fake data removed from ClimbProApp.mc (done) |
| Sideload to device | Task 3 |

## Notes for Later

- **Android sends to two app IDs**: The companion app must call `sendMessage` to both `0123456789abcdef0123456789abcdef` (datafield) and `fedcba9876543210fedcba9876543210` (widget) when route data changes. This is not in scope for this plan.
- **ClimbData.mc is duplicated**: Both apps have their own copy. They're currently identical. If one changes, the other must be updated manually. Consider using a Monkey C barrel (`.barrel` file) to share it if drift becomes a problem.
