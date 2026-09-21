using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
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
    // NOTE: index -> gradient-band mapping (0-2%, 2-4%, ... 10%+) is the shared
    // contract from CLAUDE.md and must stay identical between COLORS and
    // DARK_COLORS below. Only swap RGB values here, never reorder bands.
    hidden const COLORS = [
        0x99FF99,  // 0: light green (0-2%)
        0xFFFF00,  // 1: yellow (2-4%)
        0xFFAA00,  // 2: dark yellow/amber (4-6%)
        0xFF5500,  // 3: orange (6-8%)
        0xFF0000,  // 4: dark orange/red (8-10%)
        0xAA0000,  // 5: dark red (10%+)
    ];

    // Low-light "dark theme" palette (issue #81): same band order as COLORS, but
    // darker/lower-saturation RGB values so the MIP display is less blinding and
    // higher-contrast reds/yellows don't wash out night vision. Toggled via the
    // "darkTheme" Connect IQ app setting (see resources/settings/).
    hidden const DARK_COLORS = [
        0x2E4D2E,  // 0: muted dark green (0-2%)
        0x665C00,  // 1: muted dark yellow (2-4%)
        0x664400,  // 2: muted dark amber (4-6%)
        0x662200,  // 3: muted dark orange (6-8%)
        0x660000,  // 4: muted dark red-orange (8-10%)
        0x440000,  // 5: muted deep red (10%+)
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

    // Ghost / summary state
    hidden var lastGhostTimerMs = 0;
    hidden var summaryUntilMs = -1;        // show post-summit summary until this timer value (ms)
    hidden var summaryClimbIndex = -1;     // which climb the summary is for
    hidden var summaryActualSec = 0;
    hidden var summaryDeltaSec = 0;
    hidden var lastRouteId = null;         // detect a new payload (route change) to reset ghost state

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

        // A new payload (route change) invalidates all per-ride ghost/summary state,
        // otherwise a stale climbStartTimerMs or summary index would leak across routes.
        var rid = data.routeId;
        var routeChanged = (rid == null) ? (lastRouteId != null) : !rid.equals(lastRouteId);
        if (routeChanged) {
            lastRouteId = rid;
            lastActiveClimb = -1;
            alertedClimbIndex = -1;
            summaryUntilMs = -1;
            summaryClimbIndex = -1;
            data.climbStartTimerMs = -1;
        }

        var elapsed = 0;
        if (info != null && info has :elapsedDistance && info.elapsedDistance != null) {
            elapsed = info.elapsedDistance.toNumber();
        }
        var timerMs = (info != null && info has :timerTime && info.timerTime != null)
                ? info.timerTime : 0;
        lastGhostTimerMs = timerMs;

        // Current speed for the ETA-to-summit estimate (cheap per-tick read, no extra
        // smoothing beyond what Activity.Info already applies).
        data.currentSpeedMps = (info != null && info has :currentSpeed && info.currentSpeed != null)
                ? info.currentSpeed : 0.0;

        // Navigation-anchored distance: when the route is loaded as a Garmin course,
        // distance-along-course (rtl - distanceToDestination) is a more accurate axis
        // than the raw odometer. chooseAxis() gates it behind a length + calibration
        // trust check, falling back to the odometer otherwise.
        var navDist = -1;
        if (data.routeTotalLen > 0
                && info has :distanceToDestination && info.distanceToDestination != null) {
            navDist = data.routeTotalLen - info.distanceToDestination.toNumber();
            if (navDist < 0) { navDist = 0; }
        }
        var axis = data.chooseAxis(elapsed, navDist);
        data.navDistThisTick = navDist;

        data.updateProgress(axis);

        // GPS↔route coordinate matching every tick: snaps progress on a climb, pre-aligns
        // and watches for divergence from ~1 km before a climb, and sets data.offRoute.
        if (info != null && info has :currentLocation && info.currentLocation != null) {
            var ll = info.currentLocation.toDegrees();   // [lat, lon]
            data.updateRouteMatch(ll[0], ll[1]);
        }

        // Detect leaving a climb (summary) BEFORE overwriting the climb-start timer.
        if (lastActiveClimb >= 0 && data.activeClimbIndex != lastActiveClimb
                && data.climbStartTimerMs >= 0) {
            summaryClimbIndex = lastActiveClimb;
            summaryActualSec = ((timerMs - data.climbStartTimerMs) / 1000.0).toNumber();
            var totalTarget = climbTotalTarget(data, lastActiveClimb);
            summaryDeltaSec = (totalTarget >= 0) ? (summaryActualSec - totalTarget) : 0;
            summaryUntilMs = timerMs + 12000;   // show for 12 s
        }

        // Capture the timer at the start of a newly entered climb.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != lastActiveClimb) {
            data.climbStartTimerMs = timerMs;
        }
        lastActiveClimb = data.activeClimbIndex;

        // Climb-start alert: vibrate when entering a new climb within 50m.
        // Suppressed while off-route so a wrong-turn odometer reading can't fire it.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != alertedClimbIndex
                && !data.offRoute) {
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

        if (summaryUntilMs > 0 && lastGhostTimerMs < summaryUntilMs
                && data.activeClimbIndex < 0 && summaryClimbIndex >= 0) {
            drawClimbSummary(dc, data);
            return;
        }

        if (data.activeClimbIndex >= 0) {
            drawActiveClimb(dc, data);
        } else if (data.nextClimbIndex >= 0) {
            drawNextClimbPreview(dc, data);
        } else {
            drawNoClimbs(dc);
        }

        if (data.offRoute) {
            drawOffRouteBanner(dc);
        }
    }

    // Red banner across the top when the rider has diverged from the route near a climb.
    hidden function drawOffRouteBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_RED);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, "OFF ROUTE", Gfx.TEXT_JUSTIFY_CENTER);
    }

    // =========================================================================
    // Active climb rendering
    // =========================================================================

    // Mirrors drawNextClimbPreview's layout (header → name → centered profile → 3 stats →
    // bottom line) but for the climb in progress: header reads "HUIDIGE KLIM", the profile carries
    // a live progress marker + surface bar, the stats show remaining values, and the bottom line
    // (where the preview shows "in X km") shows the pacing ghost.
    hidden function drawActiveClimb(dc, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var ci = data.activeClimbIndex;

        // Header (same slot as the next-climb page, text swapped)
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "HUIDIGE KLIM", Gfx.TEXT_JUSTIFY_CENTER);

        // Climb name
        var name = data.climbName[ci];
        if (name == null) {
            name = "Climb " + (ci + 1);
        }
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.15).toNumber(), Gfx.FONT_TINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        // Profile (same geometry as the next-climb preview)
        var profileTop = (h * 0.30).toNumber();
        var profileHeight = (h * 0.35).toNumber();
        var profileBottom = profileTop + profileHeight;
        drawProfile(dc, data, ci, 8, profileTop, w - 16, profileHeight);

        // Surface bar: 5px tall, 2px below the profile (skipped if all segments are UNKNOWN)
        drawSurfaceBar(dc, data, ci, 8, profileBottom + 2, w - 16);

        // Progress marker on the profile
        var totalLen = data.climbLength[ci];
        if (totalLen > 0) {
            var progressPct = data.progressInClimb.toFloat() / totalLen.toFloat();
            if (progressPct > 1.0) { progressPct = 1.0; }
            if (progressPct < 0.0) { progressPct = 0.0; }

            var markerX = 8 + ((w - 16) * progressPct).toNumber();
            // Clamp so the 2px marker stays within the profile (right edge = 8 + (w-16) - 2).
            var markerMax = w - 10;
            if (markerX > markerMax) { markerX = markerMax; }

            dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
            dc.fillRectangle(markerX, profileTop, 2, profileHeight);
        }

        // Stats row — same positions as the next-climb page, live values
        var statsY = (h * 0.72).toNumber();

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
        var gradWhole = curGrad / 10;
        var gradFrac = curGrad % 10;
        if (gradFrac < 0) { gradFrac = -gradFrac; }

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(32, statsY, Gfx.FONT_XTINY, formatDist(remaining), Gfx.TEXT_JUSTIFY_LEFT);
        dc.drawText(w / 2, statsY, Gfx.FONT_XTINY, remElev + "m↑", Gfx.TEXT_JUSTIFY_CENTER);
        dc.drawText(w - 32, statsY, Gfx.FONT_XTINY,
            gradWhole + "." + gradFrac + "%", Gfx.TEXT_JUSTIFY_RIGHT);

        // Current segment's gradient-implied VAM (vertical ascent m/h), complementary to the
        // gradient stat above. Data-plumbing only: no new computation happens on the watch, this
        // just renders the avg/peak pair CommListener already parsed into segVamAvg/segVamPeak.
        if (data.hasVam[ci] && data.activeSegmentIndex >= 0
                && data.activeSegmentIndex < data.segCount[ci]) {
            var vamAvg = data.segVamAvg[ci][data.activeSegmentIndex];
            var vamPeak = data.segVamPeak[ci][data.activeSegmentIndex];
            var vamY = (h * 0.80).toNumber();
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, vamY, Gfx.FONT_XTINY,
                "VAM " + vamAvg + "/" + vamPeak, Gfx.TEXT_JUSTIFY_CENTER);
        }

        // Bottom line (where the preview shows "in X km"): live ghost delta when the climb
        // carries a pacing reference, otherwise the ETA to the summit at current speed.
        // Among ghost deltas, the per-segment PR delta ("vs PR") takes priority over the
        // manual pacing-plan delta ("vs plan") — it's the more actionable, always-on signal
        // (repeat-climb comparison). Screen space is too tight on the FR255M to show more
        // than one of these three at once.
        var ghostY = (h * 0.88).toNumber();
        var ghostDrawn = false;
        if (data.climbStartTimerMs >= 0) {
            var actual = (lastGhostTimerMs - data.climbStartTimerMs) / 1000.0;
            if (data.hasRefTargets[ci]) {
                var ref = data.refSecondsAt();
                if (ref >= 0) {
                    drawGhostDelta(dc, w, ghostY, (actual - ref).toNumber(), "vs PR");
                    ghostDrawn = true;
                }
            } else if (data.hasTargets[ci]) {
                var target = data.targetSecondsAt();
                if (target >= 0) {
                    drawGhostDelta(dc, w, ghostY, (actual - target).toNumber(), "vs plan");
                    ghostDrawn = true;
                }
            }
        }
        if (!ghostDrawn) {
            var etaSec = data.etaSeconds(remaining, data.currentSpeedMps);
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, ghostY, Gfx.FONT_XTINY,
                "ETA " + formatEta(etaSec), Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    // + = behind (red), - or 0 = ahead/on pace (green).
    hidden function drawGhostDelta(dc, w, y, deltaSec, suffix) {
        if (deltaSec > 0) {
            dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, y, Gfx.FONT_XTINY, "+" + deltaSec + "s " + suffix, Gfx.TEXT_JUSTIFY_CENTER);
        } else {
            dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, y, Gfx.FONT_XTINY, deltaSec + "s " + suffix, Gfx.TEXT_JUSTIFY_CENTER);
        }
    }

    // =========================================================================
    // Profile drawing (colored bars like ClimbFinder)
    // =========================================================================

    // Reads the "darkTheme" app setting (resources/settings/) and picks the matching
    // gradient-color palette. Wrapped in try/catch: Properties.getValue can throw if
    // the property isn't registered (e.g. a stale/older simulator settings cache),
    // and this must never crash a per-tick redraw -- fall back to the normal palette.
    hidden function activeColors() {
        var dark = false;
        try {
            var v = Properties.getValue("darkTheme");
            dark = (v != null && v == true);
        } catch (e) {
            dark = false;
        }
        return dark ? DARK_COLORS : COLORS;
    }

    hidden function drawProfile(dc, data, ci, x, y, w, h) {

        var colors = activeColors();
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

            dc.setColor(colors[colorIdx], Gfx.COLOR_TRANSPARENT);

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

    // Formats a whole-seconds ETA as "m:ss"; a negative value (speed too low/unknown,
    // see ClimbData.etaSeconds) renders as a placeholder rather than a bogus duration.
    hidden function formatEta(seconds) {
        if (seconds < 0) { return "--:--"; }
        var m = seconds / 60;
        var s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : "" + s);
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

    hidden function climbTotalTarget(data, ci) {
        if (ci < 0 || !data.hasTargets[ci]) { return -1; }
        var sum = 0;
        for (var s = 0; s < data.segCount[ci]; s++) { sum += data.segTargetSec[ci][s]; }
        return sum;
    }

    hidden function drawClimbSummary(dc, data) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var ci = summaryClimbIndex;

        var name = data.climbName[ci];
        if (name == null) { name = "Climb " + (ci + 1); }

        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "KLIM KLAAR", Gfx.TEXT_JUSTIFY_CENTER);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.18).toNumber(), Gfx.FONT_TINY, name, Gfx.TEXT_JUSTIFY_CENTER);

        var mins = summaryActualSec / 60;
        var secs = summaryActualSec % 60;
        if (secs < 0) { secs = -secs; }
        dc.drawText(w / 2, (h * 0.40).toNumber(), Gfx.FONT_NUMBER_MEDIUM,
                mins + ":" + (secs < 10 ? "0" + secs : "" + secs), Gfx.TEXT_JUSTIFY_CENTER);

        dc.drawText(w / 2, (h * 0.62).toNumber(), Gfx.FONT_XTINY,
                data.climbElevGain[ci] + "m↑", Gfx.TEXT_JUSTIFY_CENTER);

        if (data.hasTargets[ci]) {
            var d = summaryDeltaSec;
            if (d > 0) {
                dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), Gfx.FONT_XTINY,
                        "+" + d + "s vs plan", Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), Gfx.FONT_XTINY,
                        d + "s vs plan", Gfx.TEXT_JUSTIFY_CENTER);
            }
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
