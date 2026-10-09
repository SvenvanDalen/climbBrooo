using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.System as Sys;
using Toybox.Activity as Activity;
using Toybox.Attention as Attention;
using Toybox.Sensor as Sensor;
using Toybox.Time as Time;
using Toybox.Weather as Weather;

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

    // Colorblind-friendly palette (issue #258, protocol/colors.md), selected by the phone
    // via payload "pal" = 1: pale yellow, then a light-to-dark blue ramp. Blue-yellow axis
    // only (kept by deutan/protan vision) with strictly decreasing lightness. Same band
    // order as COLORS; values are MIP colors and must match GradientPalette.java verbatim.
    hidden const CVD_COLORS = [
        0xFFFFAA,  // 0: pale yellow (0-2%)
        0x55FFFF,  // 1: light cyan (2-4%)
        0x55AAFF,  // 2: sky blue (4-6%)
        0x0055FF,  // 3: blue (6-8%)
        0x0000FF,  // 4: pure blue (8-10%)
        0x0000AA,  // 5: navy (10%+)
    ];
    const PALETTE_COLORBLIND = 1;
    // Status colors in the colorblind palette: blue = good/ahead, orange = bad/behind.
    const CVD_OK_COLOR = 0x00AAFF;
    const CVD_BAD_COLOR = 0xFF5500;

    // "colorMode" setting value for FTP intensity-zone colors (issue #66); 0 = gradient.
    const COLOR_MODE_ZONES = 1;

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
    hidden var batteryWarnedClimbIndex = -1;
    hidden var blockDoneClimbIndex = -1;   // interval block "klaar" already signalled (issue #180)

    // Ghost / summary state
    hidden var lastGhostTimerMs = 0;
    hidden var summaryUntilMs = -1;        // show post-summit summary until this timer value (ms)
    hidden var summaryClimbIndex = -1;     // which climb the summary is for
    hidden var summaryActualSec = 0;
    hidden var summaryDeltaSec = 0;
    hidden var lastRouteId = null;         // detect a new payload (route change) to reset ghost state

    // Felt temperature on descents (issue #248); null = nothing shown.
    hidden var descent = new DescentTracker();
    hidden var feltShownC = null;

    // Tunnels / technical descents ahead (issue #203).
    hidden var hazardIdx = -1;             // hazard the banner is about; -1 = none
    hidden var hazardPosM = 0;             // route axis (m) hazardIdx was looked up at
    hidden var hazardAlerted = 0;          // bitmask of hazards already alerted this route
    // Everesting attempt (issue #217); everestAscentM = last total ascent for the banner.
    hidden var everest = new EverestTracker();
    hidden var everestAscentM = null;
    // Lights reminder at dusk (issue #198): once per ride, computed on the watch.
    hidden var lights = new LightsReminder();
    // "Vlakker stuk" notice during a climb (issue #213).
    hidden var easier = new EasierAheadTracker();
    // Heart-rate alarm (issue #228). hrShownBpm = banner value while above the limit;
    // hrIrregularUntilMs = show the "onregelmatig" banner until this System timer value.
    hidden var hrLimit = new HrLimitAlarm();
    hidden var hrIrregular = new HrIrregularDetector();
    hidden var hrShownBpm = null;
    hidden var hrIrregularUntilMs = -1;
    // Heat-index warning (issue #227): checked once a minute; heatShownC = banner value
    // while the alarm is latched hot, else null.
    hidden var heatAlarm = new HeatAlarm();
    hidden var heatNextCheckMs = 0;
    hidden var heatShownC = null;
    // Eat/drink reminder (issue #184): time/ascent triggers, shortened when it is hot.
    // fuelTempC is refreshed once a minute; the banner shows until fuelBannerUntilMs.
    hidden var fuel = new FuelReminder();
    hidden var fuelTempC = null;
    hidden var fuelNextTempMs = 0;
    hidden var fuelBannerUntilMs = -1;
    // Cadence coach (issue #179): cadShownRpm = banner value while out of the target band
    // after a nudge, else null.
    hidden var cadenceCoach = new CadenceCoach();
    hidden var cadShownRpm = null;
    // Pacing alert (issue #6): too fast against the plan/PR for the distance covered.
    hidden var pacing = new PacingAlert();
    // Post-summit buzz (issue #8): progress on the active climb as of the previous tick.
    hidden var lastProgressInClimb = 0;
    // "Nieuwe klim!" banner (issue #27) until this timer value (ms); -1 = none.
    hidden var newClimbUntilMs = -1;

    function initialize() {
        DataField.initialize();
    }

    /**
     * Called every GPS tick. Update route progress.
     */
    function compute(info) {
        // Lights reminder (issue #198) runs before the payload check: it needs only the GPS
        // fix and the clock, so it also works without any route on the watch.
        checkLightsReminder(info);
        // Safety alarms run before the payload gate: they don't need a route.
        checkHeartRate(info);
        checkHeatIndex();
        // Eat/drink reminder (issue #184): needs only the activity timer, so no route either.
        checkFuelReminder(info);
        checkCadence(info);

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
            batteryWarnedClimbIndex = -1;
            blockDoneClimbIndex = -1;
            data.batteryWarningActive = false;
            summaryUntilMs = -1;
            summaryClimbIndex = -1;
            data.climbStartTimerMs = -1;
            hazardAlerted = 0;
            hazardIdx = -1;
            easier.reset();
            pacing.reset();
            newClimbUntilMs = -1;
            lastProgressInClimb = 0;
            data.resetRouteGhostAnchor();
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
        // Current power for the interval-block band (issue #180); null without a power meter.
        data.currentPower = (info != null && info has :currentPower && info.currentPower != null)
                ? info.currentPower : null;
        data.currentHeartRate = (info != null && info has :currentHeartRate && info.currentHeartRate != null)
                ? info.currentHeartRate : null;
        data.currentCadence = (info != null && info has :currentCadence && info.currentCadence != null)
                ? info.currentCadence : null;

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
            // Radius mode / day trip (issues #7, #9): count down to the next climb start.
            data.updateRadius(ll[0], ll[1]);
        }

        // Virtual opponent on the route (issue #178): the best earlier ride, interpolated at
        // the route progress. Computed every tick so it starts alongside the rider even
        // while the page doesn't show it.
        data.routeGhostDeltaSec = data.routeGhostDelta(timerMs, axis);

        // Tunnels / technical descents (issue #203): banner from HAZARD_LOOKAHEAD_M ahead and
        // while inside, one buzz per hazard (bitmask latch, survives GPS jitter around the
        // look-ahead edge). Suppressed off-route, where the route axis is meaningless.
        hazardIdx = (data.offRoute || data.mode == null || !data.mode.equals("route")) ? -1
                : hazardAt(data.hazards, axis, HAZARD_LOOKAHEAD_M);
        hazardPosM = axis;
        if (hazardIdx >= 0 && (hazardAlerted & (1 << hazardIdx)) == 0) {
            hazardAlerted |= (1 << hazardIdx);
            triggerHazardAlert();
        }

        // Detect leaving a climb (summary) BEFORE overwriting the climb-start timer.
        if (lastActiveClimb >= 0 && data.activeClimbIndex != lastActiveClimb
                && data.climbStartTimerMs >= 0) {
            summaryClimbIndex = lastActiveClimb;
            summaryActualSec = ((timerMs - data.climbStartTimerMs) / 1000.0).toNumber();
            var totalTarget = climbTotalTarget(data, lastActiveClimb);
            summaryDeltaSec = (totalTarget >= 0) ? (summaryActualSec - totalTarget) : 0;
            summaryUntilMs = timerMs + 12000;   // show for 12 s
            // Issue #8: one short buzz when the climb was really topped out (not abandoned).
            // Fires on this one transition only, so it can't repeat for the same climb.
            if (!data.offRoute && summitReached(lastProgressInClimb, data.climbLength[lastActiveClimb])
                    && readBoolSettingDefault("summitAlert", true)) {
                triggerSummitAlert();
            }
        }

        // Interval block done (issue #180): short vibration once the rider tops out a climb
        // that carries a block. The block itself starts with the climb-start alert below.
        // Latched per climb, like the start alert, so it can't re-fire.
        if (lastActiveClimb >= 0 && data.activeClimbIndex != lastActiveClimb
                && lastActiveClimb != blockDoneClimbIndex
                && data.blockFinished(lastActiveClimb, axis)) {
            triggerBlockDoneAlert();
            blockDoneClimbIndex = lastActiveClimb;
        }

        // Capture the timer at the start of a newly entered climb.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != lastActiveClimb) {
            data.climbStartTimerMs = timerMs;
        }
        lastActiveClimb = data.activeClimbIndex;
        lastProgressInClimb = data.activeClimbIndex >= 0 ? data.progressInClimb : 0;

        // Climb-start alert: vibrate when entering a new climb within 50m.
        // Suppressed while off-route so a wrong-turn odometer reading can't fire it.
        if (data.activeClimbIndex >= 0 && data.activeClimbIndex != alertedClimbIndex
                && !data.offRoute) {
            if (data.progressInClimb <= 50) {
                triggerClimbAlert(data, data.activeClimbIndex);
                alertedClimbIndex = data.activeClimbIndex;
                // Issue #27: a climb the rider never rode before gets a short banner.
                newClimbUntilMs = data.climbNew[data.activeClimbIndex] ? timerMs + 10000 : -1;
            }
        }
        if (newClimbUntilMs >= 0 && (timerMs >= newClimbUntilMs || data.activeClimbIndex < 0)) {
            newClimbUntilMs = -1;
        }

        // Pacing alert (issue #6): plan ("tsec") first, else the PR ("refsec"). Needs a
        // running timer since the climb start; suppressed off-route.
        var pci = data.activeClimbIndex;
        var pRef = -1;
        var pActual = 0;
        if (pci >= 0 && !data.offRoute && data.climbStartTimerMs >= 0
                && timerMs > data.climbStartTimerMs) {
            pActual = (timerMs - data.climbStartTimerMs) / 1000.0;
            pRef = data.hasTargets[pci] ? data.targetSecondsAt() : data.refSecondsAt();
        }
        if (pacing.update(pci, pActual, pRef, data.progressInClimb,
                readBoolSettingDefault("pacingAlert", true), timerMs)) {
            triggerPacingAlert();
        }

        // Battery-vs-remaining-climb-time warning (issue #49): once per climb, mirroring
        // the climb-start alert's idempotency pattern above -- keep re-checking every
        // tick until the condition is actually met, then latch it so GPS/pace jitter
        // around the threshold can't re-fire it for the same climb.
        if (data.activeClimbIndex < 0) {
            data.batteryWarningActive = false;
        } else if (data.activeClimbIndex != batteryWarnedClimbIndex) {
            var ci2 = data.activeClimbIndex;
            var remainingClimb = data.climbLength[ci2] - data.progressInClimb;
            if (remainingClimb < 0) { remainingClimb = 0; }
            var climbEtaSec = data.etaSeconds(remainingClimb, data.currentSpeedMps);
            var batteryPct = Sys.getSystemStats().battery;
            if (data.batteryInsufficientForClimb(climbEtaSec, batteryPct)) {
                triggerBatteryAlert();
                data.batteryWarningActive = true;
                batteryWarnedClimbIndex = ci2;
            }
        }

        // Everesting (issue #217): count summit passes and watch the total ascent. The plan
        // comes from the payload; the same plan on a resync keeps the count.
        everest.setPlan(data.everest);
        if (everest.active()) {
            everestAscentM = (info != null && info has :totalAscent) ? info.totalAscent : null;
            var eLat = null;
            var eLon = null;
            if (info != null && info has :currentLocation && info.currentLocation != null) {
                var ell = info.currentLocation.toDegrees();
                eLat = ell[0];
                eLon = ell[1];
            }
            var ev = everest.update(eLat, eLon, everestAscentM);
            if (ev == EVEREST_DONE) {
                triggerEverestDoneAlert();
            } else if (ev == EVEREST_REPEAT) {
                triggerEverestRepeatAlert();
            }
        }

        // Easier stretch ahead (issue #213): banner + one short double buzz, latched per
        // stretch. Candidate is only recomputed when the active segment changes.
        var eci = data.activeClimbIndex;
        if (easier.update(eci, data.activeSegmentIndex, data.progressInClimb,
                eci >= 0 ? data.segDist[eci] : null, eci >= 0 ? data.segGradient[eci] : null,
                eci >= 0 ? data.segCount[eci] : 0, data.offRoute, easierAheadEnabled())) {
            triggerEasierAheadAlert();
        }

        // Felt temperature on descents (issue #248): grade over >= 150 m from odometer +
        // altitude, riding speed as wind speed. Watch-only; nothing is shown on a climb or
        // without a temperature reading.
        var rawDist = (info != null && info has :elapsedDistance) ? info.elapsedDistance : null;
        var alt = (info != null && info has :altitude) ? info.altitude : null;
        var spd = (info != null && info has :currentSpeed) ? info.currentSpeed : null;
        var desc = descent.update(rawDist, alt, spd);
        feltShownC = feltTempToShow(desc, data.activeClimbIndex >= 0,
                desc ? ambientTempC() : null, spd, feltShownC);
    }

    // Sun times come from the current GPS fix + clock (LightsReminder.mc); the sun maths
    // runs at most once a minute and the alert fires once per ride.
    hidden function checkLightsReminder(info) {
        var lat = null;
        var lon = null;
        if (info != null && info has :currentLocation && info.currentLocation != null) {
            var ll = info.currentLocation.toDegrees();
            lat = ll[0];
            lon = ll[1];
        }
        if (lights.update(Time.now().value(), lat, lon, lightsLeadMin(), lightsReminderEnabled())) {
            triggerLightsAlert();
        }
    }

    // Reads "lightsReminder" defensively like the other settings; default on.
    hidden function lightsReminderEnabled() {
        try {
            var v = Properties.getValue("lightsReminder");
            return v == null || v == true;
        } catch (e) {
            return true;
        }
    }

    // Minutes before sunset at which the reminder fires ("lightsLeadMin"); default 15.
    hidden function lightsLeadMin() {
        try {
            var v = Properties.getValue("lightsLeadMin");
            if (v != null && v instanceof Toybox.Lang.Number && v >= 0) { return v; }
        } catch (e) {
            return 15;
        }
        return 15;
    }

    // Heart-rate alarm (issue #228): limit alarm (sustained > hrAlarmBpm) and, opt-in,
    // the irregular-jumps detector. Cheap per-tick work on one number.
    hidden function checkHeartRate(info) {
        var hr = (info != null && info has :currentHeartRate) ? info.currentHeartRate : null;
        var now = Sys.getTimer();
        if (hrLimit.update(hr, readNumberSetting("hrAlarmBpm"), now)) {
            triggerHeartRateAlert();
        }
        hrShownBpm = (hrLimit.high && hr != null) ? hr : null;
        if (readBoolSetting("hrIrregularAlarm")) {
            if (hrIrregular.update(hr, now)) {
                triggerHeartRateAlert();
                hrIrregularUntilMs = now + 30000;
            }
        }
        if (hrIrregularUntilMs >= 0 && now >= hrIrregularUntilMs) {
            hrIrregularUntilMs = -1;
        }
    }

    // Cadence coach (issue #179): nudge when the cadence stays outside the target band
    // ("cadenceLow".."cadenceHigh" rpm, master toggle "cadenceCoach"). Coasting (0) and a
    // missing sensor are ignored inside CadenceCoach. Works without a route.
    hidden function checkCadence(info) {
        var cad = (info != null && info has :currentCadence) ? info.currentCadence : null;
        var dir = cadenceCoach.update(cad, readNumberSetting("cadenceLow"),
                readNumberSetting("cadenceHigh"), readBoolSetting("cadenceCoach"), Sys.getTimer());
        if (dir != CADENCE_OK) {
            triggerCadenceAlert(dir);
        }
        cadShownRpm = (cadenceCoach.shownDir != CADENCE_OK && cad != null) ? cad : null;
    }

    // App-setting reads, defensive like activeColors() reads "darkTheme":
    // Properties.getValue can throw on a stale settings cache. Default 0 / false.
    hidden function readNumberSetting(key) {
        try {
            var v = Properties.getValue(key);
            if (v instanceof Number) { return v; }
        } catch (e) {
            return 0;
        }
        return 0;
    }

    // Heat-index warning (issue #227). Once a minute (the inputs change slowly): heat index
    // from the air temperature + humidity, alarm latched with hysteresis in HeatAlarm.
    hidden function checkHeatIndex() {
        var now = Sys.getTimer();
        if (now < heatNextCheckMs) { return; }
        heatNextCheckMs = now + 60000;
        var threshold = heatIndexThresholdC();
        var hi = null;
        if (threshold > 0) {
            var r = heatReading();
            if (r != null) { hi = heatIndexC(r[0], r[1]); }
        }
        if (heatAlarm.update(hi, threshold, now)) {
            triggerHeatAlert();
        }
        heatShownC = heatAlarm.hot ? hi : null;
    }

    // Eat/drink reminder (issue #184). Cheap per-tick compare on the activity timer + total
    // ascent; the temperature (for the hot-weather correction) is read once a minute.
    hidden function checkFuelReminder(info) {
        var now = Sys.getTimer();
        var hotC = readNumberSetting("fuelHotC");
        if (hotC <= 0) {
            fuelTempC = null;
        } else if (now >= fuelNextTempMs) {
            fuelNextTempMs = now + 60000;
            var r = heatReading();
            fuelTempC = (r == null) ? null : r[0];
        }
        var timerMs = (info != null && info has :timerTime) ? info.timerTime : null;
        var ascent = (info != null && info has :totalAscent) ? info.totalAscent : null;
        if (fuel.update(timerMs, ascent, fuelTempC, readNumberSetting("fuelIntervalMin"),
                readNumberSetting("fuelClimbM"), hotC)) {
            triggerFuelAlert();
            fuelBannerUntilMs = now + 30000;
        }
        if (fuelBannerUntilMs >= 0 && now >= fuelBannerUntilMs) {
            fuelBannerUntilMs = -1;
        }
    }

    // [tempC, humidityPct] for the heat index, or null. Garmin Weather (the phone's
    // current conditions, cached on the watch) comes first: it is outdoor air with a real
    // humidity, whereas the watch's internal sensor reads several degrees high from wrist
    // heat and would false-alarm on every warm day. Without weather data the Sensor
    // temperature (a paired Tempe, else the wrist sensor) is used with humidity unknown.
    hidden function heatReading() {
        if (Toybox has :Weather) {
            try {
                var cc = Weather.getCurrentConditions();
                if (cc != null && cc.temperature != null) {
                    var rh = (cc has :relativeHumidity) ? cc.relativeHumidity : null;
                    return [cc.temperature, rh];
                }
            } catch (e) {
                // fall through to the sensor
            }
        }
        var t = ambientTempC();
        return (t == null) ? null : [t, null];
    }

    // "heatIndexThreshold" app setting in °C; 0 = off. Read defensively like "darkTheme".
    hidden function heatIndexThresholdC() {
        try {
            var v = Properties.getValue("heatIndexThreshold");
            if (v instanceof Number) { return v; }
        } catch (e) {
            return 0;
        }
        return 0;
    }

    hidden function readBoolSetting(key) {
        try {
            var v = Properties.getValue(key);
            return v != null && v == true;
        } catch (e) {
            return false;
        }
    }

    // Temperature from Sensor.Info: a paired Tempe sensor gives true ambient air; without
    // one the FR255M reports its internal (wrist-warmed) sensor, which reads high, so the
    // felt value is then an upper bound. null when no reading is available.
    hidden function ambientTempC() {
        if (!(Toybox has :Sensor)) { return null; }
        try {
            var si = Sensor.getInfo();
            if (si != null && si has :temperature) { return si.temperature; }
        } catch (e) {
            return null;
        }
        return null;
    }

    function onUpdate(dc) {
        // Clear background
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_WHITE);
        dc.clear();
        
        var data = App.getApp().climbData;
        var lightsBanner = lights.bannerVisible(Time.now().value());
        if (data == null || !data.payloadReceived) {
            drawNoData(dc);
            if (fuelBannerUntilMs >= 0) {
                drawFuelBanner(dc);
            } else if (lightsBanner) {
                drawLightsBanner(dc);
            }
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
            drawNoClimbs(dc, data);
        }

        // Off-route takes priority: it's the more urgent/actionable state and the two
        // banners would otherwise fight for the same top strip of a very small screen.
        if (data.offRoute) {
            drawOffRouteBanner(dc);
        } else if (hrShownBpm != null) {
            drawHeartRateBanner(dc, hrHighLabel(hrShownBpm));
        } else if (hrIrregularUntilMs >= 0) {
            drawHeartRateBanner(dc, hrIrregularLabel());
        } else if (data.batteryWarningActive) {
            drawBatteryWarningBanner(dc);
        } else if (hazardIdx >= 0
                && hazardLabel(data.hazards, hazardIdx, hazardPosM, data.units) != null) {
            drawHazardBanner(dc, data);
        } else if (heatShownC != null) {
            drawHeatBanner(dc, data, heatShownC);
        } else if (fuelBannerUntilMs >= 0) {
            drawFuelBanner(dc);
        } else if (cadShownRpm != null) {
            drawCadenceBanner(dc, cadenceLabel(cadenceCoach.shownDir, cadShownRpm));
        } else if (pacing.shownAheadSec != null && data.activeClimbIndex >= 0) {
            drawTopBanner(dc, Gfx.COLOR_ORANGE, pacingAlertLabel(pacing.shownAheadSec));
        } else if (newClimbUntilMs >= 0 && data.activeClimbIndex >= 0) {
            drawTopBanner(dc, Gfx.COLOR_BLUE, "Nieuwe klim!");
        } else if (easier.shownLenM != null && data.activeClimbIndex >= 0) {
            drawEasierAheadBanner(dc, easier.shownLenM);
        } else if (lightsBanner) {
            drawLightsBanner(dc);
        } else if (feltShownC != null && data.activeClimbIndex < 0) {
            drawFeltTempBanner(dc, data, feltShownC);
        } else if (everest.active()) {
            drawEverestBanner(dc);
        }
    }

    // Purple strip for a tunnel / technical descent ahead or under the rider (issue #203).
    hidden function drawHazardBanner(dc, data) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_PURPLE, Gfx.COLOR_PURPLE);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY,
                hazardLabel(data.hazards, hazardIdx, hazardPosM, data.units),
                Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Yellow strip for 30 s after the dusk reminder fired (issue #198).
    hidden function drawLightsBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_YELLOW, Gfx.COLOR_YELLOW);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, lightsReminderLabel(), Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Plain coloured strip across the top with white text (pacing #6, new climb #27).
    hidden function drawTopBanner(dc, color, text) {
        var w = dc.getWidth();
        dc.setColor(color, color);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, text, Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Green strip in the header slot while an easier stretch is coming up (issue #213).
    hidden function drawEasierAheadBanner(dc, lenM) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_DK_GREEN, Gfx.COLOR_DK_GREEN);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, easierAheadLabel(lenM), Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Blue strip in the header slot between climbs while descending (issue #248). The
    // value is latched to whole degrees (latchFeltTemp) so it only changes on a >= 1 °C move.
    hidden function drawFeltTempBanner(dc, data, c) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_BLUE, Gfx.COLOR_BLUE);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, feltTempLabel(c, data.units), Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Dark-green strip with the Everesting progress (issue #217): repeats done/planned and
    // total ascent/target. Lowest priority -- it is always on during an attempt, so every
    // warning banner may cover it.
    hidden function drawEverestBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_DK_GREEN, Gfx.COLOR_DK_GREEN);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY,
                everestLabel(everest.repeats, everest.plannedRepeats(), everestAscentM,
                        everest.targetM()),
                Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Purple strip for the heart-rate alarm (issue #228), on and between climbs. Right
    // below off-route in priority: it is a safety signal, battery/temperature can wait.
    hidden function drawHeartRateBanner(dc, text) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_PURPLE, Gfx.COLOR_PURPLE);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, text, Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Dark-orange strip while the heat-index alarm is latched hot (issue #227), on and
    // between climbs. Below off-route and battery in priority.
    hidden function drawHeatBanner(dc, data, hiC) {
        var w = dc.getWidth();
        dc.setColor(0xAA0000, 0xAA0000);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, heatLabel(hiC, data.units), Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Green strip for 30 s after the eat/drink reminder fired (issue #184), on and between
    // climbs. Below the safety banners (off-route, heart rate, battery, hazard, heat).
    hidden function drawFuelBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_GREEN, Gfx.COLOR_GREEN);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, fuelReminderLabel(), Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Dark-blue strip while the cadence is still outside the target band after a cadence-
    // coach nudge (issue #179). A coaching hint: below the safety/heat banners in priority.
    hidden function drawCadenceBanner(dc, text) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_DK_BLUE, Gfx.COLOR_DK_BLUE);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, text, Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Red banner across the top when the rider has diverged from the route near a climb.
    hidden function drawOffRouteBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_RED);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, "OFF ROUTE", Gfx.TEXT_JUSTIFY_CENTER);
    }

    // Orange banner across the top: battery may not last the rest of this climb
    // (see ClimbData.batteryInsufficientForClimb). Persists for the rest of the climb
    // once triggered -- the underlying condition (low battery) doesn't resolve itself.
    hidden function drawBatteryWarningBanner(dc) {
        var w = dc.getWidth();
        dc.setColor(Gfx.COLOR_ORANGE, Gfx.COLOR_ORANGE);
        dc.fillRectangle(0, 0, w, 16);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 1, Gfx.FONT_XTINY, "LOW BATTERY", Gfx.TEXT_JUSTIFY_CENTER);
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
        var large = largeTextModeActive();

        // Header (same slot as the next-climb page, text swapped)
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "HUIDIGE KLIM", Gfx.TEXT_JUSTIFY_CENTER);

        // Climb name
        var name = data.climbName[ci];
        if (name == null) {
            name = "Climb " + (ci + 1);
        }
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.15).toNumber(), nameFont(large), name, Gfx.TEXT_JUSTIFY_CENTER);

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
        // Values every slot may need, gathered once per redraw for FieldLayout.metricText.
        var vals = {
            :remaining => remaining, :remElev => remElev, :curGrad => curGrad,
            :avgGrad => data.climbAvgGrad[ci],
            :etaSec => data.etaSeconds(remaining, data.currentSpeedMps),
            :hasVam => false, :vamAvg => 0, :vamPeak => 0,
            :speedMps => data.currentSpeedMps, :hr => data.currentHeartRate,
            :power => data.currentPower, :cadence => data.currentCadence,
            :timerMs => lastGhostTimerMs, :units => data.units,
            :rideRemElev => data.rideRemainingElev(),
            :cadTarget => data.cadenceTargetAt(ci, data.activeSegmentIndex)
        };
        if (data.hasVam[ci] && data.activeSegmentIndex >= 0
                && data.activeSegmentIndex < data.segCount[ci]) {
            vals[:hasVam] = true;
            vals[:vamAvg] = data.segVamAvg[ci][data.activeSegmentIndex];
            vals[:vamPeak] = data.segVamPeak[ci][data.activeSegmentIndex];
        }

        // Five phone-chosen slots ('lay'); the default layout is the pre-layout screen.
        // Large-text mode (#82) drops the middle slot and row 4 so the rest can be bigger,
        // except an interval block in row 4: that is the rider's training target (#180).
        var lay = data.layout;
        var sf = statFont(large);
        drawSlot(dc, data, ci, lay[0], 32, statsY, sf, Gfx.TEXT_JUSTIFY_LEFT, false,
            Gfx.COLOR_BLACK, vals);
        if (showSecondaryStat(large)) {
            drawSlot(dc, data, ci, lay[1], w / 2, statsY, sf, Gfx.TEXT_JUSTIFY_CENTER, true,
                Gfx.COLOR_BLACK, vals);
        }
        drawSlot(dc, data, ci, lay[2], w - 32, statsY, sf, Gfx.TEXT_JUSTIFY_RIGHT, false,
            Gfx.COLOR_BLACK, vals);
        if (showSecondaryStat(large) || slotShowsBlock(lay[3], data.hasBlock[ci])) {
            drawSlot(dc, data, ci, lay[3], w / 2, (h * 0.80).toNumber(), Gfx.FONT_XTINY,
                Gfx.TEXT_JUSTIFY_CENTER, true, Gfx.COLOR_DK_GRAY, vals);
        }
        drawSlot(dc, data, ci, lay[4], w / 2, (h * 0.88).toNumber(), sf,
            Gfx.TEXT_JUSTIFY_CENTER, true, Gfx.COLOR_DK_GRAY, vals);
    }

    // True when this slot code ends up drawing the interval block for a climb with/without one.
    function slotShowsBlock(code, hasBlock) {
        return code == FieldLayout.BLOCK || (code == FieldLayout.AUTO_ROW4 && hasBlock);
    }

    // Draws one stat slot. AUTO_ROW4 / AUTO_BOTTOM resolve to their pre-layout fallbacks;
    // GHOST and BLOCK keep their own colours and show "--" when the climb has neither.
    hidden function drawSlot(dc, data, ci, code, x, y, font, justify, wide, color, vals) {
        if (code == FieldLayout.AUTO_ROW4) {
            if (data.hasBlock[ci]) {
                code = FieldLayout.BLOCK;
            } else if (vals[:hasVam]) {
                code = FieldLayout.VAM;
            } else {
                return;
            }
        } else if (code == FieldLayout.AUTO_BOTTOM) {
            if (drawGhost(dc, data, ci, x, y, font, justify, wide)) {
                return;
            }
            code = FieldLayout.ETA;
        }

        var text = null;
        if (code == FieldLayout.GHOST) {
            if (drawGhost(dc, data, ci, x, y, font, justify, wide)) {
                return;
            }
            text = "--";
        } else if (code == FieldLayout.BLOCK) {
            if (data.hasBlock[ci]) {
                drawIntervalBlock(dc, data, ci, x, y, font, justify);
                return;
            }
            text = "--";
        } else {
            text = FieldLayout.metricText(code, vals, wide);
        }
        if (text != null && text.length() > 0) {
            dc.setColor(color, Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, text, justify);
        }
    }

    // Live delta against the per-segment PR ("vs PR"), else the manual pacing plan
    // ("vs plan"); PR takes priority as the always-on repeat-climb signal. Returns false
    // (nothing drawn) before the climb timer started or without a reference.
    // Without either, the route-level virtual opponent ("vs beste", issue #178) is shown.
    hidden function drawGhost(dc, data, ci, x, y, font, justify, wide) {
        if (data.climbStartTimerMs < 0) {
            return drawRouteGhost(dc, data, x, y, font, justify, wide);
        }
        var actual = (lastGhostTimerMs - data.climbStartTimerMs) / 1000.0;
        if (data.hasRefTargets[ci]) {
            var ref = data.refSecondsAt();
            if (ref >= 0) {
                drawGhostDelta(dc, x, y, font, justify, (actual - ref).toNumber(),
                    wide ? " vs PR" : "", data.palette);
                return true;
            }
        } else if (data.hasTargets[ci]) {
            var target = data.targetSecondsAt();
            if (target >= 0) {
                drawGhostDelta(dc, x, y, font, justify, (actual - target).toNumber(),
                    wide ? " vs plan" : "", data.palette);
                return true;
            }
        }
        return drawRouteGhost(dc, data, x, y, font, justify, wide);
    }

    // Seconds ahead/behind the best earlier ride of this route (issue #178); false (nothing
    // drawn) without a route ghost, off-route, in radius mode or before the timer runs.
    hidden function drawRouteGhost(dc, data, x, y, font, justify, wide) {
        var d = data.routeGhostDeltaSec;
        if (d == null) {
            return false;
        }
        drawGhostDelta(dc, x, y, font, justify, d, wide ? " vs beste" : "", data.palette);
        return true;
    }

    // Interval-block line (issue #180): "Doel 266-280W" without a power meter, otherwise
    // "252W 266-280" coloured blue (under), green (in band) or red (over).
    hidden function drawIntervalBlock(dc, data, ci, x, y, font, justify) {
        var band = data.blockLow[ci] + "-" + data.blockHigh[ci];
        var zone = data.blockZone(ci, data.currentPower);
        dc.setColor(intervalZoneColorFor(zone, data.palette), Gfx.COLOR_TRANSPARENT);
        var text = (zone == data.ZONE_NONE)
            ? "Doel " + band + "W"
            : data.currentPower.toNumber() + "W " + band;
        dc.drawText(x, y, font, text, justify);
    }

    // Colour per power zone; not hidden so tests can check it directly.
    function intervalZoneColor(zone) {
        return intervalZoneColorFor(zone, 0);
    }

    // Colorblind palette (issue #258): under = navy, in band = sky blue, over = orange --
    // apart in lightness and on the blue-orange axis instead of green/red.
    function intervalZoneColorFor(zone, palette) {
        if (palette == PALETTE_COLORBLIND) {
            if (zone == -1) { return 0x0000AA; }
            if (zone == 0) { return CVD_OK_COLOR; }
            if (zone == 1) { return CVD_BAD_COLOR; }
            return Gfx.COLOR_DK_GRAY;
        }
        if (zone == -1) { return Gfx.COLOR_BLUE; }
        if (zone == 0) { return Gfx.COLOR_DK_GREEN; }
        if (zone == 1) { return Gfx.COLOR_RED; }
        return Gfx.COLOR_DK_GRAY;
    }

    // + = behind (red; orange when colorblind), - or 0 = ahead/on pace (green; blue).
    hidden function drawGhostDelta(dc, x, y, font, justify, deltaSec, suffix, pal) {
        if (deltaSec > 0) {
            dc.setColor(badColor(pal), Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, "+" + deltaSec + "s" + suffix, justify);
        } else {
            dc.setColor(okColor(pal), Gfx.COLOR_TRANSPARENT);
            dc.drawText(x, y, font, deltaSec + "s" + suffix, justify);
        }
    }

    // =========================================================================
    // Profile drawing (colored bars like ClimbFinder)
    // =========================================================================

    // Reads the "darkTheme" app setting (resources/settings/) and picks the matching
    // gradient-color palette. Wrapped in try/catch: Properties.getValue can throw if
    // the property isn't registered (e.g. a stale/older simulator settings cache),
    // and this must never crash a per-tick redraw -- fall back to the normal palette.
    hidden function activeColors(data) {
        var dark = false;
        try {
            var v = Properties.getValue("darkTheme");
            dark = (v != null && v == true);
        } catch (e) {
            dark = false;
        }
        return paletteColors(data.palette, dark);
    }

    // Pure palette pick (testable): the colorblind palette (issue #258) wins over the dark
    // theme, since telling the bands apart matters more than dimming them.
    function paletteColors(palette, dark) {
        if (palette == PALETTE_COLORBLIND) { return CVD_COLORS; }
        return dark ? DARK_COLORS : COLORS;
    }

    // Good/ahead status color: green, or blue in the colorblind palette (issue #258).
    function okColor(palette) {
        return palette == PALETTE_COLORBLIND ? CVD_OK_COLOR : Gfx.COLOR_GREEN;
    }

    // Bad/behind status color: red, or orange in the colorblind palette (issue #258).
    function badColor(palette) {
        return palette == PALETTE_COLORBLIND ? CVD_BAD_COLOR : Gfx.COLOR_RED;
    }

    // Reads the "largeTextMode" app setting (issue #82, resources/settings/) the same
    // defensive way activeColors() reads "darkTheme" -- Properties.getValue can throw
    // on a stale/older simulator settings cache and this must never crash a per-tick
    // redraw. Independent of darkTheme; the two settings compose freely.
    hidden function largeTextModeActive() {
        var large = false;
        try {
            var v = Properties.getValue("largeTextMode");
            large = (v != null && v == true);
        } catch (e) {
            large = false;
        }
        return large;
    }

    // Reads the "colorMode" app setting (issue #66, resources/settings/): 0 = Helling
    // (gradient colors, default), 1 = FTP-zone. Same defensive read as activeColors():
    // a missing/stale property must never crash a redraw -- fall back to gradient colors.
    hidden function zoneColorModeActive() {
        var v = null;
        try {
            v = Properties.getValue("colorMode");
        } catch (e) {
            v = null;
        }
        return zoneColorsSelected(v);
    }

    // "colorMode" as a Number (0 when missing or invalid); 2 = heart-rate zones (issue #24).
    hidden function colorModeSetting() {
        return readNumberSetting("colorMode");
    }

    // Pure decision for zoneColorModeActive() (no Properties access, so tests can call it):
    // only the exact FTP-zone value selects zone colors; anything else is gradient mode.
    function zoneColorsSelected(settingValue) {
        return settingValue != null && settingValue instanceof Toybox.Lang.Number
            && settingValue == COLOR_MODE_ZONES;
    }

    // =========================================================================
    // Large-text-mode font/layout decisions (issue #82)
    // =========================================================================
    // Kept as small pure functions (no Dc/Properties access) so they're directly
    // unit-testable -- see garmin/test/LargeTextModeTest.mc. Not "hidden" so tests
    // can call them straight, the way ClimbData's targetSecondsAt()/refSecondsAt()
    // are tested directly rather than only smoke-tested through onUpdate().

    // Climb/route name: the single most important line to make legible for a
    // low-vision or bright-sunlight rider, so it gets the biggest bump.
    function nameFont(large) {
        return large ? Gfx.FONT_MEDIUM : Gfx.FONT_TINY;
    }

    // Stat-row numbers (remaining distance, gradient, ETA/ghost line, etc.).
    function statFont(large) {
        return large ? Gfx.FONT_SMALL : Gfx.FONT_XTINY;
    }

    // Whether to draw a "secondary" stat/line this tick. Per issue #82 ("minder
    // informatie per scherm ten gunste van leesbaarheid"), large-text mode shows
    // LESS information rather than cramming bigger text into the same layout: it
    // drops nice-to-have items (VAM numbers, the middle elevation stat column) and
    // keeps only what's most critical to a glancing rider (climb name, primary
    // progress stat, remaining distance, current gradient, pacing/ETA line).
    function showSecondaryStat(large) {
        return !large;
    }

    hidden function drawProfile(dc, data, ci, x, y, w, h) {

        var colors = activeColors(data);
        var colorMode = zoneColorModeActive() ? COLOR_MODE_ZONES : colorModeSetting();
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
            var colorIdx = data.colorIndexForMode(ci, s, colorMode);

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
        var large = largeTextModeActive();

        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, previewHeader(data), Gfx.TEXT_JUSTIFY_CENTER);

        // Climb name
        var name = data.climbName[ni];
        if (name == null) {
            name = "Climb " + (ni + 1);
        }
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.15).toNumber(), nameFont(large), name, Gfx.TEXT_JUSTIFY_CENTER);

        // Mini profile
        var profileTop = (h * 0.30).toNumber();
        var profileHeight = (h * 0.35).toNumber();
        drawProfile(dc, data, ni, 8, profileTop, w - 16, profileHeight);

        // Stats
        var statsY = (h * 0.72).toNumber();
        var length = data.climbLength[ni];
        var elev = data.climbElevGain[ni];
        var grad = data.climbAvgGrad[ni];

        var sf = statFont(large);
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(32, statsY, sf, formatDist(length), Gfx.TEXT_JUSTIFY_LEFT);
        // Elevation gain is the "nice to have" middle stat -- dropped in large-text
        // mode, same rationale as the active-climb stat row (issue #82).
        if (showSecondaryStat(large)) {
            dc.drawText(w / 2, statsY, sf,
                Units.isImperial(data.units) ? Units.formatElev(elev, data.units) : elev + "hm",
                Gfx.TEXT_JUSTIFY_CENTER);
        }
        var nGrad = grad / 10;
        var nGradF = grad % 10;
        dc.drawText(w - 32, statsY, sf,
            nGrad + "." + nGradF + "%", Gfx.TEXT_JUSTIFY_RIGHT);

        // Distance to climb -- kept in large-text mode, it's the key "when do I
        // need to be ready" stat on this screen, not a nice-to-have.
        if (data.distToNextClimb >= 0) {
            var distY = (h * 0.88).toNumber();
            dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
            dc.drawText(w / 2, distY, sf,
                distToNextLabel(data.mode, data.distToNextClimb, data.units), Gfx.TEXT_JUSTIFY_CENTER);
        }

        // Virtual opponent (issue #178) in the otherwise empty row above "in X km".
        drawRouteGhost(dc, data, w / 2, (h * 0.80).toNumber(), Gfx.FONT_XTINY,
            Gfx.TEXT_JUSTIFY_CENTER, true);
    }

    // Header of the next-climb page: a day trip (issue #9) shows "DAGTOCHT 2/5" (the
    // climb about to come, of the total), everything else "NEXT CLIMB".
    hidden function previewHeader(data) {
        if (data.ordered && data.mode != null && data.mode.equals("radius")) {
            return "DAGTOCHT " + (data.nextClimbIndex + 1) + "/" + data.climbCount;
        }
        return "NEXT CLIMB";
    }

    // "in 2.3 km" along the route; in radius mode (issue #7) the distance is straight-line
    // to the climb start, so it gets a "~".
    function distToNextLabel(mode, distM, units) {
        var prefix = (mode != null && mode.equals("radius")) ? "in ~" : "in ";
        return prefix + Units.formatDist(distM, units);
    }

    // =========================================================================
    // Utility
    // =========================================================================

    hidden function drawNoData(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_MEDIUM,
            "No data", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }

    hidden function drawNoClimbs(dc, data) {
        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Gfx.FONT_SMALL,
            "No climbs ahead", Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
        // Virtual opponent (issue #178): the only live info between the last climb and home.
        drawRouteGhost(dc, data, dc.getWidth() / 2, (dc.getHeight() * 0.66).toNumber(),
            Gfx.FONT_SMALL, Gfx.TEXT_JUSTIFY_CENTER, true);
    }

    // Distance in the rider's chosen units (payload "un", issue #262); metric by default.
    hidden function formatDist(meters) {
        var data = App.getApp().climbData;
        return Units.formatDist(meters, data != null ? data.units : 0);
    }

    // Reads the "climbAlertDistinctTone" app setting (resources/settings/, issue #83)
    // the same defensive way activeColors() reads "darkTheme": Properties.getValue can
    // throw on a stale/older simulator settings cache, and this must never crash the
    // climb-start-alert trigger path -- fall back to the default (off) alert style.
    hidden function useDistinctClimbAlertTone() {
        var distinct = false;
        try {
            var v = Properties.getValue("climbAlertDistinctTone");
            distinct = (v != null && v == true);
        } catch (e) {
            distinct = false;
        }
        return distinct;
    }

    // Reads the "easierAheadAlert" app setting (issue #213, default on). Same defensive read
    // as the other settings: a missing/stale property falls back to on.
    hidden function easierAheadEnabled() {
        try {
            var v = Properties.getValue("easierAheadAlert");
            return v == null || v != false;
        } catch (e) {
            return true;
        }
    }

    // Easier stretch ahead (issue #213): two short light buzzes, no tone -- lighter than
    // the climb-start and battery alerts so it can't be mistaken for either.
    hidden function triggerEasierAheadAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(50, 150),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(50, 150)
            ]);
        }
    }

    // Climb-start alert. Issue #26: the "vibeShortSteep" / "vibeLong" / "vibeRegular" setting
    // for this climb's type picks the vibration; "standaard" keeps the alert below.
    hidden function triggerClimbAlert(data, ci) {
        var settingKey = ["vibeRegular", "vibeShortSteep", "vibeLong"]
                [climbTypeOf(data.climbLength[ci], data.climbAvgGrad[ci])];
        var pattern = climbVibePattern(readNumberSetting(settingKey));
        if (pattern != null) {
            if (Attention has :vibrate) {
                var profiles = new [pattern.size() / 2];
                for (var i = 0; i < profiles.size(); i++) {
                    profiles[i] = new Attention.VibeProfile(pattern[i * 2], pattern[i * 2 + 1]);
                }
                Attention.vibrate(profiles);
            }
            if (Attention has :playTone) {
                Attention.playTone(useDistinctClimbAlertTone() ? Attention.TONE_START : Attention.TONE_LAP);
            }
            return;
        }
        triggerDefaultClimbAlert();
    }

    hidden function triggerDefaultClimbAlert() {
        if (useDistinctClimbAlertTone()) {
            triggerClimbAlertDistinctTone();
            return;
        }
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

    // Issue #83: riders wearing earbuds/headphones can miss the wrist vibration
    // climb-start alert. The literal ask was a spoken "voice prompt", but
    // Toybox.Attention exposes no TTS / audio-clip-playback API on the FR255M --
    // it only offers vibrate(), playTone() (from a fixed set of built-in tone
    // constants) and backlight(). There is no way for a third-party Connect IQ
    // datafield to speak arbitrary words on this device/SDK tier.
    //
    // This is the most faithful available approximation: an opt-in, more
    // attention-grabbing alert layered on top of the normal vibration, instead
    // of the single TONE_LAP chime used by the default alert. Purely additive --
    // default (setting off/unset) keeps today's vibrate+TONE_LAP behavior
    // unchanged.
    //
    // Code review on PR #133 flagged that the first version of this alert was too
    // similar to triggerBatteryAlert(): both used a 5-entry, evenly-spaced
    // (3 pulses / 2 gaps) vibe pattern with identical 150ms gaps and the same
    // terminal TONE_ALERT_HI tone -- differing only in pulse length (400ms vs
    // 250ms), which is not reliably distinguishable by feel mid-ride. Fixed by
    // using a genuinely different vibe SHAPE (short-short-short-long "here it
    // comes" rhythm, 4 pulses / 3 gaps, 80ms gaps instead of 150ms) and a
    // different terminal tone (TONE_START, which also fits the "climb start"
    // semantics -- distinct from both TONE_LAP and TONE_ALERT_HI).
    //
    // Also fixed: the original used three back-to-back playTone() calls
    // (TONE_LAP, TONE_LAP, TONE_ALERT_HI) with no gap between them. Toybox.Attention
    // on real hardware does not reliably queue playTone() calls -- a later call
    // can cut off/interrupt an earlier one's playback, so a rider was likely to
    // hear only the final tone rather than the intended 3-tone cadence. The
    // "distinct pattern" signal now lives entirely in the VIBE profile (which IS
    // a proper timed sequence on this API), and the tone is reduced to a single
    // playTone() call so there is nothing to race/drop.
    hidden function triggerClimbAlertDistinctTone() {
        if (Attention has :vibrate) {
            var vibePattern = [
                new Attention.VibeProfile(100, 120),
                new Attention.VibeProfile(0, 80),
                new Attention.VibeProfile(100, 120),
                new Attention.VibeProfile(0, 80),
                new Attention.VibeProfile(100, 120),
                new Attention.VibeProfile(0, 80),
                new Attention.VibeProfile(100, 600)
            ];
            Attention.vibrate(vibePattern);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_START);
        }
    }

    // Tunnel / technical descent ahead (issue #203): three short buzzes + a high tone.
    hidden function triggerHazardAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 200),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 200),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 200)
            ]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_ALERT_HI);
        }
    }

    // Everesting repeat done (issue #217): two short buzzes, no tone.
    hidden function triggerEverestRepeatAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 200),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(100, 200)
            ]);
        }
    }

    // Everesting target reached (issue #217): long buzz + success tone, once.
    hidden function triggerEverestDoneAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(100, 1500)]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_SUCCESS);
        }
    }

    // Heart-rate alarm (issue #228): four quick buzzes + the high alert tone, a different
    // rhythm from the climb-start, battery and block-done alerts.
    hidden function triggerHeartRateAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 150),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 150),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 150),
                new Attention.VibeProfile(0, 100),
                new Attention.VibeProfile(100, 150)
            ]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_ALERT_HI);
        }
    }

    // Cadence coach (issue #179): vibration only, no tone -- a coaching nudge, not a
    // warning. Too low = one long buzz ("trap sneller"), too high = two short ones.
    hidden function triggerCadenceAlert(dir) {
        if (!(Attention has :vibrate)) { return; }
        if (dir == CADENCE_HIGH) {
            Attention.vibrate([
                new Attention.VibeProfile(60, 150),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(60, 150)
            ]);
        } else {
            Attention.vibrate([new Attention.VibeProfile(60, 600)]);
        }
    }

    // Heat-index warning (issue #227): two long buzzes + the alert tone -- a different
    // shape from the climb-start (long-gap-long, lap tone) and battery (3 short) alerts.
    hidden function triggerHeatAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 800),
                new Attention.VibeProfile(0, 300),
                new Attention.VibeProfile(100, 800)
            ]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_ALERT_LO);
        }
    }

    // Eat/drink reminder (issue #184): the climb-start vibration (long-gap-long) with the
    // time-alert tone instead of the lap tone, so it is felt like the climb alert but heard
    // as a different event.
    hidden function triggerFuelAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 500),
                new Attention.VibeProfile(0, 200),
                new Attention.VibeProfile(100, 500)
            ]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_TIME_ALERT);
        }
    }

    // Distinct pattern/tone from triggerClimbAlert() so the rider can tell a battery
    // Interval block done at the top (issue #180): one short buzz, no tone -- deliberately
    // lighter than the climb-start and battery alerts so it can't be mistaken for either.
    // Top of a climb reached (issue #8): one medium buzz, softer than the start alert.
    hidden function triggerSummitAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(70, 400)]);
        }
    }

    // Too fast for the plan/PR (issue #6): two long pulses, distinct from the other alerts.
    hidden function triggerPacingAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 400),
                new Attention.VibeProfile(0, 200),
                new Attention.VibeProfile(100, 400)
            ]);
        }
    }

    // Like readBoolSetting(), but a missing/unreadable property yields `def`.
    hidden function readBoolSettingDefault(key, def) {
        try {
            var v = Properties.getValue(key);
            if (v instanceof Boolean) { return v; }
        } catch (e) {
            return def;
        }
        return def;
    }

    hidden function triggerBlockDoneAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(100, 300)]);
        }
    }

    // Dusk reminder (issue #198): two long buzzes + a low tone, distinct from the climb,
    // battery and block-done alerts.
    hidden function triggerLightsAlert() {
        if (Attention has :vibrate) {
            Attention.vibrate([
                new Attention.VibeProfile(100, 800),
                new Attention.VibeProfile(0, 300),
                new Attention.VibeProfile(100, 800)
            ]);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_ALERT_LO);
        }
    }

    // warning apart from a climb-start alert by feel/sound alone.
    hidden function triggerBatteryAlert() {
        if (Attention has :vibrate) {
            var vibePattern = [
                new Attention.VibeProfile(100, 250),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(100, 250),
                new Attention.VibeProfile(0, 150),
                new Attention.VibeProfile(100, 250)
            ];
            Attention.vibrate(vibePattern);
        }
        if (Attention has :playTone) {
            Attention.playTone(Attention.TONE_ALERT_HI);
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
        var large = largeTextModeActive();
        var sf = statFont(large);

        var name = data.climbName[ci];
        if (name == null) { name = "Climb " + (ci + 1); }

        dc.setColor(Gfx.COLOR_DK_GRAY, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 4, Gfx.FONT_XTINY, "KLIM KLAAR", Gfx.TEXT_JUSTIFY_CENTER);

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, (h * 0.18).toNumber(), nameFont(large), name, Gfx.TEXT_JUSTIFY_CENTER);

        var mins = summaryActualSec / 60;
        var secs = summaryActualSec % 60;
        if (secs < 0) { secs = -secs; }
        dc.drawText(w / 2, (h * 0.40).toNumber(), Gfx.FONT_NUMBER_MEDIUM,
                mins + ":" + (secs < 10 ? "0" + secs : "" + secs), Gfx.TEXT_JUSTIFY_CENTER);

        // Issue #8: ascent and average gradient of the climb just finished.
        dc.drawText(w / 2, (h * 0.62).toNumber(), sf,
                Units.formatElev(data.climbElevGain[ci], data.units) + "↑  "
                    + FieldLayout.formatGrad(data.climbAvgGrad[ci]),
                Gfx.TEXT_JUSTIFY_CENTER);

        if (data.hasTargets[ci]) {
            var d = summaryDeltaSec;
            if (d > 0) {
                dc.setColor(badColor(data.palette), Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), sf,
                        "+" + d + "s vs plan", Gfx.TEXT_JUSTIFY_CENTER);
            } else {
                dc.setColor(okColor(data.palette), Gfx.COLOR_TRANSPARENT);
                dc.drawText(w / 2, (h * 0.78).toNumber(), sf,
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
