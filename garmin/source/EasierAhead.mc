// Issue #213: "vlakker stuk" notice during a climb. Watch-only, no wire format change: the
// already-synced per-segment gradients (segGradient, pct x 10) and lengths (segDist) are
// enough to see that the segments right after the current one are markedly less steep.
//
// Pure logic only (no Dc/Attention/Properties access) so it is unit-testable -- see
// garmin/test/EasierAheadTest.mc. ClimbProView feeds it once per tick and draws the banner.

// Finds the easier stretch that starts directly after segment `fromSeg`: the contiguous run
// of segments from fromSeg + 1 whose gradient is at least `dropX10` (pct x 10) below the
// gradient of fromSeg. Returns [startOffsetM, lengthM, startSeg] (offset measured from the
// climb start) when that run is at least minLenM long, otherwise null.
function findEasierStretch(segDist, segGrad, segCount, fromSeg, dropX10, minLenM) {
    if (segDist == null || segGrad == null || fromSeg < 0 || fromSeg + 1 >= segCount) {
        return null;
    }
    var limit = segGrad[fromSeg] - dropX10;
    var start = 0;
    for (var s = 0; s <= fromSeg; s++) { start += segDist[s]; }
    var len = 0;
    for (var s = fromSeg + 1; s < segCount; s++) {
        if (segGrad[s] > limit) { break; }
        len += segDist[s];
    }
    if (len < minLenM) { return null; }
    return [start, len, fromSeg + 1];
}

// Banner text, e.g. "300 m vlakker" / "1.2 km vlakker". Lengths are rounded to 50 m (at
// least 50 m) so the notice reads as an estimate, not a false-precision number.
function easierAheadLabel(lenM) {
    if (lenM >= 1000) {
        var hm = (lenM + 50) / 100;          // round to 100 m
        return (hm / 10) + "." + (hm % 10) + " km vlakker";
    }
    var r = ((lenM + 25) / 50) * 50;
    if (r < 50) { r = 50; }
    return r + " m vlakker";
}

// Per-ride state for the notice. The candidate stretch is only recomputed when the active
// (climb, segment) pair changes, so the per-tick cost is a couple of comparisons.
//
// Idempotency mirrors the climb-start alert: the alert fires once per (climb, start
// segment) key and is latched. Once shown, the banner stays up until the rider enters the
// easier stretch (the active segment changes), so GPS jitter back across the lookahead
// boundary can neither hide it nor re-fire the vibration.
class EasierAheadTracker {
    const DROP_X10 = 30;        // "markedly less steep": >= 3.0 percentage points easier
    const MIN_LEN_M = 200;      // same length floor as the false-flat trim rule
    const LOOKAHEAD_M = 150;    // announce this far before the easier stretch starts
    const START_QUIET_M = 50;   // stay silent inside the climb-start alert window

    var shownLenM = null;       // length to show in the banner; null = no banner

    hidden var candClimb = -1;
    hidden var candSeg = -1;
    hidden var cand = null;     // [startOffsetM, lengthM, startSeg] or null
    hidden var alertedClimb = -1;
    hidden var alertedSeg = -1;

    function initialize() {
    }

    // New route / payload: forget everything, including the latch.
    function reset() {
        shownLenM = null;
        candClimb = -1;
        candSeg = -1;
        cand = null;
        alertedClimb = -1;
        alertedSeg = -1;
    }

    // ci/activeSeg/progressM: the active climb, its active segment and metres into it (ci < 0
    // when not on a climb). segDist/segGrad/segCount: that climb's segment arrays. enabled:
    // the app setting. Returns true exactly once per easier stretch, when the notice first
    // appears -- the caller then vibrates.
    function update(ci, activeSeg, progressM, segDist, segGrad, segCount, offRoute, enabled) {
        if (ci < 0 || activeSeg < 0 || !enabled) {
            shownLenM = null;
            candClimb = -1;
            candSeg = -1;
            cand = null;
            return false;
        }
        if (ci != candClimb || activeSeg != candSeg) {
            candClimb = ci;
            candSeg = activeSeg;
            cand = findEasierStretch(segDist, segGrad, segCount, activeSeg, DROP_X10, MIN_LEN_M);
            shownLenM = null;
        }
        if (cand == null || offRoute) {
            shownLenM = null;
            return false;
        }
        var latched = (alertedClimb == ci && alertedSeg == cand[2]);
        if (latched) {
            shownLenM = cand[1];
            return false;
        }
        if (progressM > START_QUIET_M && cand[0] - progressM <= LOOKAHEAD_M) {
            alertedClimb = ci;
            alertedSeg = cand[2];
            shownLenM = cand[1];
            return true;
        }
        shownLenM = null;
        return false;
    }
}
