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
