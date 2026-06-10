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
    hidden var routeSaved = null;

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
                App.getApp().lastReceivedPayload = payload;
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
        var totalItems = data.climbCount + 2;  // last items: save/delete + set-active

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
                // "Zet actief" row
                dc.setColor(i == selectedIndex ? Gfx.COLOR_WHITE : Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, yPos + 14, Gfx.FONT_XTINY, "Zet actief", Gfx.TEXT_JUSTIFY_CENTER);
            }
        }
    }

    function refreshRouteSaved() {
        routeSaved = StorageManager.isRouteSaved(routeId);
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
            var total = data.climbCount + 2;
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
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
