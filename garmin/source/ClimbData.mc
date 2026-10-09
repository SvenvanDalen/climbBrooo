using Toybox.System as Sys;
using Toybox.Math as Math;

/**
 * Flat data store for all climb/segment data received from the phone.
 * Uses parallel arrays to minimize object allocation on the watch.
 * Memory budget: ~4KB for 16 climbs × 16 segments.
 */
class ClimbData {

    // Limits
    const MAX_CLIMBS = 16;
    const MAX_SEGMENTS = 16;
    const MAX_CALIB = 16;

    // Route-matching thresholds (metres)
    const APPROACH_WINDOW_M = 1000; // start GPS↔route coordinate checking this far before a climb
    const CALIB_SNAP_M      = 30;   // on-climb: snap progress when within this of a calib point
    const APPROACH_SNAP_M   = 40;   // approach: align climb start when within this of calib point 0
    const OFFROUTE_ON_M     = 100;  // on-climb: off-route if beyond this from every calib point
    const OFFROUTE_MARGIN_M = 300;  // approach: off-route if straight-line exceeds along-route remaining by this

    // Navigation-distance trust
    const NAV_LEN_TOL_M   = 500;   // length-gate tolerance floor (m)
    const NAV_LEN_TOL_PCT = 10;    // length-gate tolerance as % of routeTotalLen
    const NAV_DISAGREE_M  = 150;   // nav-vs-calib disagreement that revokes trust (m)
    const NAV_UNKNOWN = 0;
    const NAV_TRUSTED = 1;
    const NAV_REVOKED = 2;

    // Skip resilience
    const SKIP_MARGIN_M = 1000;   // ride this far past a climb's end (m) before it may be skipped

    // ETA-to-summit
    const MIN_ETA_SPEED_MPS = 0.5;   // below this, treat speed as too noisy/stopped for an ETA

    // Battery-vs-remaining-climb-time warning (issue #49). We have no way to read the
    // device's actual discharge curve from Monkey C, so this is a deliberately
    // conservative, hand-picked estimate: FR255M running GPS + backlight + music
    // playback typically drains well under 10%/hour, so 10%/hour is a pessimistic
    // (worst-case) assumption that would rather warn a bit early than not warn at all.
    const BATTERY_DRAIN_PCT_PER_HOUR = 10.0;
    // Require this much extra battery runway beyond the raw ETA before staying quiet,
    // to absorb estimate error (pace changes, drain-rate variance) and leave the rider
    // some margin after the summit rather than cutting it exactly to zero.
    const BATTERY_WARNING_MARGIN = 1.2;

    // Payload state
    var payloadReceived = false;
    var mode = "route";       // "route" or "radius"
    var routeId = null;
    var routeName = null;
    var routeTotalLen = 0;    // route total length (m) from payload "rtl"; 0 = unknown
    var palette = 0;          // color palette from payload "pal" (issue #258): 0 default, 1 colorblind
    var hazards = null;       // packed "hz" [startM, endM, type, ...] (issue #203); null = none
    var units = 0;            // display-unit bitmask from payload "un" (issue #262); 0 = metric
    var ordered = false;      // radius payload with "ord": 1 = day trip in this order (issue #9)
    // Per gradient class (segment color 0-5): the rider's usual cadence in rpm ("cg", issue
    // #18) and usual heart-rate zone 1-5 ("hg", issue #24); 0 = unknown. null = not sent.
    var cadenceByGrade = null;
    var hrZoneByGrade = null;

    // Virtual opponent on the route (issue #178, wire "gh" = [stepM, sec1, ..., secN]).
    const MAX_GHOST_STEPS = 100;
    var ghostStep = 0;            // metres per step; 0 = no route ghost
    var ghostCum = null;          // cumulative seconds at each step boundary, [0] = 0; null = none
    var ghostAnchorMs = -1;       // timerTime (ms) when the opponent started alongside the rider
    var ghostAnchorRef = 0.0;     // reference seconds at the anchor's route position
    var routeGhostDeltaSec = null; // latest delta (s, + = behind); null = nothing to show

    // Climb-level arrays (indexed by climb)
    var climbCount = 0;
    var climbStartDist;   // route mode: start distance along route (m) — working value (may be shifted)
    var climbEndDist;     // route mode: end distance along route (m) — working value
    var climbStartDist0;  // immutable start anchor (never shifted) — absolute route distance
    var climbEndDist0;    // immutable end anchor (never shifted)
    var climbLength;      // climb length (m)
    var climbElevGain;    // elevation gain (m)
    var climbAvgGrad;     // avg gradient fixed-point (pct×10)
    var climbName;        // display name (String or null)
    var climbStartLat;    // radius mode: start latitude
    var climbStartLon;    // radius mode: start longitude
    var climbNew;         // bool per climb: never ridden before ("nw", issue #27)
    var radiusDist;       // radius mode: straight-line metres to each start, -1 = unknown (issue #7)

