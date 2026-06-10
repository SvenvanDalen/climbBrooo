using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

// Shown right after sending SET_ACTIVE_ROUTE / SET_ACTIVE_CLIMB to the phone.
// Displays "Versturen..." until PhoneMessageCallback stores the phone's
// ACTIVE_SET ack in App.getApp().activeAck.
class ActiveSetView extends Ui.View {

    function initialize() {
        View.initialize();
    }

    function onShow() {
        App.getApp().activeAck = null;
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var ack = App.getApp().activeAck;

        if (ack == null) {
            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Versturen...",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        if (ack.get("ok") == true) {
            dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2 - 14, Gfx.FONT_SMALL, "Actief gezet",
                Gfx.TEXT_JUSTIFY_CENTER);
            var name = ack.get("name");
            if (name instanceof Toybox.Lang.String) {
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, h / 2 + 10, Gfx.FONT_XTINY, name,
                    Gfx.TEXT_JUSTIFY_CENTER);
            }
        } else {
            dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Mislukt",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class ActiveSetDelegate extends Ui.BehaviorDelegate {
    function initialize() { BehaviorDelegate.initialize(); }
    function onBack()   { Ui.popView(Ui.SLIDE_RIGHT); return true; }
    function onSelect() { Ui.popView(Ui.SLIDE_RIGHT); return true; }
}
