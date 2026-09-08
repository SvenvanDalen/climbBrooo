using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Attention as Attention;
using Toybox.System as Sys;

/**
 * Single "5 km terrain window" datafield fed by the ON-WATCH parse: a
 * doorlopend elevation profile from the current position to +5 km (clamped
 * to route end), with detected-climb segments colored on top of the raw
 * terrain line. Terrain that never meets the climb definition (short rises,
 * a dike) is still visible via the profile shape, just not colored. offRoute
 * keeps the last window on screen (progress marker frozen) with a banner
 * overlay instead of going blank. As a datafield, position arrives via
 * compute(info)'s Activity.Info each activity tick (no separate
 * Position.enableLocationEvents registration, and no manual redraw-gating —
 * the system already calls onUpdate at the activity's own refresh cadence).
 */
class OnboardView extends Ui.DataField {

    hidden const COLORS = [
        0x99FF99, 0xFFFF00, 0xFFAA00, 0xFF5500, 0xFF0000, 0xAA0000,
    ];
    hidden const WINDOW_M = 5000.0;

    function initialize() {
        DataField.initialize();
    }

    function compute(info) {
        var data = (App.getApp() as OnboardApp).climbData;
        if (data == null || !data.parsed) {
            return;
        }
        data.computeTicks++;   // TEMP diagnostic, see OnboardClimbData
        if (info == null || info.currentLocation == null) {
            return;
        }
        data.fixTicks++;
        var ll = info.currentLocation.toDegrees();
        data.lastLat = ll[0];
        data.lastLon = ll[1];
        data.updatePosition(ll[0], ll[1]);
        if (data.takeAlert()) {
            if (Attention has :vibrate) {
                Attention.vibrate([new Attention.VibeProfile(50, 1000)]);
            }
            if (Attention has :playTone) {
                Attention.playTone(Attention.TONE_ALERT_HI);
            }
        }
    }

    function onUpdate(dc) {
        var app = App.getApp() as OnboardApp;
        var data = app.climbData;
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        if (data == null || !data.parsed) {
            dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
                        "Geen route", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }
        drawWindow(dc, app.store, data);
    }

