using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.System as Sys;

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

    function onShow() {
        refreshData();
        if (selectedIndex >= getTotalCount() && getTotalCount() > 0) {
            selectedIndex = getTotalCount() - 1;
        }
    }

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

    function getTotalCount() {
        return phoneCount + savedRouteIds.size() + savedClimbKeys.size();
    }

    function onUpdate(dc) {
        // Refresh phoneCount from the live index so that a response arriving after
        // SyncView's timeout (or after this view was already shown) is not missed.
        var _liveIndex = App.getApp().phoneRouteIndex;
        if (_liveIndex != null && _liveIndex.received) {
            phoneCount = _liveIndex.getCount();
        }

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        // Phone-connection indicator: green dot = verbonden, rood = geen verbinding
        var phoneConnected = Sys.getDeviceSettings().phoneConnected;
        dc.setColor(phoneConnected ? 0x00AA00 : Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
        dc.fillCircle(dc.getWidth() - 10, 10, 5);

        var w     = dc.getWidth();
        var h     = dc.getHeight();
        var total = getTotalCount();

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        if (total > 0) {
            dc.drawText(w / 2, 4, Gfx.FONT_XTINY,
                "Routes (" + (selectedIndex + 1) + "/" + total + ")",
                Gfx.TEXT_JUSTIFY_CENTER);
        } else {
            dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "Routes", Gfx.TEXT_JUSTIFY_CENTER);
        }

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
        if (climbIdx == null) { return; }
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
        Ui.pushView(new ClimbDetailView(0, climbIdx), new ClimbDetailDelegate(), Ui.SLIDE_LEFT);
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
