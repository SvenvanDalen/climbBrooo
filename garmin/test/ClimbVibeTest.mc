using Toybox.Test;
using Toybox.Application as App;

// Issue #26 (vibration per climb type), #25 (ride ascent left), #18 (cadence target),
// #24 (heart-rate zone colors) and #27 (new climb flag).

(:test)
function vibe_climbType(logger) {
    Test.assertEqual(climbTypeOf(1500, 90), CLIMB_TYPE_SHORT_STEEP);
    Test.assertEqual(climbTypeOf(2499, 70), CLIMB_TYPE_SHORT_STEEP);
    Test.assertEqual(climbTypeOf(2500, 90), CLIMB_TYPE_REGULAR);
    Test.assertEqual(climbTypeOf(1500, 69), CLIMB_TYPE_REGULAR);
    Test.assertEqual(climbTypeOf(5000, 30), CLIMB_TYPE_LONG);
    Test.assertEqual(climbTypeOf(8000, 95), CLIMB_TYPE_LONG);
    return true;
}

(:test)
function vibe_patterns(logger) {
    Test.assert(climbVibePattern(VIBE_DEFAULT) == null);
    Test.assert(climbVibePattern(9) == null);
    Test.assertEqual(climbVibePattern(VIBE_SHORT_DOUBLE).size(), 6);
    Test.assertEqual(climbVibePattern(VIBE_LONG_SINGLE).size(), 2);
    Test.assertEqual(climbVibePattern(VIBE_TRIPLE).size(), 10);
    return true;
}

function extrasPayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "ex", "name" => "Ex",
        "cg" => [92, 88, 84, 80, 76, 0],
        "hg" => [2, 3, 3, 4, 5, 0],
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75, "nw" => 1,
              "segs" => [400, 20, 50, 2, 400, 40, 100, 5] },
            { "sd" => 3000, "ed" => 4000, "len" => 1000, "eg" => 70, "ag" => 70,
              "segs" => [500, 35, 70, 3, 500, 35, 70, 3] }
        ]
    };
}

function extrasData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    new PhoneMessageCallback().onMessage(extrasPayload());
    return app.climbData;
}

(:test)
function extras_parse_newFlagAndGradeClasses(logger) {
    var d = extrasData();
    Test.assertEqual(d.climbNew[0], true);
    Test.assertEqual(d.climbNew[1], false);
    Test.assertEqual(d.cadenceByGrade[3], 80);
    Test.assertEqual(d.hrZoneByGrade[4], 5);
    return true;
}

(:test)
function extras_parse_malformedGradeClassesDropped(logger) {
    var p = extrasPayload();
    p.put("cg", [90, 85]);
    p.put("hg", [1, 2, 9, "x", 3, 4]);
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    new PhoneMessageCallback().onMessage(p);
    Test.assert(app.climbData.cadenceByGrade == null);
    Test.assertEqual(app.climbData.hrZoneByGrade[2], 0);
    Test.assertEqual(app.climbData.hrZoneByGrade[3], 0);
    Test.assertEqual(app.climbData.hrZoneByGrade[4], 3);
    return true;
}

(:test)
function extras_cadenceTarget_perSegmentClass(logger) {
    var d = extrasData();
    Test.assertEqual(d.cadenceTargetAt(0, 0), 84);   // color class 2
    Test.assert(d.cadenceTargetAt(0, 1) == null);    // class 5 has no data (0)
    Test.assert(d.cadenceTargetAt(0, -1) == null);
    return true;
}

(:test)
function extras_cadenceText(logger) {
    Test.assertEqual(FieldLayout.cadenceText(86, 90, true), "86/90rpm");
    Test.assertEqual(FieldLayout.cadenceText(86, 90, false), "86/90");
    Test.assertEqual(FieldLayout.cadenceText(null, 90, false), "--/90");
    Test.assertEqual(FieldLayout.cadenceText(86, null, false), "86rpm");
    Test.assertEqual(FieldLayout.cadenceText(null, null, true), "--");
    return true;
}

(:test)
function extras_hrZoneColorMode(logger) {
    var d = extrasData();
    // Segment 0 is class 2 -> zone 3 -> orange (3); segment 1 class 5 has no zone -> gradient.
    Test.assertEqual(d.colorIndexForMode(0, 0, 2), 3);
    Test.assertEqual(d.colorIndexForMode(0, 1, 2), 5);
    Test.assertEqual(d.colorIndexForMode(0, 0, 0), 2);
    return true;
}

(:test)
function extras_rideRemainingElev(logger) {
    var d = extrasData();
    d.updateProgress(0);                     // before climb 0
    Test.assertEqual(d.rideRemainingElev(), 130);
    d.updateProgress(1500);                  // in climb 0's second segment
    Test.assertEqual(d.rideRemainingElev(), 40 + 70);
    d.updateProgress(2000);                  // between the climbs
    Test.assertEqual(d.rideRemainingElev(), 70);
    d.updateProgress(5000);                  // past the last climb
    Test.assertEqual(d.rideRemainingElev(), 0);
    return true;
}

(:test)
function extras_rideRemElevSlot(logger) {
    var v = fieldLayoutVals();
    v.put(:rideRemElev, 820);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.RIDE_REM_ELEV, v, true), "rit 820m↑");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.RIDE_REM_ELEV, v, false), "820m↑");
    Test.assertEqual(FieldLayout.parse([16, 0, 0, 0, 0])[0], FieldLayout.RIDE_REM_ELEV);
    return true;
}
