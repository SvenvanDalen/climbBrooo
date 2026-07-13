using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;

class OnboardView extends Ui.View {

    function initialize() {
        View.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
                    "ClimbPro Onboard", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }
}

class OnboardDelegate extends Ui.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }
}
