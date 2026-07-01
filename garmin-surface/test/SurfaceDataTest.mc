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

// ===========================================================================
// Edge cases
// ===========================================================================

// A payload without a surfSec array is not a surface payload → parse returns false.
(:test)
function surface_parse_missingSurfSec_returnsFalse(logger) {
    var d = new SurfaceData();
    Test.assert(!d.parse({ "v" => 3, "mode" => "route" }));
    Test.assertEqual(d.payloadReceived, false);
    return true;
}

// surfSec present but not an array → rejected.
(:test)
function surface_parse_surfSecNotArray_returnsFalse(logger) {
    var d = new SurfaceData();
    Test.assert(!d.parse({ "v" => 3, "surfSec" => "nope" }));
    return true;
}

// A surface type outside 0..5 defaults to UNKNOWN (5).
(:test)
function surface_parse_typeOutOfRange_defaultsUnknown(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 0, "e" => 400, "t" => 9 } ] });
    Test.assertEqual(d.secType[0], 5);
    return true;
}

// More sections than MAX_SECTIONS are truncated to the array bound.
(:test)
function surface_parse_moreSectionsThanMax_truncates(logger) {
    var d = new SurfaceData();
    var n = d.MAX_SECTIONS + 5;
    var secs = new [n];
    for (var i = 0; i < n; i++) {
        secs[i] = { "s" => i * 100, "e" => i * 100 + 50, "t" => 1 };
    }
    d.parse({ "v" => 3, "surfSec" => secs });
    Test.assertEqual(d.count, d.MAX_SECTIONS);
    return true;
}

// Elapsed exactly at a section's end is treated as past it (end is exclusive).
(:test)
function surface_updateProgress_atSectionEnd_notCurrent(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 1000, "e" => 2000, "t" => 1 } ] });
    d.updateProgress(2000);
    Test.assertEqual(d.currentIdx, -1);
    Test.assertEqual(d.nextIdx, -1);
    return true;
}

// correctElapsed with no checkpoints applies only the stored offset (0 here).
(:test)
function surface_correctElapsed_noCheckpoints_returnsElapsed(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 0, "e" => 1000, "t" => 1 } ] });
    Test.assertEqual(d.correctElapsed(480, [52.0, 5.0]), 480);   // totalCp 0
    return true;
}

// With multiple checkpoints, correctElapsed snaps to the one the rider is actually near.
(:test)
function surface_correctElapsed_picksNearestCheckpoint(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [
        { "s" => 0, "e" => 1000, "t" => 1,
          "cp" => [200, 5200000, 500000, 800, 5201000, 500000] }  // cp0@(52.0,5.0) cp1@(52.01,5.0)
    ] });
    // GPS on cp1 (dist 800); odometer 760 → raw 40, smoothed 10, corrected 770.
    var corrected = d.correctElapsed(760, [52.01, 5.0]);
    Test.assertEqual(d.distanceOffset, 10);
    Test.assertEqual(corrected, 770);
    return true;
}

// refineSubPieceByGPS on a section that carries no checkpoints is a no-op.
(:test)
function surface_refine_sectionNoCheckpoints_noOp(logger) {
    var d = new SurfaceData();
    d.parse({ "v" => 3, "surfSec" => [ { "s" => 0, "e" => 1000, "t" => 1 } ] });  // no cp
    d.updateProgress(100);
    var before = d.currentSubPiece;
    d.refineSubPieceByGPS([52.0, 5.0]);
    Test.assertEqual(d.currentSubPiece, before);   // unchanged
    return true;
}
