using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Communications as Comm;

class ClimbDetailView extends Ui.View {

    hidden var climbIndex;
    hidden var originalClimbIndex;
    hidden var drawer;
    hidden var climbSaved = null;

    function initialize(ci, origIdx) {
        View.initialize();
        climbIndex         = ci;
        originalClimbIndex = origIdx;
        drawer = new ProfileDrawer();
    }

    function getOriginalClimbIndex() { return originalClimbIndex; }

    // Layout mirrors the datafield climb pages (name → centered profile → 3 stats → bottom line)
    // using proportional positions, so nothing collides or runs off the round display. The bottom
    // slot (where the datafield shows "in X km") carries the save/menu hint instead.
    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().climbData;
        var ci = climbIndex;

        // Name (title)
        var name = data.climbName[ci];
        if (name == null) { name = "Climb " + (ci + 1); }
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.15).toNumber(), Gfx.FONT_TINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        // Profile (same geometry as the datafield climb pages)
        var profileTop = (h * 0.30).toNumber();
        var profileH = (h * 0.35).toNumber();
        var profileBottom = profileTop + profileH;
        drawer.drawProfile(dc, data, ci, 8, profileTop, w - 16, profileH);
        drawer.drawSurfaceBar(dc, data, ci, 8, profileBottom + 2, w - 16);

        // Stats row
        var statsY = (h * 0.72).toNumber();
        var grad = data.climbAvgGrad[ci];
        var gradFrac = grad % 10;
        if (gradFrac < 0) { gradFrac = -gradFrac; }

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(32, statsY, Gfx.FONT_XTINY,
            formatDist(data.climbLength[ci]), Gfx.TEXT_JUSTIFY_LEFT);
        dc.drawText(w / 2, statsY, Gfx.FONT_XTINY,
            data.climbElevGain[ci] + "m", Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w - 32, statsY, Gfx.FONT_XTINY,
            (grad / 10) + "." + gradFrac + "%", Gfx.TEXT_JUSTIFY_RIGHT);

        // Bottom line: save/menu hint (same slot as the datafield "in X km" line)
        var rId = data.routeId;
        if (rId != null) {
            if (climbSaved == null) { refreshClimbSaved(); }
            dc.setColor(climbSaved ? Gfx.COLOR_RED : Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, (h * 0.88).toNumber(), Gfx.FONT_XTINY,
                (climbSaved ? "SELECT: Remove" : "SELECT: Save") + "  MENU: Actief",
                Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }

    function refreshClimbSaved() {
        var data = App.getApp().climbData;
        var rId = data.routeId;
        if (rId != null) {
            climbSaved = StorageManager.isClimbSaved(rId, climbIndex);
        } else {
            climbSaved = false;
        }
    }

    function toggleSave() {
        var data = App.getApp().climbData;
        var rId = data.routeId;
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
        refreshClimbSaved();
    }
}

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

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