    // Calibration point arrays — populated by CommListener when it parses the "calib" key
    // from the v3 payload. checkCalibration() is a no-op until CommListener wires these up.
    // (indexed [climb][calib_point])
    var calibCount;   // calibration points per climb
    var calibDist;    // distance from climb start (m)
    var calibLat;     // latitude (Float)
    var calibLon;     // longitude (Float)
    var calibIdx;     // next calibration point index to check (reset on payload)

    // Segment-level arrays (indexed [climb][segment])
    var segCount;         // segments per climb
    var segDist;          // segment distance (m)
    var segElevGain;      // segment elevation gain (m)
    var segGradient;      // segment gradient fixed-point (pct×10)
    var segColor;         // color index 0-5
    var segSurf;          // surface type per segment: 0=asphalt 1=gravel 2=dirt 3=cobble 4=mixed 5=unknown
    var segTargetSec;     // per-segment target seconds (parallel to seg arrays); 0 = none
    var hasTargets;       // bool per climb: true when tsec was provided
    var segRefSec;        // per-segment PR reference seconds (parallel to seg arrays); 0 = none
    var hasRefTargets;    // bool per climb: true when refsec was provided
    var segVamAvg;        // gradient-implied average VAM (m/h) per segment; 0 = none
    var segVamPeak;       // gradient-implied peak VAM (m/h) per segment; 0 = none
    var hasVam;           // bool per climb: true when vam was provided
    var segZone;          // per climb: Array of FTP intensity-zone color indices (0-5, "zc",
                          // issue #66) or null when the payload carried none. Allocated only
                          // when zc arrives, so riders without an FTP pay no memory for it.

    // Interval block per climb (issue #180, wire "ib"): power band in watts, foot to top.
    var hasBlock;         // bool per climb: true when a valid ib was provided
    var blockTarget;      // target watts
    var blockLow;         // lower band edge (W)
    var blockHigh;        // upper band edge (W)

    // Everesting attempt (issue #217, wire "ev"): [targetM, repeats, startLat, startLon,
    // topLat, topLon] or null; everestClimb = index of the climb that carried it (-1 = none).
    var everest = null;
    var everestClimb = -1;

    // Power-zone results for powerZone()/blockZone()
    const ZONE_NONE  = -2;   // no block or no power reading
    const ZONE_UNDER = -1;
    const ZONE_IN    = 0;
    const ZONE_OVER  = 1;

    // Runtime state (set by RouteTracker)
    var activeClimbIndex = -1;     // -1 = not on a climb
    var activeSegmentIndex = -1;   // current segment within active climb
    var progressInClimb = 0;       // meters into the active climb
    var distToNextClimb = -1;      // meters to the next climb start (-1 = unknown)
    var nextClimbIndex = -1;       // index of next upcoming climb
    var lastElapsedDistance = 0;  // last GPS elapsed distance passed to updateProgress
    var climbStartTimerMs = -1;    // timerTime (ms) when the active climb was entered; -1 = not set
    var offRoute = false;          // true when GPS has diverged from the route near a climb
    var climbEntered;              // bool per climb: GPS has confirmed the rider physically reached
                                   // this climb's start. A climb may not become active until this is
                                   // set (climbs without calibration geometry fall back to odometer).
    var navTrust = 0;             // NAV_UNKNOWN / NAV_TRUSTED / NAV_REVOKED
    var navMaxToDest = 0;         // largest distanceToDestination seen this ride (length gate)
    var navDistThisTick = -1;     // navDist for the current tick (-1 = not navigating); set by view
    var climbSkipped;             // bool per climb: rider bypassed it; progression skips over it
    var currentSpeedMps = 0.0;    // most recent Activity.Info.currentSpeed; set by view.compute()
    var currentPower = null;      // most recent Activity.Info.currentPower (W); null = no power meter
    var currentHeartRate = null;  // most recent Activity.Info.currentHeartRate (bpm); null = no sensor
    var currentCadence = null;    // most recent Activity.Info.currentCadence (rpm); null = no sensor
    var layout;                   // stat-slot metric codes for the active-climb page ('lay')
    var batteryWarningActive = false; // true once the low-battery-vs-climb-time warning has
                                       // fired for the current climb; cleared when the climb ends
                                       // or the route changes. Drives the view's persistent banner.

