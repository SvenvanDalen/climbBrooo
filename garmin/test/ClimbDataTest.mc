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
