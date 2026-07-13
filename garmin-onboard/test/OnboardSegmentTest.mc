using Toybox.Test;

(:test)
function seg_colorCutoffs(logger) {
    Test.assertEqual(RouteParser.colorFor(0.0), 0);
    Test.assertEqual(RouteParser.colorFor(-0.05), 0);   // downhill clamps to 0
    Test.assertEqual(RouteParser.colorFor(0.019), 0);
    Test.assertEqual(RouteParser.colorFor(0.02), 1);
    Test.assertEqual(RouteParser.colorFor(0.039), 1);
    Test.assertEqual(RouteParser.colorFor(0.04), 2);
    Test.assertEqual(RouteParser.colorFor(0.06), 3);
    Test.assertEqual(RouteParser.colorFor(0.08), 4);
    Test.assertEqual(RouteParser.colorFor(0.10), 5);
    Test.assertEqual(RouteParser.colorFor(0.15), 5);
    return true;
}

(:test)
function seg_thirteenSegmentsSummingToLength(logger) {
    // ~2000 m at 5% via parse() so smoothing+detection+segmentation run together
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    RouteParser.parse(st, d);
    Test.assert(d.parsed);
    Test.assertEqual(d.climbCount, 1);
    // ceil(1 / 0.08) = 13 segments
    Test.assertEqual(d.segCount[0], 13);
    var sum = 0;
    for (var s = 0; s < d.segCount[0]; s++) { sum += d.segDist[0][s]; }
    var diff = sum - d.climbLength[0];
    if (diff < 0) { diff = -diff; }
    Test.assert(diff <= 13);   // <= 1 m rounding per segment
    return true;
}

(:test)
function seg_constantGradient_uniformColorAndGradient(logger) {
    var eles = new [21];
    for (var i = 0; i < 21; i++) { eles[i] = 100 + i * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    // Segment WITHOUT smoothing so the 5% is exact: detect + segment directly.
    RouteParser.detectClimbs(st, d);
    RouteParser.segmentClimb(st, d, 0);
    for (var s = 0; s < d.segCount[0]; s++) {
        Test.assert(d.segGradient[0][s] >= 47 && d.segGradient[0][s] <= 53);
        Test.assertEqual(d.segColor[0][s], 2);   // 5% => 0.04..0.06 => index 2
    }
    return true;
}
