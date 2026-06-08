using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

class ClimbListView extends Ui.View {

    var selectedIndex = 0;

    function initialize() { View.initialize(); }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        var w = dc.getWidth();
        var data = App.getApp().climbData;

        dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 6, Gfx.FONT_XTINY,
            "Climbs (" + (selectedIndex + 1) + "/" + data.climbCount + ")",
            Gfx.TEXT_JUSTIFY_CENTER);

        var startIdx = selectedIndex - 1;
        if (startIdx < 0) { startIdx = 0; }
        if (startIdx + 4 > data.climbCount) { startIdx = data.climbCount - 4; }
        if (startIdx < 0) { startIdx = 0; }

        var itemH = 36;
        var listTop = 30;

        for (var i = startIdx; i < data.climbCount && i < startIdx + 4; i++) {
            var yPos = listTop + (i - startIdx) * itemH;
            var name = data.climbName[i];
            if (name == null) { name = "Climb " + (i + 1); }

            if (i == selectedIndex) {
                dc.setColor(Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);
                dc.fillRectangle(10, yPos - 2, w - 20, itemH - 4);
                dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            } else {
                dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT);
            }

            dc.drawText(w / 2, yPos + 4, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

            var grad = data.climbAvgGrad[i];
            var gradFrac = grad % 10;
            if (gradFrac < 0) { gradFrac = -gradFrac; }
            var gradStr = (grad / 10) + "." + gradFrac + "%  " + formatDist(data.climbLength[i]);
            dc.drawText(w / 2, yPos + 20, Gfx.FONT_XTINY, gradStr, Gfx.TEXT_JUSTIFY_CENTER);
        }
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
        if (view instanceof ClimbListView && view.selectedIndex < data.climbCount - 1) {
            view.selectedIndex++;
            Ui.requestUpdate();
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
        if (view instanceof ClimbListView) {
            Ui.pushView(
                new ClimbDetailView(view.selectedIndex),
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
