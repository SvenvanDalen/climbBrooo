using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

class RouteView extends Ui.View {

    function initialize() { View.initialize(); }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().climbData;

        if (data == null || !data.payloadReceived) {
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
                "No data", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        var name = data.routeName != null ? data.routeName : "Route";
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 30, Gfx.FONT_SMALL, name, Gfx.TEXT_JUSTIFY_CENTER);

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2, Gfx.FONT_XTINY,
            data.climbCount + " climbs", Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, h / 2 + 24, Gfx.FONT_XTINY,
            "SELECT to browse", Gfx.TEXT_JUSTIFY_CENTER);
    }
}

class RouteDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onSelect() {
        var data = App.getApp().climbData;
        if (data != null && data.payloadReceived && data.climbCount > 0) {
            Ui.pushView(new ClimbListView(), new ClimbListDelegate(), Ui.SLIDE_LEFT);
        }
        return true;
    }
}
