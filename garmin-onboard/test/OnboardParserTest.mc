using Toybox.Test;

(:test)
function parser_smooth_constantStaysConstant(logger) {
    var st = onbStoreFromEle([100, 100, 100, 100, 100, 100, 100]);
    RouteParser.smoothEle(st);
    for (var i = 0; i < st.pointCount; i++) {
        Test.assert((st.ele[i] - 100.0).abs() < 0.001);
    }
    return true;
}

(:test)
function parser_smooth_flattensSpike(logger) {
    // 13 points so index 6 has a full 11-point window: (12*100 + 210)/11
    var eles = [100, 100, 100, 100, 100, 100, 210, 100, 100, 100, 100, 100, 100];
    var st = onbStoreFromEle(eles);
    RouteParser.smoothEle(st);
    var expected = (10.0 * 100.0 + 210.0) / 11.0;
    Test.assert((st.ele[6] - expected).abs() < 0.01);
    // neighbours pulled up slightly, not left at 100
    Test.assert(st.ele[5] > 100.0);
    return true;
}

(:test)
function parser_smooth_edgesUseShrunkWindow(logger) {
    var st = onbStoreFromEle([100, 200, 100, 100, 100, 100, 100, 100]);
    RouteParser.smoothEle(st);
    // index 0 window = points 0..5 => (100+200+100*4)/6
    var expected = (100.0 + 200.0 + 400.0) / 6.0;
    Test.assert((st.ele[0] - expected).abs() < 0.01);
    return true;
}

(:test)
function detect_flatRoute_noClimbs(logger) {
    var eles = new [30];
    for (var i = 0; i < 30; i++) { eles[i] = 100; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 0);
    return true;
}

(:test)
function detect_twoKmAtFivePct_oneClimb(logger) {
    // 21 points, ~100 m apart, +5 m per point => ~2000 m at ~5%
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    Test.assert(d.climbLength[0] >= 1990 && d.climbLength[0] <= 2010);
    Test.assertEqual(d.climbElevGain[0], 100);
    // avg gradient pct*10 ~ 50
    Test.assert(d.climbAvgGrad[0] >= 48 && d.climbAvgGrad[0] <= 52);
    Test.assertEqual(d.climbStartIdx[0], 0);
    Test.assertEqual(d.climbEndIdx[0], 20);
    return true;
}

(:test)
function detect_tooShortOrTooShallow_rejected(logger) {
    // ~700 m at 6% => too short
    var shortE = new [8];
    for (var i = 0; i < 8; i++) { shortE[i] = 100 + i * 6; }
    var d1 = new OnboardClimbData();
    RouteParser.detectClimbs(onbStoreFromEle(shortE), d1);
    Test.assertEqual(d1.climbCount, 0);
    // ~2900 m at 2% => too shallow
    var shallowE = new [30];
    for (var i = 0; i < 30; i++) { shallowE[i] = 100 + i * 2; }
    var d2 = new OnboardClimbData();
    RouteParser.detectClimbs(onbStoreFromEle(shallowE), d2);
    Test.assertEqual(d2.climbCount, 0);
    return true;
}

(:test)
function detect_falseFlatLeadIn_isTrimmed(logger) {
    // 5 flat points (~400 m at 0%) then 16 points at 6% (~1500 m)
    var eles = new [21];
    for (var i = 0; i < 5; i++) { eles[i] = 100; }
    for (var i = 5; i < 21; i++) { eles[i] = 100 + (i - 4) * 6; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    // climb must start at the flat/steep boundary (~400 m), not at 0
    Test.assert(d.climbStartDist[0] >= 380 && d.climbStartDist[0] <= 420);
    Test.assert(d.climbLength[0] >= 1580 && d.climbLength[0] <= 1620);
    return true;
}

(:test)
function detect_trimNeverShrinksBelowMinimum(logger) {
    // 3 lead points at ~1% (300 m) then 6 points at 6% (600 m): total ~900 m,
    // avg ~4.3% => valid climb; trimming the 300 m lead would leave 600 m < 800 m,
    // so the trim must NOT be committed.
    var eles = [100, 101, 102, 103, 109, 115, 121, 127, 133, 139];
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 1);
    Test.assert(d.climbStartDist[0] <= 20);   // still starts at the route start
    Test.assert(d.climbLength[0] >= 880);
    return true;
}

(:test)
function detect_downhillGapSplitsClimbs(logger) {
    // climb 1: 10 pts at 6% (~900 m), then 5 pts descending 8 m each (>20 m tol),
    // then climb 2: 10 pts at 6% (~900 m) => two separate climbs
    var eles = new [26];
    for (var i = 0; i <= 9; i++) { eles[i] = 100 + i * 6; }
    for (var i = 10; i <= 14; i++) { eles[i] = eles[9] - (i - 9) * 8; }
    for (var i = 15; i < 26; i++) { eles[i] = eles[14] + (i - 14) * 6; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.detectClimbs(st, d);
    Test.assertEqual(d.climbCount, 2);
    return true;
}
