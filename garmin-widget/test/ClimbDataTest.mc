using Toybox.Test;

(:test)
function widget_updateProgress_onClimb_setsActiveAndSegment(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;

    d.updateProgress(1500);            // 500 m into the climb -> segment 1

    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assertEqual(d.progressInClimb, 500);
    Test.assertEqual(d.activeSegmentIndex, 1);
    return true;
}

(:test)
function widget_checkCalibration_nearPoint_snapsProgress(logger) {
    var d = new ClimbData();
    d.payloadReceived = true;
    d.mode = "route";
    d.climbCount = 1;
    d.climbStartDist[0] = 1000;
    d.climbEndDist[0]   = 1800;
    d.segCount[0] = 2; d.segDist[0][0] = 400; d.segDist[0][1] = 400;
    d.calibCount[0] = 1;
    d.calibDist[0][0] = 400;
    d.calibLat[0][0]  = 52.0f;
    d.calibLon[0][0]  = 5.0f;

    d.updateProgress(1300);            // GPS says 300 m in
    d.checkCalibration(52.0f, 5.0f);   // but we are on the calib point (400 m)

    Test.assertEqual(d.progressInClimb, 400);
    return true;
}

(:test)
function widget_parseFlatStarred_storesSpecialized(logger) {
    var d = new ClimbData();
    d.parseFlatStarred([
        { "s" => 3200, "e" => 3600, "t" => 1, "n" => "Gravel ster" },
        { "s" => 5000, "e" => 5400, "t" => 2 }
    ]);

    Test.assertEqual(d.flatStarredCount, 2);
    Test.assertEqual(d.flatStarredStart[0], 3200);
    Test.assertEqual(d.flatStarredEnd[0], 3600);
    Test.assertEqual(d.flatStarredSurf[0], 1);
    Test.assertEqual(d.flatStarredName[0], "Gravel ster");
    Test.assertEqual(d.flatStarredSurf[1], 2);
    Test.assert(d.flatStarredName[1] == null);
    return true;
}

(:test)
function widget_parseFlatStarred_skipsMalformedAndResets(logger) {
    var d = new ClimbData();
    d.parseFlatStarred([ { "s" => 100, "e" => 200, "t" => 1 } ]);
    Test.assertEqual(d.flatStarredCount, 1);

    // Resync: a malformed (non-Dictionary) element is skipped — no phantom row.
    d.parseFlatStarred([ 42, { "s" => 700, "e" => 800, "t" => 3 } ]);
    Test.assertEqual(d.flatStarredCount, 1);
    Test.assertEqual(d.flatStarredStart[0], 700);
    Test.assertEqual(d.flatStarredSurf[0], 3);

    // Resync with no fss clears the list.
    d.parseFlatStarred(null);
    Test.assertEqual(d.flatStarredCount, 0);
    return true;
}
