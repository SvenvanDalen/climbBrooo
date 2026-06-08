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
