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

    // Payload state
    var payloadReceived = false;
    var mode = "route";       // "route" or "radius"
    var routeId = null;
    var routeName = null;

    // Climb-level arrays (indexed by climb)
    var climbCount = 0;
    var climbStartDist;   // route mode: start distance along route (m)
    var climbEndDist;     // route mode: end distance along route (m)
    var climbLength;      // climb length (m)
    var climbElevGain;    // elevation gain (m)
    var climbAvgGrad;     // avg gradient fixed-point (pct×10)
    var climbName;        // display name (String or null)
    var climbStartLat;    // radius mode: start latitude
    var climbStartLon;    // radius mode: start longitude

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

    // Runtime state (set by RouteTracker)
    var activeClimbIndex = -1;     // -1 = not on a climb
    var activeSegmentIndex = -1;   // current segment within active climb
    var progressInClimb = 0;       // meters into the active climb
    var distToNextClimb = -1;      // meters to the next climb start (-1 = unknown)
    var nextClimbIndex = -1;       // index of next upcoming climb
    var lastElapsedDistance = 0;  // last GPS elapsed distance passed to updateProgress
    var climbStartTimerMs = -1;    // timerTime (ms) when the active climb was entered; -1 = not set
    var offRoute = false;          // true when GPS has diverged from the route near a climb

    function initialize() {
        climbStartDist = new [MAX_CLIMBS];
        climbEndDist = new [MAX_CLIMBS];
        climbLength = new [MAX_CLIMBS];
        climbElevGain = new [MAX_CLIMBS];
        climbAvgGrad = new [MAX_CLIMBS];
        climbName = new [MAX_CLIMBS];
        climbStartLat = new [MAX_CLIMBS];
        climbStartLon = new [MAX_CLIMBS];
        segCount = new [MAX_CLIMBS];

        segDist = new [MAX_CLIMBS];
        segElevGain = new [MAX_CLIMBS];
        segGradient = new [MAX_CLIMBS];
        segColor = new [MAX_CLIMBS];
        segSurf = new [MAX_CLIMBS];
        segTargetSec = new [MAX_CLIMBS];
        hasTargets = new [MAX_CLIMBS];

        for (var i = 0; i < MAX_CLIMBS; i++) {
            climbStartDist[i] = 0;
            climbEndDist[i] = 0;
            climbLength[i] = 0;
            climbElevGain[i] = 0;
            climbAvgGrad[i] = 0;
            climbName[i] = null;
            climbStartLat[i] = 0.0;
            climbStartLon[i] = 0.0;
            segCount[i] = 0;

            segDist[i] = new [MAX_SEGMENTS];
            segElevGain[i] = new [MAX_SEGMENTS];
            segGradient[i] = new [MAX_SEGMENTS];
            segColor[i] = new [MAX_SEGMENTS];
            segSurf[i] = new [MAX_SEGMENTS];
            segTargetSec[i] = new [MAX_SEGMENTS];
            hasTargets[i] = false;
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
                segSurf[i][s] = 5; // UNKNOWN
                segTargetSec[i][s] = 0;
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
            if (elapsedDistance >= climbStartDist[i] && elapsedDistance <= climbEndDist[i]) {
                // We are ON this climb
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
            } else if (elapsedDistance < climbStartDist[i]) {
                // Approaching this climb
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
        if (ni >= 0 && distToNextClimb >= 0 && distToNextClimb <= APPROACH_WINDOW_M
                && calibCount[ni] > 0) {
            var actual   = distM(lat, lon, calibLat[ni][0], calibLon[ni][0]);
            var expected = (climbStartDist[ni] + calibDist[ni][0]) - lastElapsedDistance;
            // Straight-line distance can never exceed the along-route distance on-route, so a
            // large excess means the rider has left the route.
            if (expected > 0 && actual > expected + OFFROUTE_MARGIN_M) { offRoute = true; }
            // Reached the climb's first calibration point: align its start to current elapsed
            // distance so the climb triggers at the right place despite GPS/odometer drift.
            if (actual <= APPROACH_SNAP_M) {
                climbStartDist[ni] = lastElapsedDistance - calibDist[ni][0];
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
            // Shift climbStartDist so the NEXT updateProgress() call produces the correct progress.
            // GPS says we are at lastElapsedDistance; we know we're really at calibDist[ci][k].
            climbStartDist[ci] = lastElapsedDistance - calibDist[ci][k];
            progressInClimb    = calibDist[ci][k];
            calibIdx[ci] = k + 1;
            updateCurrentSegment();
        }
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
}
