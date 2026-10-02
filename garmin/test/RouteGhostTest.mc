using Toybox.Test;
using Toybox.Application as App;
using Toybox.Lang;

// Issue #178: virtual opponent on a route. Covers the "gh" parse (shape, types, cap, resync
// clear), the interpolation at route distance (incl. a shorter last step ending at "rtl") and
// the anchored ahead/behind delta (radius mode, off-route and stopped timer give null).

function ghostPayload(gh) {
    var p = {
        "v" => 3, "mode" => "route", "routeId" => "gh", "rtl" => 1100,
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [900, 50, 55, 2] } ]
    };
    if (gh != null) { p.put("gh", gh); }
    return p;
}

function ghostData(gh) {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    new PhoneMessageCallback().onMessage(ghostPayload(gh));
    return app.climbData;
}

(:test)
function routeGhost_parse_buildsCumulative(logger) {
    var d = ghostData([250, 40, 50, 60, 70, 20]);
    Test.assertEqual(d.ghostStep, 250);
    Test.assertEqual(d.ghostCum.size(), 6);
    Test.assertEqual(d.ghostCum[0], 0);
    Test.assertEqual(d.ghostCum[2], 90);
    Test.assertEqual(d.ghostCum[5], 240);
    return true;
}

(:test)
function routeGhost_parse_malformedClears(logger) {
    Test.assert(ghostData(null).ghostCum == null);
    Test.assert(ghostData([250]).ghostCum == null);              // no step
    Test.assert(ghostData([0, 10]).ghostCum == null);            // step <= 0
    Test.assert(ghostData([250, 10, "x"]).ghostCum == null);     // not a Number
    Test.assert(ghostData([250, 10, -1]).ghostCum == null);      // negative seconds
    Test.assert(ghostData("250,10").ghostCum == null);           // not an Array
    return true;
}

(:test)
function routeGhost_parse_capsSteps(logger) {
    var gh = new [150];
    gh[0] = 250;
    for (var i = 1; i < 150; i++) { gh[i] = 10; }
    var d = ghostData(gh);
    Test.assertEqual(d.ghostCum.size(), d.MAX_GHOST_STEPS + 1);
    return true;
}

(:test)
function routeGhost_resyncWithoutGh_clears(logger) {
    var d = ghostData([250, 40, 50]);
    Test.assert(d.ghostCum != null);
    new PhoneMessageCallback().onMessage(ghostPayload(null));
    Test.assert(d.ghostCum == null);
    Test.assertEqual(d.routeGhostSecAt(100), -1);
    return true;
}

(:test)
function routeGhost_secAt_interpolates(logger) {
    var d = ghostData([250, 40, 50, 60, 70, 20]);
    Test.assertEqual(d.routeGhostSecAt(0).toNumber(), 0);
    Test.assertEqual(d.routeGhostSecAt(125).toNumber(), 20);    // half of step 1
    Test.assertEqual(d.routeGhostSecAt(250).toNumber(), 40);
    Test.assertEqual(d.routeGhostSecAt(375).toNumber(), 65);    // 40 + 50/2
    return true;
}

(:test)
function routeGhost_secAt_shortLastStepEndsAtRtl(logger) {
    // rtl 1100: the 5th step runs 1000-1100 m (100 m), not 1000-1250.
    var d = ghostData([250, 40, 50, 60, 70, 20]);
    Test.assertEqual(d.routeGhostSecAt(1050).toNumber(), 230);  // 220 + 20/2
    Test.assertEqual(d.routeGhostSecAt(1100).toNumber(), 240);
    Test.assertEqual(d.routeGhostSecAt(5000).toNumber(), 240);  // past the end: clamped
    return true;
}

(:test)
function routeGhost_delta_anchorsThenCompares(logger) {
    var d = ghostData([250, 40, 50, 60, 70, 20]);
    // First valid tick at 250 m: the opponent starts alongside -> 0.
    Test.assertEqual(d.routeGhostDelta(60000, 250), 0);
    // 500 m (ref +50 s) reached 60 s later: 10 s behind.
    Test.assertEqual(d.routeGhostDelta(120000, 500), 10);
    // 750 m (ref +110 s) reached 100 s after the anchor: 10 s ahead.
    Test.assertEqual(d.routeGhostDelta(160000, 750), -10);
    return true;
}

(:test)
function routeGhost_delta_nullWhenNothingToCompare(logger) {
    var d = ghostData(null);
    Test.assert(d.routeGhostDelta(60000, 250) == null);         // no ghost
    d = ghostData([250, 40, 50]);
    Test.assert(d.routeGhostDelta(0, 250) == null);             // timer not running
    d.offRoute = true;
    Test.assert(d.routeGhostDelta(60000, 250) == null);         // off-route
    d.offRoute = false;
    d.mode = "radius";
    Test.assert(d.routeGhostDelta(60000, 250) == null);         // radius mode
    return true;
}

(:test)
function routeGhost_resetAnchor_restartsAlongside(logger) {
    var d = ghostData([250, 40, 50, 60]);
    d.routeGhostDelta(10000, 0);
    Test.assertEqual(d.routeGhostDelta(100000, 250), 50);
    d.resetRouteGhostAnchor();
    Test.assertEqual(d.routeGhostDelta(100000, 250), 0);
    return true;
}
