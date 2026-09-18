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

(:test)
function calib_agreementWithinTol_keepsTrusted(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;
    d.activeClimbIndex = 0;
    d.navDistThisTick = 1400;            // absCalib = 1000 + 400 = 1400 → agree
    d.checkCalibration(52.0f, 5.0f);     // within snap radius
    Test.assertEqual(d.navTrust, d.NAV_TRUSTED);
    Test.assertEqual(d.climbStartDist0[0], 1000);   // anchor untouched
    Test.assertEqual(d.climbStartDist[0], 1000);    // NOT shifted while trusted
    return true;
}

(:test)
function calib_disagreementRevokesTrust(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;
    d.activeClimbIndex = 0;
    d.navDistThisTick = 1700;            // absCalib 1400, off by 300 > 150 → revoke
    d.checkCalibration(52.0f, 5.0f);
    Test.assertEqual(d.navTrust, d.NAV_REVOKED);
    return true;
}

(:test)
function calib_odometerMode_stillShifts(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;          // odometer mode
    d.activeClimbIndex = 0;
    d.lastElapsedDistance = 1450;        // GPS says we're at the 400 m calib point
    d.checkCalibration(52.0f, 5.0f);
    Test.assertEqual(d.progressInClimb, 400);
    Test.assertEqual(d.climbStartDist[0], 1050);  // shifted: 1450 - 400 (existing behaviour)
    return true;
}

(:test)
function skip_trustedNavPastEnd_marksSkippedAndAdvances(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);   // climb 0
    d.setAnchors(1, 4000, 5000);   // climb 1
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_TRUSTED;            // on the course → back-on-route is implicit
    d.updateProgress(2900);               // 1100 m past climb-0 end, never entered
    Test.assertEqual(d.climbSkipped[0], true);
    Test.assertEqual(d.nextClimbIndex, 1); // progression advanced to climb 1
    return true;
}

(:test)
function skip_odometerNoLaterConfirm_doesNotSkip(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;            // odometer mode, no later climb confirmed
    d.updateProgress(2900);               // odometer past end, but might be off-route
    Test.assertEqual(d.climbSkipped[0], false);
    Test.assertEqual(d.nextClimbIndex, 0); // still waiting on climb 0
    return true;
}

(:test)
function skip_odometerLaterClimbEntered_skips(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1; d.calibDist[0][0] = 0;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;
    d.climbEntered[1] = true;             // GPS confirmed we reached the later climb
    d.updateProgress(2900);
    Test.assertEqual(d.climbSkipped[0], true);
    return true;
}

(:test)
function skip_skippedClimbNotReactivated(logger) {
    var d = new ClimbData();
    d.payloadReceived = true; d.mode = "route"; d.climbCount = 2;
    d.setAnchors(0, 1000, 1800);
    d.setAnchors(1, 4000, 5000);
    d.calibCount[0] = 1;
    d.climbSkipped[0] = true;             // already abandoned
    d.updateProgress(1500);               // axis back inside climb-0 range
    Test.assertEqual(d.activeClimbIndex, -1);  // not reactivated
    Test.assertEqual(d.nextClimbIndex, 1);
    return true;
}

// ===========================================================================
// Edge cases
// ===========================================================================

// A single odometer-activated climb (calibCount 0 → confirmed via odometer fallback).
function edgeClimb() {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.setAnchors(0, 1000, 1800);
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    return d;
}

// chooseAxis: navDist >= 0 but no known route length → cannot gate → odometer.
(:test)
function chooseAxis_noRouteLen_returnsOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 0;                   // unknown
    d.resetNavTrust();
    Test.assertEqual(d.chooseAxis(500, 100), 500);
    Test.assertEqual(d.navTrust, d.NAV_UNKNOWN);
    return true;
}

// chooseAxis: once trust is revoked it never returns the nav axis again.
(:test)
function chooseAxis_revokedTrust_returnsOdometer(logger) {
    var d = new ClimbData();
    d.routeTotalLen = 8000;
    d.resetNavTrust();
    d.navTrust = d.NAV_REVOKED;
    Test.assertEqual(d.chooseAxis(500, 100), 500);   // navDist ignored
    Test.assertEqual(d.navTrust, d.NAV_REVOKED);     // and stays revoked
    return true;
}

// updateProgress records the odometer but does nothing else before a payload arrives.
(:test)
function updateProgress_noPayload_earlyReturn(logger) {
    var d = new ClimbData();               // payloadReceived == false
    d.updateProgress(1500);
    Test.assertEqual(d.lastElapsedDistance, 1500);
    Test.assertEqual(d.activeClimbIndex, -1);
    return true;
}

// Radius mode never drives an active climb from updateProgress.
(:test)
function updateProgress_radiusMode_earlyReturn(logger) {
    var d = edgeClimb();
    d.mode = "radius";
    d.updateProgress(1500);
    Test.assertEqual(d.lastElapsedDistance, 1500);
    Test.assertEqual(d.activeClimbIndex, -1);
    return true;
}

// Odometer past the only climb's end → no active climb and no next climb.
(:test)
function updateProgress_pastAllClimbs_noActiveNoNext(logger) {
    var d = edgeClimb();
    d.updateProgress(2000);                // > climbEndDist 1800
    Test.assertEqual(d.activeClimbIndex, -1);
    Test.assertEqual(d.nextClimbIndex, -1);
    Test.assertEqual(d.distToNextClimb, -1);
    return true;
}

