using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;

// Phase 0 placeholder view.
// Renders a "ClimbPro" label centered. Real ClimbView / NextClimbView land in Phase 6.
class ClimbProView extends Ui.DataField {

    function initialize() {
        DataField.initialize();
    }

    function compute(info) {
        // No computation in scaffold. Phase 6 will read info.currentLocation,
        // info.elapsedDistance, etc.
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_TRANSPARENT, Gfx.COLOR_BLACK);
        dc.clear();
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Gfx.FONT_MEDIUM,
            "ClimbPro",
            Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER
        );
    }
}
