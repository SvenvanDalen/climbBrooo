using Toybox.Test;

(:test)
function updateProgress_onClimb_setsActiveAndSegment(logger) {
    var d = new ClimbData();          // new roept initialize() aan
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2;
    d.segDist[0][0] = 400;
    d.segDist[0][1] = 400;

    d.updateProgress(1500);           // 500 m in de klim → segment 1

    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assertEqual(d.progressInClimb, 500);
    Test.assertEqual(d.activeSegmentIndex, 1);
    return true;
}

(:test)
function checkCalibration_nearPoint_snapsProgress(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 400;          // 400 m from climb start
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);           // GPS says 300 m into the climb
    d.checkCalibration(52.0f, 5.0f);  // but we are exactly on the calib point (400 m)

    Test.assertEqual(d.progressInClimb, 400);
    return true;
}

// Helper: a climb at 2000 m with one calib point at its start (52.0, 5.0).
function approachData() {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 2000;
    d.climbEndDist[0]   = 2800;
    d.segCount[0] = 1; d.segDist[0][0] = 800;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 0;            // first calib point at the climb start
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;
    return d;
}

(:test)
function updateRouteMatch_approachDivergence_setsOffRoute(logger) {
    var d = approachData();
    d.updateProgress(1500);                 // 500 m before the climb (inside 1 km window)
    // GPS ~2 km north of the climb start → straight-line (≈2000 m) far exceeds the
    // remaining along-route distance (500 m) + margin (300 m) → off route.
    d.updateRouteMatch(52.018f, 5.0f);

    Test.assert(d.offRoute);
    return true;
}

(:test)
function updateRouteMatch_approachOnRoute_noOffRoute_andSnaps(logger) {
    var d = approachData();
    d.updateProgress(1500);                 // odometer says 500 m to go...
    d.updateRouteMatch(52.0f, 5.0f);        // ...but GPS is exactly at the climb start

    Test.assert(!d.offRoute);
    // Reaching calib point 0 aligns the climb start to the current elapsed distance.
    Test.assertEqual(d.climbStartDist[0], 1500);
    return true;
}

(:test)
function updateRouteMatch_approachFarButNotDiverged_noOffRoute(logger) {
    var d = approachData();
    d.updateProgress(1500);                 // 500 m to go along the route
    // GPS 300 m from the climb start: straight-line (300) < remaining (500) + margin (300),
    // so this is normal on-route approach, not a divergence.
    d.updateRouteMatch(52.0027f, 5.0f);

    Test.assert(!d.offRoute);
    return true;
}

(:test)
function updateRouteMatch_onClimbFarFromCalib_setsOffRoute(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 1; d.segDist[0][0] = 800;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 400;
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);                 // on the climb
    d.updateRouteMatch(52.01f, 5.0f);       // ~1.1 km from the only calib point → off route

    Test.assert(d.offRoute);
    return true;
}

(:test)
function updateRouteMatch_onClimbNearCalib_noOffRoute(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 1; d.segDist[0][0] = 800;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 400;
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);
    d.updateRouteMatch(52.0f, 5.0f);        // on the calib point → on route

    Test.assert(!d.offRoute);
    return true;
}