// Progress beyond the sum of the segment lengths clamps to the last segment.
(:test)
function updateProgress_progressBeyondSegments_lastSegment(logger) {
    var d = edgeClimb();
    d.setAnchors(0, 1000, 2000);           // climb longer than its 800 m of segments
    d.updateProgress(1900);                // 900 m in, but segDist sums to 800
    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assertEqual(d.progressInClimb, 900);
    Test.assertEqual(d.activeSegmentIndex, 1);   // last segment (fallback branch)
    return true;
}

// Progress exactly on a segment boundary lands in the earlier segment (<= boundary).
(:test)
function updateProgress_segmentBoundaryExact_picksEarlierSegment(logger) {
    var d = edgeClimb();
    d.updateProgress(1400);                // exactly 400 m in = end of segment 0
    Test.assertEqual(d.progressInClimb, 400);
    Test.assertEqual(d.activeSegmentIndex, 0);
    return true;
}

// Once every calibration point is consumed, checkCalibration is a no-op.
(:test)
function checkCalibration_exhaustedIdx_noOp(logger) {
    var d = edgeClimb();
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.activeClimbIndex = 0;
    d.calibIdx[0] = 1;                      // already past the only point
    d.progressInClimb = 123;
    d.checkCalibration(52.0f, 5.0f);        // on the point, but idx exhausted
    Test.assertEqual(d.progressInClimb, 123);   // untouched
    return true;
}

// GPS far from the pending calibration point → no snap, index not advanced.
(:test)
function checkCalibration_farFromPoint_noSnap(logger) {
    var d = edgeClimb();
    d.calibCount[0] = 1; d.calibDist[0][0] = 400;
    d.calibLat[0][0] = 52.0f; d.calibLon[0][0] = 5.0f;
    d.activeClimbIndex = 0;
    d.progressInClimb = 300;
    d.checkCalibration(53.0f, 5.0f);        // ~111 km away
    Test.assertEqual(d.calibIdx[0], 0);     // not consumed
    Test.assertEqual(d.progressInClimb, 300);
    return true;
}

// Reaching two calibration points in turn advances calibIdx and snaps to each.
(:test)
function checkCalibration_sequentialPoints_advancesIdx(logger) {
    var d = edgeClimb();
    d.calibCount[0] = 2;
    d.calibDist[0][0] = 200; d.calibLat[0][0] = 52.0f;  d.calibLon[0][0] = 5.0f;
    d.calibDist[0][1] = 600; d.calibLat[0][1] = 52.01f; d.calibLon[0][1] = 5.0f;
    d.navTrust = d.NAV_UNKNOWN;             // odometer mode → snaps progress
    d.activeClimbIndex = 0;
    d.lastElapsedDistance = 1200;

    d.checkCalibration(52.0f, 5.0f);        // first point (200 m)
    Test.assertEqual(d.calibIdx[0], 1);
    Test.assertEqual(d.progressInClimb, 200);

    d.checkCalibration(52.01f, 5.0f);       // second point (600 m)
    Test.assertEqual(d.calibIdx[0], 2);
    Test.assertEqual(d.progressInClimb, 600);
    return true;
}

// Radius mode leaves offRoute false (no route geometry to match against).
(:test)
function updateRouteMatch_radiusMode_noOffRoute(logger) {
    var d = edgeClimb();
    d.mode = "radius";
    d.offRoute = true;                      // ensure it gets cleared
    d.updateRouteMatch(52.0f, 5.0f);
    Test.assert(!d.offRoute);
    return true;
}

// Approaching a climb that carries no calibration geometry never flags off-route.
(:test)
function updateRouteMatch_nextClimbNoCalib_noOffRoute(logger) {
    var d = edgeClimb();                    // calibCount[0] == 0
    d.updateProgress(500);                  // approaching: nextClimbIndex 0, 500 m out
    Test.assertEqual(d.nextClimbIndex, 0);
    d.updateRouteMatch(52.5f, 5.0f);        // far away, but no calib to compare
    Test.assert(!d.offRoute);
    return true;
}

// ===========================================================================
// etaSeconds() — pure function, remaining distance (m) + speed (m/s) -> seconds
// ===========================================================================

// Normal case: 1000 m remaining at 5 m/s (18 km/h) -> 200 s.
(:test)
function etaSeconds_normalCase_returnsRemainingOverSpeed(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(1000, 5.0), 200);
    return true;
}

// Zero speed can't produce a meaningful estimate -> placeholder (-1), not a divide-by-zero.
(:test)
function etaSeconds_zeroSpeed_returnsNegativeOne(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(1000, 0.0), -1);
    return true;
}

// Speed below the noise floor (stopped/near-stopped) also returns the placeholder.
(:test)
function etaSeconds_belowMinSpeed_returnsNegativeOne(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(1000, 0.1), -1);
    return true;
}

// Null speed (e.g. Activity.Info.currentSpeed unavailable) is handled the same as zero.
(:test)
function etaSeconds_nullSpeed_returnsNegativeOne(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(1000, null), -1);
    return true;
}

// Very small remaining distance with a healthy speed -> a small but valid ETA.
(:test)
function etaSeconds_verySmallRemaining_returnsSmallEta(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(5, 5.0), 1);
    return true;
}

// Already at/past the summit -> 0 s, regardless of speed.
(:test)
function etaSeconds_zeroRemaining_returnsZero(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(0, 5.0), 0);
    return true;
}

// Negative remaining (past the summit, e.g. clamp not yet applied by the caller) also
// short-circuits to 0 rather than a negative/nonsensical ETA.
(:test)
function etaSeconds_negativeRemaining_returnsZero(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.etaSeconds(-50, 5.0), 0);
    return true;
}
