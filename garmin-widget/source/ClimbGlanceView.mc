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

        // Connection dot: green = phone reachable, red = no connection. Colorblind palette
        // (issue #258): blue filled dot vs orange ring, so shape also tells them apart.
        var connected = Sys.getDeviceSettings().phoneConnected;
        WidgetPalette.drawConnectionDot(dc, w - 8, h / 4, connected, WidgetPalette.current());

        var routeCount = StorageManager.getSavedRouteIds().size();
        var climbCount = StorageManager.getSavedClimbKeys().size();
        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(0, (h * 3) / 4, Gfx.FONT_XTINY,
            routeCount + " routes, " + climbCount + " klimmen",
            Gfx.TEXT_JUSTIFY_LEFT);
    }
}
