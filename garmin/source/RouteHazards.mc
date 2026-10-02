// Issue #203: tunnels and technical descents along the route. The phone detects them and
// sends the packed route-level "hz" array [startM, endM, type, ...] (type 0 = tunnel,
// 1 = technical descent); the datafield only parses it and does a cheap per-tick lookup.
//
// Pure helpers only (no Activity/Attention access) so parsing and the look-ahead are
// unit-testable -- see garmin/test/RouteHazardsTest.mc. ClimbProView feeds them.

const HAZARD_TUNNEL = 0;
const HAZARD_DESCENT = 1;
const HAZARD_MAX = 32;            // phone caps at 32 hazards (96 ints)
const HAZARD_LOOKAHEAD_M = 400;   // warn this far ahead of a hazard

// Validates the raw "hz" value: an Array of Numbers, a multiple of 3 long, each hazard with
// 0 <= start < end and a known type. Returns a new packed Array (at most HAZARD_MAX hazards)
// or null when absent/malformed, so a bad payload can never leave stale markers behind.
function parseHazards(raw) {
    if (raw == null || !(raw instanceof Toybox.Lang.Array)) { return null; }
    var n = raw.size() / 3;
    if (n == 0 || raw.size() % 3 != 0) { return null; }
    if (n > HAZARD_MAX) { n = HAZARD_MAX; }
    var out = new [n * 3];
    for (var i = 0; i < n; i++) {
        var s = raw[i * 3];
        var e = raw[i * 3 + 1];
        var t = raw[i * 3 + 2];
        if (!(s instanceof Toybox.Lang.Number) || !(e instanceof Toybox.Lang.Number)
                || !(t instanceof Toybox.Lang.Number)) {
            return null;
        }
        if (s < 0 || e <= s || (t != HAZARD_TUNNEL && t != HAZARD_DESCENT)) { return null; }
        out[i * 3] = s;
        out[i * 3 + 1] = e;
        out[i * 3 + 2] = t;
    }
    return out;
}

// Index of the hazard the rider is in, or the first one starting within lookaheadM ahead of
// posM; -1 when none. Hazards are ordered by start, so the scan stops early.
function hazardAt(hz, posM, lookaheadM) {
    if (hz == null || posM == null) { return -1; }
    var n = hz.size() / 3;
    for (var i = 0; i < n; i++) {
        var s = hz[i * 3];
        var e = hz[i * 3 + 1];
        if (posM >= s && posM < e) { return i; }
        if (s > posM) {
            return (s - posM <= lookaheadM) ? i : -1;
        }
    }
    return -1;
}

// Banner text: "TUNNEL 350m - LICHT" ahead, "TUNNEL - LICHT AAN" inside; same for descents.
// "Inside" is decided from the raw position; only the distance shown ahead is rounded up to
// 50 m steps, so the text changes every 50 m instead of every tick. units = payload "un"
// bitmask (ft/mi when imperial). Returns null when i is not a hazard in hz -- a resync can
// replace hz between compute() and onUpdate().
function hazardLabel(hz, i, posM, units) {
    if (hz == null || i < 0 || i * 3 + 2 >= hz.size()) { return null; }
    var t = hz[i * 3 + 2];
    var dist = hz[i * 3] - posM;
    var ahead = (dist > 0) ? Units.formatDist(((dist + 49) / 50) * 50, units) : null;
    if (t == HAZARD_TUNNEL) {
        return (ahead != null) ? "TUNNEL " + ahead + " - LICHT" : "TUNNEL - LICHT AAN";
    }
    return (ahead != null) ? "TECHN. AFDALING " + ahead : "TECHN. AFDALING";
}
