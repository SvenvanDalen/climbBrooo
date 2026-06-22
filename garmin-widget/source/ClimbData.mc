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
    const MAX_FLAT_STARRED = 16;

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

    // Flat starred segments (specialized Strava starred segments, route mode only)
    var flatStarredCount = 0;
    var flatStarredStart;   // start distance (m)
    var flatStarredEnd;     // end distance (m)
    var flatStarredSurf;    // surface type 0-4 (5=unknown)
    var flatStarredName;    // String or null

    // Runtime state (set by RouteTracker)
    var activeClimbIndex = -1;     // -1 = not on a climb
    var activeSegmentIndex = -1;   // current segment within active climb
    var progressInClimb = 0;       // meters into the active climb
    var distToNextClimb = -1;      // meters to the next climb start (-1 = unknown)
    var nextClimbIndex = -1;       // index of next upcoming climb
    var lastElapsedDistance = 0;  // last GPS elapsed distance passed to updateProgress

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
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
                segSurf[i][s] = 5; // UNKNOWN
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

        flatStarredStart = new [MAX_FLAT_STARRED];
        flatStarredEnd   = new [MAX_FLAT_STARRED];
        flatStarredSurf  = new [MAX_FLAT_STARRED];
        flatStarredName  = new [MAX_FLAT_STARRED];
        for (var i = 0; i < MAX_FLAT_STARRED; i++) {
            flatStarredStart[i] = 0;
            flatStarredEnd[i]   = 0;
            flatStarredSurf[i]  = 5;
            flatStarredName[i]  = null;
        }
    }

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
                activeClimbIndex = i;
                progressInClimb = elapsedDistance - climbStartDist[i];

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

                if (i + 1 < climbCount) {
                    nextClimbIndex = i + 1;
                    distToNextClimb = climbStartDist[i + 1] - elapsedDistance;
                }
                return;
            } else if (elapsedDistance < climbStartDist[i]) {
                nextClimbIndex = i;
                distToNextClimb = climbStartDist[i] - elapsedDistance;
                return;
            }
        }
    }

    function checkCalibration(lat, lon) {
        if (activeClimbIndex < 0) { return; }
        var ci = activeClimbIndex;
        var k  = calibIdx[ci];
        if (k >= calibCount[ci]) { return; }

        var dlat = lat - calibLat[ci][k];
        var dlon = lon - calibLon[ci][k];
        var cosLat = Math.cos(lat * Math.PI / 180.0f);
        var dm = Math.sqrt((dlat * 111111.0f) * (dlat * 111111.0f)
                         + (dlon * 111111.0f * cosLat) * (dlon * 111111.0f * cosLat));
        if (dm < 30.0f) {
            climbStartDist[ci] = lastElapsedDistance - calibDist[ci][k];
            progressInClimb    = calibDist[ci][k];
            calibIdx[ci] = k + 1;
            updateCurrentSegment();
        }
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
}
