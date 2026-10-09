using Toybox.Lang;

// Issues #7 / #9: the next climb to count down to when there is no route (radius mode) or
// an ordered day trip (radius payload with "ord": 1). Watch-only maths on the start
// coordinates the payload already carries; the distance is straight-line ("~" on screen),
// since there is no route to measure along.
//
// Pure functions only (no Dc/Properties access) so they are unit-testable -- see
// garmin/test/RadiusNextTest.mc. ClimbData.updateRadius() feeds them each GPS tick.

const RADIUS_VISIT_M = 50;     // within this of a climb start = reached (same as the start alert)
const RADIUS_SWITCH_M = 150;   // another climb must be this much closer to take over the countdown

// Marks every climb whose start is within RADIUS_VISIT_M as reached. In an ordered day trip
// reaching climb k also marks all earlier ones (the rider skipped them), so the countdown
// never jumps back. dists: metres per climb (< 0 = no start coordinate).
function radiusMarkVisited(dists, visited, count, ordered) {
    for (var i = 0; i < count; i++) {
        if (visited[i] || dists[i] < 0 || dists[i] > RADIUS_VISIT_M) { continue; }
        visited[i] = true;
        if (ordered) {
            for (var j = 0; j < i; j++) { visited[j] = true; }
        }
    }
}

// Index of the climb to count down to, or -1 when every climb was reached.
// ordered: the first climb not reached yet, in payload order.
// unordered: the nearest climb not reached yet; the current target is kept until another
// one is at least RADIUS_SWITCH_M closer, so GPS jitter between two similar distances
// can't make the screen flip back and forth.
function radiusPickTarget(dists, visited, count, ordered, current) {
    if (ordered) {
        for (var i = 0; i < count; i++) {
            if (!visited[i] && dists[i] >= 0) { return i; }
        }
        return -1;
    }
    var best = -1;
    for (var i = 0; i < count; i++) {
        if (visited[i] || dists[i] < 0) { continue; }
        if (best < 0 || dists[i] < dists[best]) { best = i; }
    }
    if (best < 0) { return -1; }
    if (current >= 0 && current < count && current != best && !visited[current]
            && dists[current] >= 0 && dists[current] - dists[best] < RADIUS_SWITCH_M) {
        return current;
    }
    return best;
}

// Number of climbs reached so far (for the "dagtocht 2/5" header).
function radiusVisitedCount(visited, count) {
    var n = 0;
    for (var i = 0; i < count; i++) {
        if (visited[i]) { n++; }
    }
    return n;
}
