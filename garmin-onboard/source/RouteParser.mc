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

    // Full pipeline: smooth -> detect+trim -> segment. Fills `data` from `store`.
    function parse(store, data) {
        data.reset();
        if (store == null || !store.complete || store.pointCount < 2) { return; }
        data.store = store;
        smoothEle(store);
        detectClimbs(store, data);
        for (var c = 0; c < data.climbCount; c++) {
            segmentClimb(store, data, c);
        }
        data.parsed = true;
    }

    // Port of ClimbDetector.detect: sliding scan, extend to the elevation peak
    // with a 20 m downhill tolerance, validate >= 800 m and >= 3%, then trim
    // false flat and store the climb rebuilt from the trimmed indices.
    function detectClimbs(store, data) {
        var n = store.pointCount;
        if (n < 2) { return; }
        var i = 0;
        while (i < n - 1 && data.climbCount < data.MAX_CLIMBS) {
            if (grad(store, i, i + 1) <= 0) {
                i++;
                continue;
            }
            var startIdx = i;
            var peakEle = store.ele[i];
            var peakIdx = i;
            var j = i + 1;
            while (j < n) {
                var e = store.ele[j];
                if (e > peakEle) {
                    peakEle = e;
                    peakIdx = j;
                } else if (peakEle - e > DOWNHILL_TOLERANCE_M) {
                    break;
                }
                j++;
            }
            var endIdx = peakIdx;
            if (endIdx <= startIdx) {
                i++;
                continue;
            }
            var len = store.dist[endIdx] - store.dist[startIdx];
            var gain = store.ele[endIdx] - store.ele[startIdx];
            if (len < MIN_CLIMB_LENGTH_M || gain <= 0) {
                i = endIdx + 1;
                continue;
            }
            if (gain / len < MIN_AVG_GRADIENT) {
                i = endIdx + 1;
                continue;
            }
            var trimmed = trimFalseFlat(store, startIdx, endIdx);
            var s = trimmed[0];
            var e2 = trimmed[1];
            var c = data.climbCount;
            var tl = store.dist[e2] - store.dist[s];
            var tg = store.ele[e2] - store.ele[s];
            data.climbStartIdx[c]  = s;
            data.climbEndIdx[c]    = e2;
            data.climbStartDist[c] = Math.round(store.dist[s]).toNumber();
            data.climbEndDist[c]   = Math.round(store.dist[e2]).toNumber();
            data.climbLength[c]    = Math.round(tl).toNumber();
            data.climbElevGain[c]  = Math.round(tg).toNumber();
            data.climbAvgGrad[c]   = (tl > 0) ? Math.round((tg / tl) * 1000.0).toNumber() : 0;
            data.climbCount = c + 1;
            // Advance past the ORIGINAL (untrimmed) end, like the phone detector.
            i = endIdx + 1;
        }
    }

    // Port of ClimbTrimmer.trim, index-based. Returns [startIdx, endIdx].
    // A lead-in/lead-out below 2% is only trimmed when it is >= 200 m long AND
    // the remaining climb stays >= 800 m.
    function trimFalseFlat(store, startIdx, endIdx) {
        if (endIdx - startIdx < 2) { return [startIdx, endIdx]; }
        if (store.dist[endIdx] - store.dist[startIdx] <= 0) { return [startIdx, endIdx]; }
        var start = startIdx;
        var end = endIdx;
        var s = startIdx;
        while (s < endIdx && grad(store, s, s + 1) < FALSE_FLAT_MAX_GRADIENT) {
            s++;
        }
        var lead = store.dist[s] - store.dist[startIdx];
        var remainAfterLead = store.dist[endIdx] - store.dist[s];
        if (s > startIdx
                && lead >= FALSE_FLAT_MIN_LENGTH_M
                && remainAfterLead >= MIN_CLIMB_LENGTH_M) {
            start = s;
        }
        var e = endIdx;
        while (e > start && grad(store, e - 1, e) < FALSE_FLAT_MAX_GRADIENT) {
            e--;
        }
        var tail = store.dist[endIdx] - store.dist[e];
        var remainAfterTail = store.dist[e] - store.dist[start];
        if (e < endIdx
                && tail >= FALSE_FLAT_MIN_LENGTH_M
                && remainAfterTail >= MIN_CLIMB_LENGTH_M) {
            end = e;
        }
        return [start, end];
    }

    // Replaced with the real segmenter in the next task.
    function segmentClimb(store, data, c) {
    }
}
