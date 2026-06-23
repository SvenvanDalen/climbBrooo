using Toybox.Test;

(:test)
function surface_parse_storesSectionsAndCheckpoints(logger) {
    var d = new SurfaceData();
    var ok = d.parse({
        "v" => 3, "mode" => "route", "routeId" => "r1", "name" => "Test",
        "surfSec" => [
            { "s" => 0, "e" => 400, "t" => 1, "n" => "Gravel ster",
              "cp" => [0, 5200000, 500000, 400, 5200300, 500000] },
            { "s" => 1000, "e" => 1600, "t" => 3 }
        ]
    });

    Test.assert(ok);
    Test.assertEqual(d.count, 2);
    Test.assertEqual(d.secStart[0], 0);
    Test.assertEqual(d.secEnd[0], 400);
    Test.assertEqual(d.secType[0], 1);
    Test.assertEqual(d.secName[0], "Gravel ster");
    Test.assertEqual(d.secCpCnt[0], 2);
    Test.assertEqual(d.secType[1], 3);
    Test.assertEqual(d.secCpCnt[1], 0);
    Test.assertEqual(d.totalCp, 2);
    return true;
}

(:test)
function surface_parse_rejectsBadVersion(logger) {
    var d = new SurfaceData();
    Test.assert(!d.parse({ "v" => 2, "surfSec" => [] }));
    return true;
}

(:test)
function surface_parse_skipsNonDictSection(logger) {
    var d = new SurfaceData();
    var ok = d.parse({ "v" => 3, "surfSec" => [ 42, { "s" => 100, "e" => 200, "t" => 2 } ] });

    Test.assert(ok);
    Test.assertEqual(d.count, 2);
    Test.assertEqual(d.secType[0], 5);   // malformed slot defaults to UNKNOWN
    Test.assertEqual(d.secStart[1], 100);
    Test.assertEqual(d.secType[1], 2);
    return true;
}

(:test)
function surface_updateProgress_inSection_setsCurrentAndSubpiece(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 1000, "e" => 2000, "t" => 1 } ] });

    d.updateProgress(1500);              // halfway through the 1000 m section

    Test.assertEqual(d.currentIdx, 0);
    Test.assertEqual(d.remainingInSection, 500);
    Test.assertEqual(d.subPieceLen, 80);     // floor(1000 * 8 / 100)
    Test.assertEqual(d.subPieceCount, 13);   // ceil(1000 / 80)
    Test.assertEqual(d.currentSubPiece, 6);  // floor(500 / 80)
    return true;
}

(:test)
function surface_updateProgress_beforeSection_setsNext(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 1000, "e" => 2000, "t" => 1 } ] });

    d.updateProgress(400);

    Test.assertEqual(d.currentIdx, -1);
    Test.assertEqual(d.nextIdx, 0);
    Test.assertEqual(d.distToNext, 600);
    return true;
}

(:test)
function surface_correctElapsed_snapsToNearestCheckpoint(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [
        { "s" => 0, "e" => 1000, "t" => 1, "cp" => [500, 5200000, 500000] }
    ] });

    // GPS exactly on the checkpoint (52.0, 5.0) but odometer says 480.
    var corrected = d.correctElapsed(480, [52.0, 5.0]);

    // raw = 500 - 480 = 20; smoothed = ((0*3)+20)/4 = 5; corrected = 480 + 5.
    Test.assertEqual(d.distanceOffset, 5);
    Test.assertEqual(corrected, 485);
    return true;
}