    function initialize() {
        layout = FieldLayout.defaults();
        climbStartDist = new [MAX_CLIMBS];
        climbEndDist = new [MAX_CLIMBS];
        climbStartDist0 = new [MAX_CLIMBS];
        climbEndDist0 = new [MAX_CLIMBS];
        climbLength = new [MAX_CLIMBS];
        climbElevGain = new [MAX_CLIMBS];
        climbAvgGrad = new [MAX_CLIMBS];
        climbName = new [MAX_CLIMBS];
        climbStartLat = new [MAX_CLIMBS];
        climbStartLon = new [MAX_CLIMBS];
        climbNew = new [MAX_CLIMBS];
        radiusDist = new [MAX_CLIMBS];
        segCount = new [MAX_CLIMBS];

        segDist = new [MAX_CLIMBS];
        segElevGain = new [MAX_CLIMBS];
        segGradient = new [MAX_CLIMBS];
        segColor = new [MAX_CLIMBS];
        segSurf = new [MAX_CLIMBS];
        segTargetSec = new [MAX_CLIMBS];
        hasTargets = new [MAX_CLIMBS];
        segRefSec = new [MAX_CLIMBS];
        hasRefTargets = new [MAX_CLIMBS];
        segVamAvg = new [MAX_CLIMBS];
        segVamPeak = new [MAX_CLIMBS];
        hasVam = new [MAX_CLIMBS];
        hasBlock = new [MAX_CLIMBS];
        blockTarget = new [MAX_CLIMBS];
        blockLow = new [MAX_CLIMBS];
        blockHigh = new [MAX_CLIMBS];
        segZone = new [MAX_CLIMBS];   // all null until a payload carries zc
        climbEntered = new [MAX_CLIMBS];
        climbSkipped = new [MAX_CLIMBS];

        for (var i = 0; i < MAX_CLIMBS; i++) {
            climbEntered[i] = false;
            climbSkipped[i] = false;
            climbStartDist[i] = 0;
            climbEndDist[i] = 0;
            climbStartDist0[i] = 0;
            climbEndDist0[i] = 0;
            climbLength[i] = 0;
            climbElevGain[i] = 0;
            climbAvgGrad[i] = 0;
            climbName[i] = null;
            climbStartLat[i] = 0.0;
            climbStartLon[i] = 0.0;
            climbNew[i] = false;
            radiusDist[i] = -1;
            segCount[i] = 0;

            segDist[i] = new [MAX_SEGMENTS];
            segElevGain[i] = new [MAX_SEGMENTS];
            segGradient[i] = new [MAX_SEGMENTS];
            segColor[i] = new [MAX_SEGMENTS];
            segSurf[i] = new [MAX_SEGMENTS];
            segTargetSec[i] = new [MAX_SEGMENTS];
            hasTargets[i] = false;
            segRefSec[i] = new [MAX_SEGMENTS];
            hasRefTargets[i] = false;
            segVamAvg[i] = new [MAX_SEGMENTS];
            segVamPeak[i] = new [MAX_SEGMENTS];
            hasVam[i] = false;
            hasBlock[i] = false;
            blockTarget[i] = 0;
            blockLow[i] = 0;
            blockHigh[i] = 0;
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
                segSurf[i][s] = 5; // UNKNOWN
                segTargetSec[i][s] = 0;
                segRefSec[i][s] = 0;
                segVamAvg[i][s] = 0;
                segVamPeak[i][s] = 0;
            }
        }

        calibCount = new [MAX_CLIMBS];
        calibDist  = new [MAX_CLIMBS];
        calibLat   = new [MAX_CLIMBS];
        calibLon   = new [MAX_CLIMBS];
        calibIdx   = new [MAX_CLIMBS];

