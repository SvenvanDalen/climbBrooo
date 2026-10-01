using Toybox.Test;
using Toybox.Application as App;

// Issue #203: tunnels and technical descents. Covers the "hz" validation (shape, types,
// ranges, cap), the per-tick look-ahead/inside lookup, the banner labels, and that a resync
// without "hz" clears stale markers.

(:test)
function hz_parse_validPacked(logger) {
    var hz = parseHazards([3400, 3650, 0, 5200, 6100, 1]);
    Test.assert(hz != null);
    Test.assertEqual(hz.size(), 6);
    Test.assertEqual(hz[0], 3400);
    Test.assertEqual(hz[5], HAZARD_DESCENT);
    return true;
}

(:test)
function hz_parse_rejectsMalformed(logger) {
    Test.assert(parseHazards(null) == null);
    Test.assert(parseHazards("x") == null);
    Test.assert(parseHazards([]) == null);
    Test.assert(parseHazards([100, 200]) == null);              // not a multiple of 3
    Test.assert(parseHazards([200, 100, 0]) == null);           // end <= start
    Test.assert(parseHazards([-5, 100, 0]) == null);            // negative start
    Test.assert(parseHazards([100, 200, 7]) == null);           // unknown type
    Test.assert(parseHazards([100, 200, "0"]) == null);         // non-number
    return true;
}

(:test)
function hz_parse_capsAtMax(logger) {
    var raw = new [(HAZARD_MAX + 5) * 3];
    for (var i = 0; i < HAZARD_MAX + 5; i++) {
        raw[i * 3] = i * 1000;
        raw[i * 3 + 1] = i * 1000 + 100;
        raw[i * 3 + 2] = HAZARD_TUNNEL;
    }
    Test.assertEqual(parseHazards(raw).size(), HAZARD_MAX * 3);
    return true;
}

(:test)
function hz_lookup_aheadInsideAndPast(logger) {
    var hz = [3400, 3650, 0, 5200, 6100, 1];
    Test.assertEqual(hazardAt(hz, 2000, 400), -1);   // 1400 m ahead: too far
    Test.assertEqual(hazardAt(hz, 3000, 400), 0);    // exactly 400 m ahead
    Test.assertEqual(hazardAt(hz, 3500, 400), 0);    // inside the tunnel
    Test.assertEqual(hazardAt(hz, 3650, 400), -1);   // at its end, next is 1550 m away
    Test.assertEqual(hazardAt(hz, 5000, 400), 1);
    Test.assertEqual(hazardAt(hz, 7000, 400), -1);   // past everything
    Test.assertEqual(hazardAt(null, 3500, 400), -1);
    return true;
}

(:test)
function hz_labels(logger) {
    var hz = [3400, 3650, 0, 5200, 6100, 1];
    Test.assertEqual(hazardLabel(hz, 0, 3100), "TUNNEL 300m - LICHT");
    Test.assertEqual(hazardLabel(hz, 0, 3500), "TUNNEL - LICHT AAN");
    Test.assertEqual(hazardLabel(hz, 1, 4850), "TECHN. AFDALING 350m");
    Test.assertEqual(hazardLabel(hz, 1, 5300), "TECHN. AFDALING");
    Test.assertEqual(hazardDisplayPos(3123), 3100);
    return true;
}

(:test)
function hz_resyncWithoutHzClearsMarkers(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());
    Test.assert(d.hazards != null);
    var p = fullRoutePayload();
    p.remove("hz");
    new PhoneMessageCallback().onMessage(p);
    Test.assert(d.hazards == null);
    return true;
}
