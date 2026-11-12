using Toybox.Test;

// Issue #86: barometric drift offset from calibration-point elevations.

(:test)
function baro_firstSampleSetsOffset(logger) {
    var r = baroUpdate(0.0f, 0, 118.0f, 120);
    Test.assert(r[0] == 2.0f);
    Test.assert(r[1] == 1);
    Test.assert(baroCorrect(200.0f, r[0]) == 202.0f);
    return true;
}

(:test)
function baro_runningMeanAndOutlier(logger) {
    var r = baroUpdate(2.0f, 1, 116.0f, 120);       // sample 4 -> mean 3
    Test.assert(r[0] == 3.0f);
    Test.assert(r[1] == 2);
    var o = baroUpdate(r[0], r[1], 50.0f, 120);     // sample 70: > 200? no, but outlier vs 3
    Test.assert(o[0] == 3.0f);
    Test.assert(o[1] == 2);
    return true;
}

(:test)
function baro_rejectsAbsurdOffset(logger) {
    var r = baroUpdate(0.0f, 0, 0.0f, 900);
    Test.assert(r[0] == 0.0f);
    Test.assert(r[1] == 0);
    return true;
}

(:test)
function baro_windowCapsSamples(logger) {
    var off = 0.0f;
    var n = 0;
    for (var i = 0; i < 20; i++) {
        var r = baroUpdate(off, n, 100.0f, 110);
        off = r[0];
        n = r[1];
    }
    Test.assert(n == BARO_MAX_SAMPLES);
    Test.assert(off > 9.9f && off < 10.1f);
    return true;
}
