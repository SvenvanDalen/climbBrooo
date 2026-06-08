using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

class ClimbDetailView extends Ui.View {

    hidden var climbIndex;
    hidden var drawer;

    function initialize(ci) {
        View.initialize();
        climbIndex = ci;
        drawer = new ProfileDrawer();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().climbData;
        var ci = climbIndex;

        var name = data.climbName[ci];
        if (name == null) { name = "Climb " + (ci + 1); }
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 6, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        var profileTop = 26;
        var profileBottom = h - 30;
        var profileH = profileBottom - profileTop;
        drawer.drawProfile(dc, data, ci, 8, profileTop, w - 16, profileH);
        drawer.drawSurfaceBar(dc, data, ci, 8, profileBottom + 2, w - 16);

        var statsY = h - 22;
        var grad = data.climbAvgGrad[ci];
        var gradFrac = grad % 10;
        if (gradFrac < 0) { gradFrac = -gradFrac; }

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(24, statsY, Gfx.FONT_XTINY,
            formatDist(data.climbLength[ci]), Gfx.TEXT_JUSTIFY_LEFT);
        dc.drawText(w / 2, statsY, Gfx.FONT_XTINY,
            data.climbElevGain[ci] + "m", Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w - 24, statsY, Gfx.FONT_XTINY,
            (grad / 10) + "." + gradFrac + "%", Gfx.TEXT_JUSTIFY_RIGHT);
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }
}

class ClimbDetailDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
