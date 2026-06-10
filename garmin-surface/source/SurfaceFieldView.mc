using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;

// Datafield showing the user-defined surface section the rider is currently
// in (surface name + remaining distance) plus a preview of the next section.
class SurfaceFieldView extends Ui.DataField {

    // Indices match SurfaceType constants 0..5
    hidden const SURF_NAMES = ["Asfalt", "Gravel", "Onverhard", "Kasseien", "Mixed", "?"];
    hidden const SURF_COLORS = [
        0x404040,  // 0: asphalt     — dark grey
        0xC8A050,  // 1: gravel      — sandy yellow
        0x8B4513,  // 2: dirt        — brown
        0x909090,  // 3: cobblestone — medium grey
        0x9060C0,  // 4: mixed       — purple
        0xAAAAAA   // 5: unknown     — light grey
    ];

    function initialize() {
        DataField.initialize();
    }

    function compute(info) {
        var data = App.getApp().surfaceData;
        if (data == null || !data.payloadReceived) { return; }
        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        data.updateProgress(elapsed);
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();

        var w = dc.getWidth();
        var h = dc.getHeight();
        var data = App.getApp().surfaceData;

        if (data == null || !data.payloadReceived || data.count == 0) {
            dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Geen secties",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }

        if (data.currentIdx >= 0) {
            drawCurrentSection(dc, data, w, h);
        } else if (data.nextIdx >= 0) {
            drawNextOnly(dc, data, w, h);
        } else {
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL, "Geen secties meer",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
        }
    }

    hidden function drawCurrentSection(dc, data, w, h) {
        var t = data.secType[data.currentIdx];

        // colour swatch above the surface name
        dc.setColor(SURF_COLORS[t], Gfx.COLOR_TRANSPARENT);
        dc.fillRectangle(w / 2 - 50, 6, 100, 8);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_MEDIUM, SURF_NAMES[t], Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
            "nog " + formatDist(data.remainingInSection), Gfx.TEXT_JUSTIFY_CENTER);

        if (data.nextIdx >= 0) {
            var nt = data.secType[data.nextIdx];
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_XTINY,
                "dan: " + SURF_NAMES[nt], Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    hidden function drawNextOnly(dc, data, w, h) {
        var nt = data.secType[data.nextIdx];
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_XTINY, "VOLGENDE", Gfx.TEXT_JUSTIFY_CENTER);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 10, Gfx.FONT_MEDIUM, SURF_NAMES[nt], Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_SMALL,
            "in " + formatDist(data.distToNext), Gfx.TEXT_JUSTIFY_CENTER);
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }
}
