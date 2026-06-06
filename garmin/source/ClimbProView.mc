using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.System as Sys;
using Toybox.Activity as Activity;
using Toybox.Attention as Attention;

/**
 * Main DataField view for ClimbPro.
 * Renders:
 * - When ON a climb: colored elevation profile with progress marker,
 *   remaining distance, remaining elevation, current gradient
 * - When BETWEEN climbs: next climb preview with distance-to-start
 * - When no data: "No data" placeholder
 */
class ClimbProView extends Ui.DataField {

    // Color palette matching protocol/colors.md
    // FR255M is MIP (64-color), so use closest available colors
    hidden const COLORS = [
        0x99FF99,  // 0: light green (0-2%)
        0xFFFF00,  // 1: yellow (2-4%)
        0xFFAA00,  // 2: dark yellow/amber (4-6%)
        0xFF5500,  // 3: orange (6-8%)
        0xFF0000,  // 4: dark orange/red (8-10%)
        0xAA0000,  // 5: dark red (10%+)
    ];

    // Surface type color palette (indices match SurfaceType constants)
    hidden const SURFACE_COLORS = [
        0x404040,  // 0: ASPHALT   — dark grey
        0xC8A050,  // 1: GRAVEL    — sandy yellow
        0x8B4513,  // 2: DIRT      — brown
        0x909090,  // 3: COBBLESTONE — medium grey
        0x9060C0,  // 4: MIXED     — purple
    ];


    // Alert state (prevent re-trigger)
    hidden var alertedClimbIndex = -1;
    hidden var lastActiveClimb = -1;
    hidden var lastActiveSeg = -1;

    function initialize() {
        DataField.initialize();
    }

    /**
     * Called every GPS tick. Update route progress.
     */
    function compute(info) {
        var data = App.getApp().climbData;
        if (data == null || !data.payloadReceived) {
            return;
        }

        // Get elapsed distance from activity info
        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }

        data.updateProgress(elapsed);

