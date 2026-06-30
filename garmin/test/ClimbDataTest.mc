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
    d.climbEntered[0] = true;         // GPS already confirmed we are on the climb

    d.updateProgress(1300);           // GPS says 300 m into the climb
    d.checkCalibration(52.0f, 5.0f);  // but we are exactly on the calib point (400 m)

    Test.assertEqual(d.progressInClimb, 400);
    return true;
}

// A climb with calibration geometry must NOT activate from the odometer alone:
// the rider must have been GPS-confirmed at its start (climbEntered) first.
(:test)
function updateProgress_inRangeButNotConfirmed_staysOffClimb(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 1; d.segDist[0][0] = 800;
    d.calibCount[0] = 1;              // has geometry, but climbEntered[0] is still false
    d.calibDist[0][0] = 0;
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);           // odometer is well inside [1000,1800]...

    Test.assertEqual(d.activeClimbIndex, -1);   // ...but the climb has NOT started
    Test.assertEqual(d.nextClimbIndex, 0);      // it is still the upcoming climb
    return true;
}

// A climb with no calibration geometry falls back to odometer-only activation.
(:test)
function updateProgress_inRangeNoCalib_activatesFromOdometer(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 1; d.segDist[0][0] = 800;
    // calibCount[0] stays 0

    d.updateProgress(1300);

    Test.assertEqual(d.activeClimbIndex, 0);
    return true;
}

// Full per-tick flow: approaching, then reaching the start confirms entry (climbEntered),
// and the next updateProgress activates the climb.
(:test)
function approachReachingStart_confirmsThenActivates(logger) {
    var d = approachData();           // climb at 2000 m, calib point 0 at its start

    d.updateProgress(1500);           // 500 m before the climb
    Test.assertEqual(d.activeClimbIndex, -1);

    d.updateRouteMatch(52.0f, 5.0f);  // GPS exactly at the climb start → confirm entry
    Test.assert(d.climbEntered[0]);

    d.updateProgress(1500);           // same tick odometer, now confirmed
    Test.assertEqual(d.activeClimbIndex, 0);
    return true;
}

// Off-route during approach never confirms entry, so the climb never starts even though
// the odometer rolls past its start.
(:test)
function offRouteApproach_neverConfirms_climbDoesNotStart(logger) {
    var d = approachData();

    d.updateProgress(1500);
    d.updateRouteMatch(52.018f, 5.0f);   // ~2 km off route → off route, not confirmed
    Test.assert(d.offRoute);
    Test.assert(!d.climbEntered[0]);

    d.updateProgress(2200);              // odometer rolled past the climb start (2000)
    Test.assertEqual(d.activeClimbIndex, -1);   // climb still has NOT started
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
    d.climbEntered[0] = true;               // already confirmed on the climb

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
    d.climbEntered[0] = true;               // already confirmed on the climb

    d.updateProgress(1300);
    d.updateRouteMatch(52.0f, 5.0f);        // on the calib point → on route

    Test.assert(!d.offRoute);
    return true;
}

(:test)
function anchors_mirrorStartEndOnParse(logger) {
    var d = new ClimbData();
    d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);   // helper sets both working + immutable anchors
    Test.assertEqual(d.climbStartDist[0], 1000);
    Test.assertEqual(d.climbEndDist[0], 1800);
    Test.assertEqual(d.climbStartDist0[0], 1000);
    Test.assertEqual(d.climbEndDist0[0], 1800);
    // Shifting the working value must not change the immutable anchor.
    d.climbStartDist[0] = 950;
    Test.assertEqual(d.climbStartDist0[0], 1000);
    return true;
}

(:test)
function chooseAxis_notNavigating_returnsOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 8000;
    d.resetNavTrust();
    Test.assertEqual(d.chooseAxis(1234, -1), 1234); // navDist < 0 → odometer
    return true;
}

(:test)
function chooseAxis_lengthGatePass_returnsNavDist(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 8000;
    d.resetNavTrust();
    // distanceToDestination ~ full route at start → navMaxToDest within tolerance.
    // navDist = rtl - distToDest = 8000 - 7900 = 100; distToDest 7900 within 10% of 8000.
    var axis = d.chooseAxis(50, 100);
    Test.assertEqual(d.navTrust, d.NAV_TRUSTED);
    Test.assertEqual(axis, 100);
    return true;
}

(:test)
function chooseAxis_lengthGateFail_staysOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 20000;          // our route is 20 km...
    d.resetNavTrust();
    // ...but the navigated course is only ~8 km: distToDest peaks near 8000.
    var axis = d.chooseAxis(50, 12000); // navDist = 20000-8000; distToDest=8000 « 20000-tol
    Test.assertEqual(d.navTrust, d.NAV_UNKNOWN);
    Test.assertEqual(axis, 50);        // falls back to odometer
    return true;
}
