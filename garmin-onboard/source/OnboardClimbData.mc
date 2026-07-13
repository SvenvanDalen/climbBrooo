using Toybox.System as Sys;

/**
 * Results of the on-watch parse: parallel arrays per climb/segment (same
 * layout philosophy as the datafield's ClimbData — minimal allocation).
 * Task 6 adds live GPS tracking state and matching on top of this container.
 */
class OnboardClimbData {

    const MAX_CLIMBS = 16;
    const MAX_SEGMENTS = 16;

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
    }
}
