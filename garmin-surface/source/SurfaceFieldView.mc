using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Attention as Attention;

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

    // Alert-state (voorkom her-trigger; idempotent per stuk, gereset bij route-wissel)
    hidden var lastRouteId = null;
    hidden var alertedSec;   // Boolean per stuk-index (grootte = SurfaceData MAX_SECTIONS)

    function initialize() {
        DataField.initialize();
        alertedSec = new [32];   // MAX_SECTIONS
        for (var i = 0; i < alertedSec.size(); i++) { alertedSec[i] = false; }
    }

    function compute(info) {
        var data = App.getApp().surfaceData;
        if (data == null || !data.payloadReceived) { return; }

        // Een nieuwe payload (route-wissel) maakt de per-stuk alert-state ongeldig.
        var rid = data.routeId;
        var routeChanged = (rid == null) ? (lastRouteId != null) : !rid.equals(lastRouteId);
        if (routeChanged) {
            lastRouteId = rid;
            for (var i = 0; i < alertedSec.size(); i++) { alertedSec[i] = false; }
        }

        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        var pos = null;
        if (info != null && info has :currentLocation && info.currentLocation != null) {
            pos = info.currentLocation.toDegrees();  // [lat, lon] decimal degrees
        }
        var corrected = data.correctElapsed(elapsed, pos);
        data.updateProgress(corrected);

        // Stuk-start-alert: één keer per stuk, binnen 50 m van de stuk-start.
        var ci = data.currentIdx;
        if (ci >= 0 && ci < alertedSec.size() && !alertedSec[ci]) {
            var into = corrected - data.secStart[ci];
            if (into >= 0 && into <= 50) {
                triggerSurfaceAlert();
                alertedSec[ci] = true;
            }
        }
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

    // Title = user name when present, else the surface-type label.
    hidden function titleFor(data, idx) {
        var nm = data.secName[idx];
        if (nm != null && nm.length() > 0) { return nm; }
        return SURF_NAMES[data.secType[idx]];
    }

    hidden function drawCurrentSection(dc, data, w, h) {
        var t = data.secType[data.currentIdx];

        // colour swatch above the section title
        dc.setColor(SURF_COLORS[t], Gfx.COLOR_TRANSPARENT);
        dc.fillRectangle(w / 2 - 50, 6, 100, 8);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_MEDIUM, titleFor(data, data.currentIdx),
            Gfx.TEXT_JUSTIFY_CENTER);
        // Surface type small under the name (only meaningful when a name overrides it).
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 8, Gfx.FONT_XTINY, SURF_NAMES[t],
            Gfx.TEXT_JUSTIFY_CENTER);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        var remLine = "nog " + formatDist(data.remainingInSection);
        if (data.subPieceCount > 0) {
            remLine = remLine + "  ·  deel " + (data.currentSubPiece + 1) + "/" + data.subPieceCount;
        }
        dc.drawText(w / 2, h / 2 + 8, Gfx.FONT_SMALL, remLine, Gfx.TEXT_JUSTIFY_CENTER);

        drawSubPieceBar(dc, data, w, h);

        if (data.nextIdx >= 0) {
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_XTINY,
                "dan: " + titleFor(data, data.nextIdx), Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    // Gevulde segmentbalk: afgereden deel-stukken in de ondergrondkleur, het huidige
    // deel-stuk blauw, komende deel-stukken lichtgrijs. De teller staat op de 'nog ...'-regel erboven.
    hidden function drawSubPieceBar(dc, data, w, h) {
        var n = data.subPieceCount;
        if (n <= 0) { return; }
        var cur = data.currentSubPiece;
        var t = data.secType[data.currentIdx];

        var x0 = 20;
        var barW = w - 40;
        var y = (h * 60) / 100;   // onder de 'nog ...'-regel (h/2+8), boven de 'dan:'-preview (3h/4)
        var barH = 6;
        // n is begrensd door SurfaceData.MAX_SUBPIECES (16), dus cellW >= 1 op het fr255m-scherm
        // en de cellen lopen nooit voorbij de rechtermarge.
        var cellW = barW / n;
        if (cellW < 1) { cellW = 1; }

        for (var i = 0; i < n; i++) {
            var cx = x0 + i * cellW;
            if (i < cur) {
                dc.setColor(SURF_COLORS[t], Gfx.COLOR_TRANSPARENT);   // afgereden
            } else if (i == cur) {
                dc.setColor(Gfx.COLOR_BLUE, Gfx.COLOR_TRANSPARENT);   // huidig
            } else {
                dc.setColor(Gfx.COLOR_LT_GRAY, Gfx.COLOR_TRANSPARENT); // komend
            }
            dc.fillRectangle(cx, y, cellW - 1, barH);
        }
    }

    hidden function drawNextOnly(dc, data, w, h) {
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 4, Gfx.FONT_XTINY, "VOLGENDE", Gfx.TEXT_JUSTIFY_CENTER);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2 - 10, Gfx.FONT_MEDIUM, titleFor(data, data.nextIdx),
            Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w / 2, (h * 3) / 4, Gfx.FONT_SMALL,
            "in " + formatDist(data.distToNext), Gfx.TEXT_JUSTIFY_CENTER);
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }

    // Tril + geluid bij binnenkomst van een ondergrond-stuk (zelfde toon TONE_LAP als de klim-alert;
    // bewust een kortere tril dan triggerClimbAlert omdat ondergrond-stukken vaker voorkomen dan klimmen).
    hidden function triggerSurfaceAlert() {
        if (Attention has :vibrate) {
            var vibePattern = [
                new Attention.VibeProfile(100, 400),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(100, 400)
            ];
            Attention.vibrate(vibePattern);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_LAP);
        }
    }
}
