using Toybox.Math as Math;
using Toybox.System as Sys;

/**
 * On-watch route analysis pipeline — Monkey C port of the phone's
 * ElevationSmoother / ClimbDetector / ClimbTrimmer / Segmenter / GradientColor.
 * Domain rules (CLAUDE.md, non-negotiable): climb >= 800 m AND >= 3% average;
 * false flat < 2% over >= 200 m trimmed but never below 800 m; segments are
 * 8% of climb length; color cutoffs 2/4/6/8/10%.
 */
module RouteParser {

    const MIN_CLIMB_LENGTH_M     = 800;
    const MIN_AVG_GRADIENT       = 0.03;
    const DOWNHILL_TOLERANCE_M   = 20.0;
    const FALSE_FLAT_MAX_GRADIENT = 0.02;
    const FALSE_FLAT_MIN_LENGTH_M = 200;
    const SEGMENT_FRACTION       = 0.08;
    const SMOOTH_WINDOW          = 5;   // points each side; matches the phone

    // Centred moving average over the elevation channel, in place.
    function smoothEle(store) {
        var n = store.pointCount;
        if (n <= 0) { return; }
        var src = store.ele;
        var out = new [n];
        for (var i = 0; i < n; i++) {
            var lo = i - SMOOTH_WINDOW;
            if (lo < 0) { lo = 0; }
            var hi = i + SMOOTH_WINDOW;
            if (hi > n - 1) { hi = n - 1; }
            var sum = 0.0;
            var cnt = 0;
            for (var j = lo; j <= hi; j++) {
                sum += src[j];
                cnt++;
            }
            out[i] = sum / cnt;
        }
        store.ele = out;
    }

    // Point-to-point gradient between store indices a and b (fraction).
    function grad(store, a, b) {
        var d = store.dist[b] - store.dist[a];
        if (d <= 0) { return 0.0; }
        return (store.ele[b] - store.ele[a]) / d;
    }
}
