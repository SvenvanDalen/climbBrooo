using Toybox.Test;
using Toybox.Application as App;

// Issues #7 / #9: next-climb countdown in radius mode and the ordered day trip. Covers the
// pure picker (nearest, hysteresis, ordered, visited/skipped) and ClimbData.updateRadius
// end to end through the wire parser.

(:test)
function radius_pick_nearestUnvisited(logger) {
    var v = [false, false, false];
    Test.assertEqual(radiusPickTarget([3000, 1200, 5000], v, 3, false, -1), 1);
    return true;
}

(:test)
function radius_pick_keepsCurrentWithinHysteresis(logger) {
    var v = [false, false];
    // Climb 1 is 100 m closer than the current target 0: not enough to switch.
    Test.assertEqual(radiusPickTarget([1100, 1000], v, 2, false, 0), 0);
    // 200 m closer: switch.
    Test.assertEqual(radiusPickTarget([1200, 1000], v, 2, false, 0), 1);
    return true;
}

(:test)
function radius_pick_skipsVisitedAndUnknown(logger) {
    var v = [true, false, false];
    Test.assertEqual(radiusPickTarget([10, -1, 4000], v, 3, false, 0), 2);
    Test.assertEqual(radiusPickTarget([10, -1, -1], v, 3, false, 0), -1);
    return true;
}

(:test)
function radius_pick_orderedTakesFirstUnvisited(logger) {
    var v = [true, false, false];
    // Climb 2 is nearer, but the day trip goes to climb 1 next.
    Test.assertEqual(radiusPickTarget([0, 9000, 500], v, 3, true, -1), 1);
    return true;
}

(:test)
function radius_markVisited_withinFiftyMetres(logger) {
    var v = [false, false];
    radiusMarkVisited([49, 51], v, 2, false);
    Test.assertEqual(v[0], true);
    Test.assertEqual(v[1], false);
    Test.assertEqual(radiusVisitedCount(v, 2), 1);
    return true;
}

(:test)
function radius_markVisited_orderedMarksSkippedClimbs(logger) {
    var v = [false, false, false];
    radiusMarkVisited([5000, 4000, 20], v, 3, true);
    Test.assertEqual(v[0], true);
    Test.assertEqual(v[1], true);
    Test.assertEqual(v[2], true);
    return true;
}

(:test)
function radius_markVisited_unorderedLeavesOthers(logger) {
    var v = [false, false, false];
    radiusMarkVisited([5000, 4000, 20], v, 3, false);
    Test.assertEqual(v[0], false);
    Test.assertEqual(v[2], true);
    return true;
}

function radiusPayload(ord) {
    var p = {
        "v" => 3, "mode" => "radius",
        "climbs" => [
            { "slat" => 5200000, "slon" => 500000, "len" => 900, "eg" => 50, "ag" => 55,
              "segs" => [450, 25, 55, 2, 450, 25, 55, 2] },
            { "slat" => 5201000, "slon" => 500000, "len" => 1200, "eg" => 80, "ag" => 66,
              "segs" => [600, 40, 66, 3, 600, 40, 66, 3] }
        ]
    };
    if (ord != null) { p.put("ord", ord); }
    return p;
}

(:test)
function radius_updateRadius_countsDownToNearest(logger) {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    new PhoneMessageCallback().onMessage(radiusPayload(null));
    Test.assertEqual(d.ordered, false);
    // ~1.1 km south of climb 0 (0.01 deg lat ~ 1111 m).
    d.updateRadius(51.99, 5.0);
    Test.assertEqual(d.nextClimbIndex, 0);
    Test.assert(d.distToNextClimb > 1050 && d.distToNextClimb < 1170);
    return true;
}

(:test)
function radius_updateRadius_orderedMovesOnAfterReachingStart(logger) {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    new PhoneMessageCallback().onMessage(radiusPayload(1));
    Test.assertEqual(d.ordered, true);
    d.updateRadius(52.0, 5.0);             // at climb 0's start
    Test.assertEqual(d.nextClimbIndex, 1);
    d.updateRadius(51.99, 5.0);            // riding away again: no jump back to climb 0
    Test.assertEqual(d.nextClimbIndex, 1);
    return true;
}

(:test)
function radius_updateRadius_ignoredInRouteMode(logger) {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    var p = radiusPayload(null);
    p.put("mode", "route");
    new PhoneMessageCallback().onMessage(p);
    d.updateRadius(52.0, 5.0);
    Test.assertEqual(d.distToNextClimb, -1);
    return true;
}

(:test)
function radius_resyncWithoutOrd_clearsOrdered(logger) {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    new PhoneMessageCallback().onMessage(radiusPayload(1));
    new PhoneMessageCallback().onMessage(radiusPayload(null));
    Test.assertEqual(d.ordered, false);
    return true;
}

(:test)
function radius_distLabel_marksStraightLine(logger) {
    var view = new ClimbProView();
    Test.assertEqual(view.distToNextLabel("radius", 2300, 0), "in ~2.3km");
    Test.assertEqual(view.distToNextLabel("route", 2300, 0), "in 2.3km");
    return true;
}