        // Climb-start alert: vibrate when entering a new climb within 50m
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != alertedClimbIndex) {
            if (data.progressInClimb <= 50) {
                triggerClimbAlert();
                alertedClimbIndex = data.activeClimbIndex;
            }
        }
    }

    function onUpdate(dc) {
        // Clear background
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        
        var data = App.getApp().climbData;
        if (data == null || !data.payloadReceived) {
            drawNoData(dc);
            return;
        }

        if (data.activeClimbIndex >= 0) {
            drawActiveClimb(dc, data);
        } else if (data.nextClimbIndex >= 0) {
            drawNextClimbPreview(dc, data);
        } else {
            drawNoClimbs(dc);
        }
    }

    // =========================================================================
    // Active climb rendering
    // =========================================================================

    hidden function drawActiveClimb(dc, data) {

        var w = dc.getWidth();
        var h = dc.getHeight();
        var ci = data.activeClimbIndex;

        // =========================
        // SAFE AREA (CRUCIAAL)
        // =========================
        var safeTop = 6;
        var safeBottom = h - 10;

        var topY = safeTop;

        // profiel zone (niet te hoog / niet te laag)
        var profileTop = safeTop + 12;
        var profileBottom = safeBottom - 18;
        var profileHeight = profileBottom - profileTop;

        var statsY = safeBottom;

        // =========================
        // TITLE
        // =========================
        var name = data.climbName[ci];
        if (name == null) {
            name = "Climb " + (ci + 1);
        }

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, topY, Gfx.FONT_XTINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        // =========================
        // PROFILE
        // =========================
        drawProfile(dc, data, ci, 4,
            profileTop.toNumber(),
            w - 8,
            profileHeight.toNumber()
        );

        // Surface bar: 5px tall, 2px below the gradient profile bottom
        drawSurfaceBar(dc, data, ci, 4, profileBottom.toNumber() + 2, w - 8);

        // =========================
        // PROGRESS MARKER
        // =========================
        var totalLen = data.climbLength[ci];
        if (totalLen > 0) {

            var progressPct = data.progressInClimb.toFloat() / totalLen.toFloat();
            if (progressPct > 1.0) { progressPct = 1.0; }

            var markerX = 4 + ((w - 8) * progressPct).toNumber();

            dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
            dc.fillRectangle(markerX - 2, profileTop.toNumber() - 2, 4, 6);
        }

        // =========================
        // STATS (SAFE FIXED POS)
        // =========================

        var remaining = totalLen - data.progressInClimb;
        if (remaining < 0) { remaining = 0; }

        var remElev = 0;
        var cumDist = 0;

        for (var s = 0; s < data.segCount[ci]; s++) {
            cumDist += data.segDist[ci][s];
            if (cumDist > data.progressInClimb) {
                remElev += data.segElevGain[ci][s];
            }
        }

        var curGrad = 0;
        if (data.activeSegmentIndex >= 0 && data.activeSegmentIndex < data.segCount[ci]) {
            curGrad = data.segGradient[ci][data.activeSegmentIndex];
        }

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);

        dc.drawText(32  , statsY,
            Gfx.FONT_XTINY,
            formatDist(remaining),
            Gfx.TEXT_JUSTIFY_LEFT
        );

        dc.drawText(w / 2, statsY,
            Gfx.FONT_XTINY,
            remElev + "m↑",
            Gfx.TEXT_JUSTIFY_CENTER
        );

        var gradWhole = curGrad / 10;
        var gradFrac = curGrad % 10;
        if (gradFrac < 0) { gradFrac = -gradFrac; }

        dc.drawText(w - 32, statsY,
            Gfx.FONT_XTINY,
            gradWhole + "." + gradFrac + "%",
            Gfx.TEXT_JUSTIFY_RIGHT
        );
    }

    // =========================================================================
    // Profile drawing (colored bars like ClimbFinder)
    // =========================================================================

    hidden function drawProfile(dc, data, ci, x, y, w, h) {

        var totalLen = data.climbLength[ci];
        if (totalLen <= 0) { return; }

        var totalElev = data.climbElevGain[ci];
        if (totalElev <= 0) { totalElev = 1; }

        var segCount = data.segCount[ci];
        if (segCount <= 0) { return; }

        // =========================
        // SAFE PROFILE AREA
        // =========================
        var safeBottom = y + h - 3;
        var baseline = safeBottom;

        var stepW = w.toFloat() / segCount.toFloat();
        var cumElev = 0;

        for (var s = 0; s < segCount; s++) {

            var segE = data.segElevGain[ci][s];
            var colorIdx = data.segColor[ci][s];

            if (colorIdx < 0) { colorIdx = 0; }
            if (colorIdx > 5) { colorIdx = 5; }

            var x1 = x + (s * stepW);
            var x2 = x + ((s + 1) * stepW);

            if (x2 <= x1) { x2 = x1 + 1; }

            var startElev = cumElev;
            var endElev = cumElev + segE;

            var y1 = baseline - ((startElev * h) / totalElev);
            var y2 = baseline - ((endElev * h) / totalElev);

            dc.setColor(COLORS[colorIdx], Gfx.COLOR_TRANSPARENT);

            var width = x2 - x1;

            for (var px = 0; px < width; px++) {

                var interp = px.toFloat() / width.toFloat();
                var py = y1 + ((y2 - y1) * interp).toNumber();

                // =========================
                // HARD SAFE CLIP FIX
                // =========================
                if (py < y) { py = y; }
                if (py > baseline) { py = baseline; }

                dc.fillRectangle(
                    x1 + px,
                    py,
                    1,
                    baseline - py
                );
            }

            dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
            dc.drawLine(x1, y1, x2, y2);

            cumElev += segE;
        }

        // baseline zichtbaar houden binnen circle
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawLine(x, baseline, x + w, baseline);
    }

    // =========================================================================
    // Next climb preview
    // =========================================================================

    hidden function drawNextClimbPreview(dc, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var ni = data.nextClimbIndex;

        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "NEXT CLIMB", Gfx.TEXT_JUSTIFY_CENTER);

        // Climb name
        var name = data.climbName[ni];
        if (name == null) {
            name = "Climb " + (ni + 1);
        }
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.15).toNumber(), Gfx.FONT_TINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        // Mini profile
        var profileTop = (h * 0.30).toNumber();
        var profileHeight = (h * 0.35).toNumber();
        drawProfile(dc, data, ni, 8, profileTop, w - 16, profileHeight);

        // Stats
        var statsY = (h * 0.72).toNumber();
        var length = data.climbLength[ni];
        var elev = data.climbElevGain[ni];
        var grad = data.climbAvgGrad[ni];

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(32, statsY, Gfx.FONT_XTINY, formatDist(length), Gfx.TEXT_JUSTIFY_LEFT);
        dc.drawText(w / 2, statsY, Gfx.FONT_XTINY, elev + "hm", Gfx.TEXT_JUSTIFY_CENTER);
        var nGrad = grad / 10;
        var nGradF = grad % 10;
        dc.drawText(w - 32, statsY, Gfx.FONT_XTINY,
            nGrad + "." + nGradF + "%", Gfx.TEXT_JUSTIFY_RIGHT);

        // Distance to climb
        if (data.distToNextClimb >= 0) {
            var distY = (h * 0.88).toNumber();
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, distY, Gfx.FONT_XTINY,
                "in " + formatDist(data.distToNextClimb), Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    hidden function drawNoData(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_MEDIUM,
            "No data", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }

    hidden function drawNoClimbs(dc) {
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
            "No climbs ahead", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }

    hidden function formatDist(meters) {
        if (meters >= 1000) {
            var km = meters / 1000;
            var hm = (meters % 1000) / 100;
            return km + "." + hm + "km";
        }
        return meters + "m";
    }

    hidden function triggerClimbAlert() {
        if (Attention has :vibrate) {
            var vibePattern = [
                new Attention.VibeProfile(100, 500),
                new Attention.VibeProfile(0, 200),
                new Attention.VibeProfile(100, 500)
            ];
            Attention.vibrate(vibePattern);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_LAP);
        }
    }

    hidden function surfaceColor(surfType) {
        if (surfType >= 0 && surfType < SURFACE_COLORS.size()) {
            return SURFACE_COLORS[surfType];
        }
        return -1; // UNKNOWN — caller checks for -1 to skip drawing
    }

    // Draws a 5px-tall bar directly below the gradient profile.
    // barX/barY: top-left corner of the bar (barY = profile bottom + 2).
    // barWidth: same pixel width as the gradient profile.
    // Skips drawing entirely if all segments are UNKNOWN (surfType == 5).
    hidden function drawSurfaceBar(dc, data, ci, barX, barY, barWidth) {
        var segCnt = data.segCount[ci];
        if (segCnt <= 0) { return; }

        var allUnknown = true;
        for (var s = 0; s < segCnt; s++) {
            if (data.segSurf[ci][s] != 5) { allUnknown = false; break; }
        }
        if (allUnknown) { return; }

        var segW = barWidth / segCnt;
        if (segW < 1) { segW = 1; }

        for (var s = 0; s < segCnt; s++) {
            var color = surfaceColor(data.segSurf[ci][s]);
            if (color == -1) { continue; } // UNKNOWN — leave transparent
            dc.setColor(color, Gfx.COLOR_TRANSPARENT);
            var x = barX + s * segW;
            var w = (s == segCnt - 1) ? (barX + barWidth - x) : segW; // fill remainder on last
            dc.fillRectangle(x, barY, w, 5);
        }
    }
}