        for (var i = 0; i < MAX_CLIMBS; i++) {
            calibCount[i] = 0;
            calibIdx[i]   = 0;
            calibDist[i]  = new [MAX_CALIB];
            calibLat[i]   = new [MAX_CALIB];
            calibLon[i]   = new [MAX_CALIB];
            for (var k = 0; k < MAX_CALIB; k++) {
                calibDist[i][k] = 0;
                calibLat[i][k]  = 0.0f;
                calibLon[i][k]  = 0.0f;
            }
        }
    }

    // Color index (0-5) to paint segment s of climb ci with. useZones = the "colorMode"
    // setting is FTP-zone (issue #66): then the phone's intensity-zone color (zc) is used
    // when this climb has one, otherwise -- and always in the default gradient mode -- the
    // gradient color from segs. Clamped so a bad value can never index past the palette.
    function colorIndexAt(ci, s, useZones) {
        var c = segColor[ci][s];
        if (useZones) {
            var z = segZone[ci];
            if (z != null && s < z.size()) { c = z[s]; }
        }
        if (c < 0) { c = 0; }
        if (c > 5) { c = 5; }
        return c;
    }

    // Color index for the "colorMode" setting value: 1 = FTP zones (colorIndexAt with zones),
    // 2 = heart-rate zones per gradient class (issue #24), anything else = gradient colors.
    // A climb or class without zone data keeps its gradient color.
    function colorIndexForMode(ci, s, colorMode) {
        if (colorMode == 2) {
            var c = hrZoneColorIndex(colorIndexAt(ci, s, false));
            return c != null ? c : colorIndexAt(ci, s, false);
        }
        return colorIndexAt(ci, s, colorMode == 1);
    }

    // Heart-rate zone colors (issue #24, "colorMode" 2): the color for the zone the rider
    // usually rides at on this gradient class. Zone 1-5 -> palette index (z1 light yellow,
    // z2 yellow, z3 orange, z4 dark orange, z5 red); null when this class has no zone (then
    // the caller keeps the gradient color).
    function hrZoneColorIndex(colorClass) {
        var hg = hrZoneByGrade;
        if (hg == null || colorClass < 0 || colorClass >= hg.size()) { return null; }
        var z = hg[colorClass];
        if (z < 1 || z > 5) { return null; }
        return z == 5 ? 5 : (z == 4 ? 4 : (z == 3 ? 3 : (z == 2 ? 1 : 0)));
    }

    // The rider's usual cadence on segment s of climb ci (issue #18), or null when unknown.
    function cadenceTargetAt(ci, s) {
        var cg = cadenceByGrade;
        if (cg == null || ci < 0 || s < 0 || s >= segCount[ci]) { return null; }
        var c = segColor[ci][s];
        if (c < 0 || c >= cg.size() || cg[c] <= 0) { return null; }
        return cg[c];
    }

    // Ascent (m) still to come on the whole ride (issue #25): what is left of the active climb
    // plus every later climb that was not skipped. Route mode only; 0 otherwise.
    function rideRemainingElev() {
        if (!payloadReceived || mode == null || !mode.equals("route")) { return 0; }
        var total = 0;
        var from = nextClimbIndex;
        var ci = activeClimbIndex;
        if (ci >= 0) {
            var cumDist = 0;
            for (var s = 0; s < segCount[ci]; s++) {
                cumDist += segDist[ci][s];
                if (cumDist > progressInClimb) { total += segElevGain[ci][s]; }
            }
            from = ci + 1;
        }
        if (from < 0) { return total; }
        for (var i = from; i < climbCount; i++) {
            if (!climbSkipped[i]) { total += climbElevGain[i]; }
        }
        return total;
    }

    /**
     * Radius mode / day trip (issues #7, #9): each GPS tick, measure the straight-line distance
     * to every climb start, mark reached climbs (climbEntered doubles as "reached" here) and
     * pick the climb to count down to. Sets nextClimbIndex / distToNextClimb; route mode is
     * left to updateProgress().
     */
    function updateRadius(lat, lon) {
        if (!payloadReceived || mode == null || !mode.equals("radius")) { return; }
        for (var i = 0; i < climbCount; i++) {
            radiusDist[i] = (climbStartLat[i] == 0.0 && climbStartLon[i] == 0.0)
                    ? -1 : distM(lat, lon, climbStartLat[i], climbStartLon[i]).toNumber();
        }
        radiusMarkVisited(radiusDist, climbEntered, climbCount, ordered);
        nextClimbIndex = radiusPickTarget(radiusDist, climbEntered, climbCount, ordered,
                nextClimbIndex);
        distToNextClimb = nextClimbIndex >= 0 ? radiusDist[nextClimbIndex] : -1;
    }

    function resetNavTrust() {
        navTrust = NAV_UNKNOWN;
        navMaxToDest = 0;
    }

    // Returns the distance (m) to match progress on this tick.
    //   navDist < 0  → not navigating → odometer.
    //   navTrust TRUSTED → navDist; else odometer (evaluating the length gate while UNKNOWN).
    function chooseAxis(elapsed, navDist) {
        if (navDist < 0 || routeTotalLen <= 0) {
            return elapsed;
        }
        if (navTrust == NAV_UNKNOWN) {
            var distToDest = routeTotalLen - navDist;   // == info.distanceToDestination
            if (distToDest > navMaxToDest) { navMaxToDest = distToDest; }
            var tol = (routeTotalLen * NAV_LEN_TOL_PCT) / 100;
            if (tol < NAV_LEN_TOL_M) { tol = NAV_LEN_TOL_M; }
            if (navMaxToDest >= routeTotalLen - tol) {
                navTrust = NAV_TRUSTED;
            }
        }
        return (navTrust == NAV_TRUSTED) ? navDist : elapsed;
    }

    // Sets both the working values and the immutable anchors for a climb.
    function setAnchors(i, startDist, endDist) {
        climbStartDist[i]  = startDist;
        climbEndDist[i]    = endDist;
        climbStartDist0[i] = startDist;
        climbEndDist0[i]   = endDist;
    }

    /**
     * Given elapsed distance along the route, determine active climb and segment.
     * Updates activeClimbIndex, activeSegmentIndex, progressInClimb, distToNextClimb.
     */
    function updateProgress(elapsedDistance) {
        lastElapsedDistance = elapsedDistance;
        if (!payloadReceived || mode == null || !mode.equals("route")) {
            return;
        }

        activeClimbIndex = -1;
        activeSegmentIndex = -1;
        progressInClimb = 0;
        distToNextClimb = -1;
        nextClimbIndex = -1;

        for (var i = 0; i < climbCount; i++) {
            if (climbSkipped[i]) { continue; }   // already abandoned → next climb

            // A climb may only become active once GPS has confirmed the rider physically reached
            // its start (climbEntered). Climbs without calibration geometry have no GPS anchor, so
            // they fall back to odometer-only activation.
            var confirmed = climbEntered[i] || calibCount[i] == 0;

            // Skip decision: never entered, has GPS geometry, ridden well past its end, and the
            // rider is confirmed back on the route. Abandon it and continue with the next climb.
            if (!confirmed && calibCount[i] > 0
                    && elapsedDistance > climbEndDist0[i] + SKIP_MARGIN_M
                    && backOnRoute(i)) {
                climbSkipped[i] = true;
                continue;
            }

            if (confirmed && elapsedDistance > climbEndDist[i]) {
                // Already finished this climb — look at the next one.
                continue;
            }

            if (confirmed && elapsedDistance >= climbStartDist[i]) {
                // We are ON this climb (odometer in range AND GPS-confirmed at the start).
                activeClimbIndex = i;
                progressInClimb = elapsedDistance - climbStartDist[i];

                // Determine active segment
                var cumDist = 0;
                for (var s = 0; s < segCount[i]; s++) {
                    cumDist += segDist[i][s];
                    if (progressInClimb <= cumDist) {
                        activeSegmentIndex = s;
                        break;
                    }
                }
                if (activeSegmentIndex == -1) {
                    activeSegmentIndex = segCount[i] - 1;
                }

                // Next climb
                if (i + 1 < climbCount) {
                    nextClimbIndex = i + 1;
                    distToNextClimb = climbStartDist[i + 1] - elapsedDistance;
                }
                return;
            } else {
                // Approaching this climb — either the odometer hasn't reached the start yet, or it
                // has but GPS has not yet confirmed we are on the climb. distToNextClimb may be ≤ 0
                // in the latter case; updateRouteMatch still runs its confirmation check there.
                nextClimbIndex = i;
                distToNextClimb = climbStartDist[i] - elapsedDistance;
                return;
            }
        }
    }

    /**
     * Call on each GPS update (route mode). Matches the GPS position against the route's
     * known coordinates (the per-climb calibration points) and:
     *  - while ON a climb: snaps progress to calibration points (see {@link #checkCalibration})
     *    and flags {@link #offRoute} when the rider is beyond OFFROUTE_ON_M from every point;
     *  - while APPROACHING a climb (within APPROACH_WINDOW_M): aligns the climb's start distance
     *    once the rider reaches its first calibration point, and flags off-route when the
     *    straight-line distance to that point exceeds the remaining along-route distance by
     *    OFFROUTE_MARGIN_M (i.e. the rider has diverged from the route).
     * Outside the approach window and off any climb there is no route geometry to check, so
     * offRoute is left false.
     */
    function updateRouteMatch(lat, lon) {
        offRoute = false;
        if (!payloadReceived || mode == null || !mode.equals("route")) { return; }

        var ci = activeClimbIndex;
        if (ci >= 0) {
            checkCalibration(lat, lon);
            var minD = minCalibDistM(ci, lat, lon);
            if (minD >= 0 && minD > OFFROUTE_ON_M) { offRoute = true; }
            return;
        }

        var ni = nextClimbIndex;
        // distToNextClimb may be ≤ 0 when the odometer has rolled past an unconfirmed climb's start
        // (e.g. the rider is off-route): we must still run the confirmation check here, so only the
        // upper bound of the approach window is enforced.
        if (ni >= 0 && distToNextClimb <= APPROACH_WINDOW_M && calibCount[ni] > 0) {
            var actual   = distM(lat, lon, calibLat[ni][0], calibLon[ni][0]);
            var expected = (climbStartDist[ni] + calibDist[ni][0]) - lastElapsedDistance;
            // Straight-line distance can never exceed the along-route distance on-route, so a
            // large excess means the rider has left the route.
            if (expected > 0 && actual > expected + OFFROUTE_MARGIN_M) { offRoute = true; }
            // Reached the climb's first calibration point: confirm the rider is physically on the
            // climb (so updateProgress may activate it) and align its start to the current elapsed
            // distance so the climb triggers at the right place despite GPS/odometer drift.
            if (actual <= APPROACH_SNAP_M) {
                climbEntered[ni] = true;
                if (navTrust != NAV_TRUSTED) {
                    climbStartDist[ni] = lastElapsedDistance - calibDist[ni][0];
                }
            }
        }
    }

    // Call on each GPS update when activeClimbIndex >= 0.
    // Resets progressInClimb when within CALIB_SNAP_M of the next calibration point.
    function checkCalibration(lat, lon) {
        if (activeClimbIndex < 0) { return; }
        var ci = activeClimbIndex;
        var k  = calibIdx[ci];
        if (k >= calibCount[ci]) { return; }

        var dm = distM(lat, lon, calibLat[ci][k], calibLon[ci][k]);
        if (dm < CALIB_SNAP_M) {
            if (navTrust == NAV_TRUSTED) {
                // Trusted nav distance is already absolute route distance — validate it against
                // the known absolute distance of this calibration point; do NOT shift the anchor.
                var absCalib = climbStartDist0[ci] + calibDist[ci][k];
                var diff = navDistThisTick - absCalib;
                if (diff < 0) { diff = -diff; }
                if (navDistThisTick >= 0 && diff > NAV_DISAGREE_M) {
                    navTrust = NAV_REVOKED;
                }
            } else {
                // Odometer mode: correct drift by shifting the working start anchor so the NEXT
                // updateProgress() produces the correct progress. GPS says we are at
                // lastElapsedDistance; we know we're really at calibDist[ci][k].
                climbStartDist[ci] = lastElapsedDistance - calibDist[ci][k];
                progressInClimb    = calibDist[ci][k];
                updateCurrentSegment();
            }
            calibIdx[ci] = k + 1;
        }
    }

    // True when the rider is confirmed back on the route at/after climb i.
    //  - trusted nav: the course distance proves we are on the route.
    //  - odometer:    a later climb must be GPS-confirmed (entered) to avoid false-skip.
    hidden function backOnRoute(i) {
        if (navTrust == NAV_TRUSTED) { return true; }
        for (var j = i + 1; j < climbCount; j++) {
            if (climbEntered[j]) { return true; }
        }
        return false;
    }

    // Minimum distance (m) from (lat,lon) to any calibration point of climb ci; -1 if none.
    hidden function minCalibDistM(ci, lat, lon) {
        var n = calibCount[ci];
        if (n <= 0) { return -1.0; }
        var best = -1.0;
        for (var k = 0; k < n; k++) {
            var d = distM(lat, lon, calibLat[ci][k], calibLon[ci][k]);
            if (best < 0 || d < best) { best = d; }
        }
        return best;
    }

    // Flat-Earth great-circle approximation in metres.
    hidden function distM(lat1, lon1, lat2, lon2) {
        var dlat = lat1 - lat2;
        var dlon = lon1 - lon2;
        var cosLat = Math.cos(lat1 * Math.PI / 180.0f);
        return Math.sqrt((dlat * 111111.0f) * (dlat * 111111.0f)
                       + (dlon * 111111.0f * cosLat) * (dlon * 111111.0f * cosLat));
    }

    hidden function updateCurrentSegment() {
        var ci = activeClimbIndex;
        if (ci < 0) { return; }
        var cumDist = 0;
        for (var s = 0; s < segCount[ci]; s++) {
            cumDist += segDist[ci][s];
            if (progressInClimb <= cumDist) {
                activeSegmentIndex = s;
                return;
            }
        }
        activeSegmentIndex = segCount[ci] - 1;
    }

    // Parses wire "gh" (issue #178): [stepM, sec1, ..., secN] -> ghostStep + cumulative
    // ghostCum. Anything malformed (not an Array of non-negative Numbers, step <= 0, no step)
    // clears the route ghost. More than MAX_GHOST_STEPS steps are cut off.
    function setRouteGhost(gh) {
        ghostStep = 0;
        ghostCum = null;
        routeGhostDeltaSec = null;
        if (gh == null || !(gh instanceof Toybox.Lang.Array) || gh.size() < 2) { return; }
        for (var i = 0; i < gh.size(); i++) {
            if (!(gh[i] instanceof Toybox.Lang.Number) || gh[i] < 0) { return; }
        }
        if (gh[0] <= 0) { return; }
        var n = gh.size() - 1;
        if (n > MAX_GHOST_STEPS) { n = MAX_GHOST_STEPS; }
        var cum = new [n + 1];
        cum[0] = 0;
        for (var k = 1; k <= n; k++) { cum[k] = cum[k - 1] + gh[k]; }
        ghostStep = gh[0];
        ghostCum = cum;
    }

    // Forgets where the opponent started (new route / new ride).
    function resetRouteGhostAnchor() {
        ghostAnchorMs = -1;
        ghostAnchorRef = 0.0;
        routeGhostDeltaSec = null;
    }

    // Reference seconds of the best ride at route distance dist (m), linearly interpolated
    // within a step; -1 without a route ghost. The last step ends at the route length ("rtl")
    // when that falls inside it, so a shorter final step is timed correctly.
    function routeGhostSecAt(dist) {
        if (ghostCum == null || ghostStep <= 0) { return -1; }
        var n = ghostCum.size() - 1;
        if (dist <= 0) { return 0.0; }
        var k = (dist / ghostStep).toNumber();
        var start = k * ghostStep;
        var end = start + ghostStep;
        if (k >= n - 1) {
            k = n - 1;
            start = k * ghostStep;
            end = n * ghostStep;
            if (routeTotalLen > start && routeTotalLen < end) { end = routeTotalLen; }
            if (dist >= end) { return ghostCum[n].toFloat(); }
        }
        var frac = (dist - start).toFloat() / (end - start).toFloat();
        return ghostCum[k] + (ghostCum[k + 1] - ghostCum[k]) * frac;
    }

    // Seconds behind (+) or ahead (-) of the best ride at route distance dist, or null when
    // there is nothing to compare: no route ghost, radius mode, off-route, or the timer not
    // running. The opponent starts alongside the rider at the first valid tick (anchor), so
    // joining the route late or starting the recording early does not skew the delta.
    function routeGhostDelta(timerMs, dist) {
        if (ghostCum == null || mode == null || !mode.equals("route")) { return null; }
        if (offRoute || timerMs == null || timerMs <= 0) { return null; }
        var ref = routeGhostSecAt(dist);
        if (ref < 0) { return null; }
        if (ghostAnchorMs < 0) {
            ghostAnchorMs = timerMs;
            ghostAnchorRef = ref;
            return 0;
        }
        var actual = (timerMs - ghostAnchorMs) / 1000.0;
        return (actual - (ref - ghostAnchorRef)).toNumber();
    }

    // Cumulative target seconds at the current progressInClimb for the active climb,
    // linearly interpolated within the running segment. Returns -1 when no targets.
    function targetSecondsAt() {
        var ci = activeClimbIndex;
        if (ci < 0 || !hasTargets[ci]) { return -1; }
        var cum = 0;            // cumulative target seconds for completed segments
        var cumDist = 0;        // cumulative distance at end of completed segments
        for (var s = 0; s < segCount[ci]; s++) {
            var segLen = segDist[ci][s];
            var segEnd = cumDist + segLen;
            if (progressInClimb <= segEnd || s == segCount[ci] - 1) {
                var into = progressInClimb - cumDist;
                if (into < 0) { into = 0; }
                if (into > segLen) { into = segLen; }
                var frac = (segLen > 0) ? (into.toFloat() / segLen.toFloat()) : 0.0;
                return cum + (segTargetSec[ci][s] * frac);
            }
            cum += segTargetSec[ci][s];
            cumDist = segEnd;
        }
        return cum;
    }

    // Cumulative PR reference seconds at the current progressInClimb for the active climb,
    // linearly interpolated within the running segment. Returns -1 when no PR reference.
    // Mirrors targetSecondsAt(); kept separate because tsec (manual pacing plan) and
    // refsec (per-segment PR) are independent and may both be present.
    function refSecondsAt() {
        var ci = activeClimbIndex;
        if (ci < 0 || !hasRefTargets[ci]) { return -1; }
        var cum = 0;            // cumulative reference seconds for completed segments
        var cumDist = 0;        // cumulative distance at end of completed segments
        for (var s = 0; s < segCount[ci]; s++) {
            var segLen = segDist[ci][s];
            var segEnd = cumDist + segLen;
            if (progressInClimb <= segEnd || s == segCount[ci] - 1) {
                var into = progressInClimb - cumDist;
                if (into < 0) { into = 0; }
                if (into > segLen) { into = segLen; }
                var frac = (segLen > 0) ? (into.toFloat() / segLen.toFloat()) : 0.0;
                return cum + (segRefSec[ci][s] * frac);
            }
            cum += segRefSec[ci][s];
            cumDist = segEnd;
        }
        return cum;
    }

    /**
     * Pure calculation (issue #180): where does the current power sit relative to the band?
     * Returns ZONE_UNDER / ZONE_IN / ZONE_OVER, or ZONE_NONE when power is null (no power
     * meter) so the view shows just the target. Band edges are inclusive.
     */
    function powerZone(power, low, high) {
        if (power == null) { return ZONE_NONE; }
        if (power < low) { return ZONE_UNDER; }
        if (power > high) { return ZONE_OVER; }
        return ZONE_IN;
    }

    // Zone for climb ci's interval block; ZONE_NONE when the climb has no block.
    function blockZone(ci, power) {
        if (ci < 0 || ci >= climbCount || !hasBlock[ci]) { return ZONE_NONE; }
        return powerZone(power, blockLow[ci], blockHigh[ci]);
    }

    /**
     * True when climb ci's interval block is done: the rider left the climb at or past its
     * end (reached the top), not by skipping it or going off-route before the summit.
     * axisDist is the route distance used for progress (see chooseAxis()).
     */
    function blockFinished(ci, axisDist) {
        if (ci < 0 || ci >= climbCount || !hasBlock[ci]) { return false; }
        return axisDist >= climbEndDist[ci];
    }

    /**
     * Pure calculation: remaining distance (m) to the climb end at the given current
     * speed (m/s) -> whole seconds to arrival. This is deliberately a function of its
     * parameters only (no instance state) so it stays trivial to unit test.
     * Returns -1 when speed is null/near-zero (too noisy/stopped for a meaningful
     * estimate) so callers can show a placeholder instead of a huge or divide-by-near-
     * zero ETA. remainingM <= 0 always returns 0 (already there / past the summit).
     */
    function etaSeconds(remainingM, speedMps) {
        if (remainingM <= 0) { return 0; }
        if (speedMps == null || speedMps < MIN_ETA_SPEED_MPS) { return -1; }
        return (remainingM / speedMps).toNumber();
    }

    /**
     * Pure calculation: will the battery plausibly run out before the climb ends?
     * (issue #49 -- battery-vs-remaining-climb-time warning.)
     *
     * remainingClimbSec: ETA to the summit in seconds, as returned by etaSeconds().
     * A negative value means the ETA itself is unknown (speed too low/noisy/stopped);
     * in that case we deliberately do NOT warn -- an unreliable ETA is not a sound
     * basis for a battery alarm, and it also keeps the alert quiet while stationary.
     *
     * batteryPct: Toybox.System.Stats.battery (0-100 float). null or <= 0 is treated
     * as unknown/invalid and also suppresses the warning rather than false-alarming.
     *
     * Like etaSeconds(), this is a function of its parameters only (no instance
     * state), so it stays trivial to unit test.
     */
    function batteryInsufficientForClimb(remainingClimbSec, batteryPct) {
        if (remainingClimbSec < 0) { return false; }
        if (batteryPct == null || batteryPct <= 0) { return false; }
        var batterySecRemaining = (batteryPct / BATTERY_DRAIN_PCT_PER_HOUR) * 3600.0;
        return batterySecRemaining < (remainingClimbSec * BATTERY_WARNING_MARGIN);
    }
}
