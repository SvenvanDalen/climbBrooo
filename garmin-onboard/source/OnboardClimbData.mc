using Toybox.System as Sys;
using Toybox.Math as Math;

/**
 * Results of the on-watch parse: parallel arrays per climb/segment (same
 * layout philosophy as the datafield's ClimbData — minimal allocation).
 * Task 6 adds live GPS tracking state and matching on top of this container.
 */
class OnboardClimbData {

    const MAX_CLIMBS = 16;
    const MAX_SEGMENTS = 16;
    const HYSTERESIS_M = 20;       // reject backwards progress jumps (GPS jitter)
    const OFFROUTE_M = 100;        // nearest-line distance beyond this = off route
    const ALERT_RADIUS_M = 50;     // climb-start alert window
    const SEARCH_WINDOW_PTS = 120; // vertices searched around the last match (~3 km at 25 m spacing)
    const OFFROUTE_RESCAN_TICKS = 20;  // full-route rescans only this often while lost

    var parsed = false;
    var store = null;          // RawRouteStore backing this parse

    var climbCount = 0;
    var climbStartDist;   // m from route start (trimmed)
    var climbEndDist;     // m from route start (trimmed)
    var climbLength;      // m
    var climbElevGain;    // m
    var climbAvgGrad;     // pct x 10
    var climbStartIdx;    // store point index (trimmed)
    var climbEndIdx;      // store point index (trimmed)

    var segCount;
    var segDist;          // [climb][segment] m
    var segElevGain;      // [climb][segment] m
    var segGradient;      // [climb][segment] pct x 10
    var segColor;         // [climb][segment] 0-5

    // Live tracking state
    var routeProgress = 0.0;       // m along the route
    var lastMatchIdx = -1;         // route vertex of the last match (-1 = never)
    var activeClimbIndex = -1;
    var activeSegmentIndex = -1;
    var progressInClimb = 0;
    var distToNextClimb = -1;
    var nextClimbIndex = -1;
    var offRoute = false;
    var offRouteTickCount = 0;     // ticks since off-route was declared or last full rescan
    var alertedClimb;              // Boolean per climb, never cleared during a ride
    var pendingAlert = false;

    // TEMP diagnostics for the "5 km window doesn't update on a real ride"
    // report — remove once root cause is confirmed. computeTicks proves
    // compute(info) is firing at all; fixTicks proves info.currentLocation
    // is non-null; lastLat/lastLon are the raw fix, independent of matching.
    var computeTicks = 0;
    var fixTicks = 0;
    var lastLat = 0.0;
    var lastLon = 0.0;

    function initialize() {
        climbStartDist = new [MAX_CLIMBS];
        climbEndDist   = new [MAX_CLIMBS];
        climbLength    = new [MAX_CLIMBS];
        climbElevGain  = new [MAX_CLIMBS];
        climbAvgGrad   = new [MAX_CLIMBS];
        climbStartIdx  = new [MAX_CLIMBS];
        climbEndIdx    = new [MAX_CLIMBS];
        segCount       = new [MAX_CLIMBS];
        segDist        = new [MAX_CLIMBS];
        segElevGain    = new [MAX_CLIMBS];
        segGradient    = new [MAX_CLIMBS];
        segColor       = new [MAX_CLIMBS];
        for (var i = 0; i < MAX_CLIMBS; i++) {
            segDist[i]     = new [MAX_SEGMENTS];
            segElevGain[i] = new [MAX_SEGMENTS];
            segGradient[i] = new [MAX_SEGMENTS];
            segColor[i]    = new [MAX_SEGMENTS];
        }
        alertedClimb = new [MAX_CLIMBS];
        reset();
    }

