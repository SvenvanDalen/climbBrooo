using Toybox.System as Sys;

/**
 * Flat data store for all climb/segment data received from the phone.
 * Uses parallel arrays to minimize object allocation on the watch.
 * Memory budget: ~4KB for 8 climbs × 16 segments.
 */
class ClimbData {

    // Limits
    const MAX_CLIMBS = 8;
    const MAX_SEGMENTS = 20;

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

    // Segment-level arrays (indexed [climb][segment])
    var segCount;         // segments per climb
    var segDist;          // segment distance (m)
    var segElevGain;      // segment elevation gain (m)
    var segGradient;      // segment gradient fixed-point (pct×10)
    var segColor;         // color index 0-5

    // Runtime state (set by RouteTracker)
    var activeClimbIndex = -1;     // -1 = not on a climb
    var activeSegmentIndex = -1;   // current segment within active climb
    var progressInClimb = 0;       // meters into the active climb
    var distToNextClimb = -1;      // meters to the next climb start (-1 = unknown)
    var nextClimbIndex = -1;       // index of next upcoming climb

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
            for (var s = 0; s < MAX_SEGMENTS; s++) {
                segDist[i][s] = 0;
                segElevGain[i][s] = 0;
                segGradient[i][s] = 0;
                segColor[i][s] = 0;
            }
        }
    }

    /**
     * Given elapsed distance along the route, determine active climb and segment.
     * Updates activeClimbIndex, activeSegmentIndex, progressInClimb, distToNextClimb.
     */
    function updateProgress(elapsedDistance) {
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
}