    // The one and only live screen: elevation profile from routeProgress to
    // routeProgress + WINDOW_M (clamped to route end), climbs colored on top.
    hidden function drawWindow(dc, store, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.drawText(w / 2, 12, Gfx.FONT_TINY,
                    (store != null && store.routeName != null) ? store.routeName : "Route",
                    Gfx.TEXT_JUSTIFY_CENTER);
        if (store == null || !store.complete || store.pointCount < 2) { return; }

        var total = store.totalLen();
        if (total <= 0) { return; }

        var winStart = data.routeProgress;
        if (winStart < 0) { winStart = 0.0; }
        if (winStart > total) { winStart = total; }
        var winEnd = winStart + WINDOW_M;
        if (winEnd > total) { winEnd = total; }
        var winLen = winEnd - winStart;

        var profX = 10;
        var profW = w - 20;
        var profY = h / 2 - 20;
        var profH = h / 4;
        var baseline = profY + profH;

        if (winLen <= 1.0) {
            dc.drawText(w / 2, baseline + 18, Gfx.FONT_SMALL, "Einde route bereikt",
                        Gfx.TEXT_JUSTIFY_CENTER);
            drawOffRouteBanner(dc, data, w);
            return;
        }

        var startIdx = indexAtDist(store, winStart);
        var endIdx = indexAtDist(store, winEnd);
        var minE = store.ele[startIdx];
        var maxE = store.ele[startIdx];
        for (var i = startIdx; i <= endIdx; i++) {
            if (store.ele[i] < minE) { minE = store.ele[i]; }
            if (store.ele[i] > maxE) { maxE = store.ele[i]; }
        }
        var span = maxE - minE;
        if (span <= 0) { span = 1.0; }

        // Raw terrain outline, one column per pixel — this is what makes a
        // sub-threshold rise (e.g. a dike) visible even though it's never colored.
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        var pi = startIdx;
        for (var px = 0; px < profW; px++) {
            var target = winStart + winLen * px / profW;
            while (pi < endIdx && store.dist[pi + 1] < target) {
                pi++;
            }
            var e = RouteParser.interpEle(store, pi + 1, target);
            var y = baseline - (((e - minE) * profH) / span).toNumber();
            dc.drawLine(profX + px, baseline, profX + px, y);
        }

        // Detected climbs overlapping the window, colored by worst segment.
        for (var c = 0; c < data.climbCount; c++) {
            if (data.climbEndDist[c] <= winStart || data.climbStartDist[c] >= winEnd) {
                continue;
            }
            var cs = clampToWin(data.climbStartDist[c], winStart, winEnd);
            var ce = clampToWin(data.climbEndDist[c], winStart, winEnd);
            var x1 = profX + (((cs - winStart) * profW) / winLen).toNumber();
            var x2 = profX + (((ce - winStart) * profW) / winLen).toNumber();
            var worst = 0;
            for (var s = 0; s < data.segCount[c]; s++) {
                if (data.segColor[c][s] > worst) { worst = data.segColor[c][s]; }
            }
            dc.setColor(COLORS[worst], Gfx.COLOR_TRANSPARENT);
            dc.fillRectangle(x1, baseline + 3, (x2 - x1 > 0) ? x2 - x1 : 1, 4);
        }

        // Position marker
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        var mx = profX + (((data.routeProgress - winStart) * profW) / winLen).toNumber();
        dc.drawLine(mx, profY - 4, mx, baseline + 8);

        var msg;
        if (data.activeClimbIndex >= 0 && data.activeSegmentIndex >= 0) {
            var g10 = data.segGradient[data.activeClimbIndex][data.activeSegmentIndex];
            msg = (g10 / 10) + "." + (g10 % 10).abs() + "%";
        } else if (data.nextClimbIndex >= 0 && data.distToNextClimb >= 0
                && data.climbStartDist[data.nextClimbIndex] < winEnd) {
            msg = "Klim " + (data.nextClimbIndex + 1) + " over " + fmtKm(data.distToNextClimb);
        } else {
            msg = "Komende " + fmtKm(winLen.toNumber());
        }
        dc.drawText(w / 2, baseline + 18, Gfx.FONT_SMALL, msg, Gfx.TEXT_JUSTIFY_CENTER);

        drawOffRouteBanner(dc, data, w);
        drawDebugLine(dc, data, h);
    }

    // TEMP diagnostic for the "screen never updates" report — remove once
    // root cause is confirmed. T=compute() calls with a parsed route,
    // F=of those, calls where info.currentLocation was non-null,
    // P=routeProgress in meters. If T stays 0, compute() isn't firing at
    // all. If T grows but F doesn't, GPS fixes aren't reaching the field.
    // If F grows but P doesn't, updatePosition/bestMatch is rejecting fixes.
    hidden function drawDebugLine(dc, data, h) {
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(4, h - 14, Gfx.FONT_XTINY,
                    "T" + data.computeTicks + " F" + data.fixTicks
                    + " P" + data.routeProgress.toNumber(),
                    Gfx.TEXT_JUSTIFY_LEFT);
    }

    hidden function drawOffRouteBanner(dc, data, w) {
        if (!data.offRoute) { return; }
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_RED);
        dc.fillRectangle(0, 0, w, 20);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_TINY, "OFF ROUTE", Gfx.TEXT_JUSTIFY_CENTER);
    }

    // First store index whose cumulative distance is >= d (linear scan; the
    // window is only recomputed on redraw, not every GPS tick).
    hidden function indexAtDist(store, d) {
        var n = store.pointCount;
        var i = 0;
        while (i < n - 1 && store.dist[i + 1] < d) { i++; }
        return i;
    }

    hidden function clampToWin(d, lo, hi) {
        if (d < lo) { return lo; }
        if (d > hi) { return hi; }
        return d;
    }

    hidden function fmtKm(m) {
        if (m >= 1000) {
            var tenths = ((m % 1000) / 100).toNumber();
            return (m / 1000).toNumber() + "." + tenths + " km";
        }
        return m.toNumber() + " m";
    }
}