    function reset() {
        parsed = false;
        store = null;
        climbCount = 0;
        for (var i = 0; i < MAX_CLIMBS; i++) {
            climbStartDist[i] = 0;
            climbEndDist[i] = 0;
            climbLength[i] = 0;
            climbElevGain[i] = 0;
            climbAvgGrad[i] = 0;
            climbStartIdx[i] = 0;
            climbEndIdx[i] = 0;
            segCount[i] = 0;
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
            }
        }
        routeProgress = 0.0;
        lastMatchIdx = -1;
        activeClimbIndex = -1;
        activeSegmentIndex = -1;
        progressInClimb = 0;
        distToNextClimb = -1;
        nextClimbIndex = -1;
        offRoute = false;
        offRouteTickCount = 0;
        pendingAlert = false;
        for (var a = 0; a < MAX_CLIMBS; a++) { alertedClimb[a] = false; }
    }

    // Match a GPS fix to the route polyline and update progress/climb state.
    // Windowed search around the last match keeps per-tick cost low; a full
    // scan runs on the first fix or before declaring off-route.
    function updatePosition(latDeg, lonDeg) {
        if (!parsed || store == null || store.pointCount < 2) { return; }
        var n = store.pointCount;
        var lo = 0;
        var hi = n - 2;
        if (lastMatchIdx >= 0) {
            lo = lastMatchIdx - SEARCH_WINDOW_PTS;
            if (lo < 0) { lo = 0; }
            hi = lastMatchIdx + SEARCH_WINDOW_PTS;
            if (hi > n - 2) { hi = n - 2; }
        }
        var m = bestMatch(latDeg, lonDeg, lo, hi);
        if (m[0] > OFFROUTE_M && (lo > 0 || hi < n - 2)) {
            if (!offRoute || offRouteTickCount >= OFFROUTE_RESCAN_TICKS) {
                m = bestMatch(latDeg, lonDeg, 0, n - 2);   // widen before giving up
                offRouteTickCount = 0;
            } else {
                offRouteTickCount++;
            }
        }
        if (m[0] > OFFROUTE_M) {
            offRoute = true;
            return;
        }
        offRoute = false;
        offRouteTickCount = 0;
        var idx = m[1];
        var prog = store.dist[idx]
                 + (store.dist[idx + 1] - store.dist[idx]) * m[2];
        if (lastMatchIdx >= 0 && prog < routeProgress - HYSTERESIS_M) {
            return;   // backwards jump beyond the hysteresis window: jitter
        }
        if (prog > routeProgress) {
            routeProgress = prog;
        }
        lastMatchIdx = idx;
        updateProgress();
    }

    // Nearest projection of (lat,lon) onto route segments [lo..hi].
    // Returns [distanceM, segIdx, tAlongSeg].
    hidden function bestMatch(latDeg, lonDeg, lo, hi) {
        var cosLat = Math.cos(latDeg * Math.PI / 180.0);
        var best = -1.0;
        var bestIdx = lo;
        var bestT = 0.0;
        for (var i = lo; i <= hi; i++) {
            var px = (lonDeg - store.lon[i]) * 111111.0 * cosLat;
            var py = (latDeg - store.lat[i]) * 111111.0;
            var bx = (store.lon[i + 1] - store.lon[i]) * 111111.0 * cosLat;
            var by = (store.lat[i + 1] - store.lat[i]) * 111111.0;
            var len2 = bx * bx + by * by;
            var t = (len2 > 0) ? ((px * bx + py * by) / len2) : 0.0;
            if (t < 0) { t = 0.0; }
            if (t > 1) { t = 1.0; }
            var dx = px - bx * t;
            var dy = py - by * t;
            var dm = Math.sqrt(dx * dx + dy * dy);
            if (best < 0 || dm < best) {
                best = dm;
                bestIdx = i;
                bestT = t;
            }
        }
        return [best, bestIdx, bestT];
    }

    // Derive active climb/segment and next-climb info from routeProgress.
    function updateProgress() {
        activeClimbIndex = -1;
        activeSegmentIndex = -1;
        progressInClimb = 0;
        distToNextClimb = -1;
        nextClimbIndex = -1;
        for (var i = 0; i < climbCount; i++) {
            if (routeProgress > climbEndDist[i]) {
                continue;
            }
            if (routeProgress >= climbStartDist[i]) {
                activeClimbIndex = i;
                progressInClimb = (routeProgress - climbStartDist[i]).toNumber();
                var cum = 0;
                for (var s = 0; s < segCount[i]; s++) {
                    cum += segDist[i][s];
                    if (progressInClimb <= cum) {
                        activeSegmentIndex = s;
                        break;
                    }
                }
                if (activeSegmentIndex == -1) {
                    activeSegmentIndex = segCount[i] - 1;
                }
                if (!alertedClimb[i]) {
                    alertedClimb[i] = true;   // entered without passing the window
                    pendingAlert = true;
                }
                if (i + 1 < climbCount) {
                    nextClimbIndex = i + 1;
                    distToNextClimb = (climbStartDist[i + 1] - routeProgress).toNumber();
                }
                return;
            }
            nextClimbIndex = i;
            distToNextClimb = (climbStartDist[i] - routeProgress).toNumber();
            if (distToNextClimb <= ALERT_RADIUS_M && !alertedClimb[i]) {
                alertedClimb[i] = true;   // once per climb; drift-back can't re-arm
                pendingAlert = true;
            }
            return;
        }
    }

    // One-shot consumer for the view: true exactly once per triggered alert.
    function takeAlert() {
        if (pendingAlert) {
            pendingAlert = false;
            return true;
        }
        return false;
    }
}
