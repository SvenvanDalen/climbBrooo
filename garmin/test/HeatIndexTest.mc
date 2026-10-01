using Toybox.Test;

// Issue #227: heat-index warning. Covers the NWS heat-index formula (reference values from
// the NWS table, both regression and simple-formula ranges + the low-humidity adjustment),
// the alarm latch (fire once, hysteresis re-arm, 20-min reminder, off/null handling) and
// the banner text.

function hiNear(actual, expected, tol) {
    var d = actual - expected;
    if (d < 0) { d = -d; }
    return d <= tol;
}

// =========================== formula =========================================

(:test)
function hi_reference_90F_70pct(logger) {
    // NWS table: 90 °F / 70 % -> 106 °F (41.1 °C).
    Test.assert(hiNear(heatIndexC(32.2222, 70), 41.07, 0.1));
    return true;
}

(:test)
function hi_reference_35C_50pct(logger) {
    Test.assert(hiNear(heatIndexC(35, 50), 40.68, 0.1));
    return true;
}

(:test)
function hi_humid_28C_90pct(logger) {
    Test.assert(hiNear(heatIndexC(28, 90), 34.0, 0.1));
    return true;
}

(:test)
function hi_lowHumidityAdjustment_40C_10pct(logger) {
    Test.assert(hiNear(heatIndexC(40, 10), 36.71, 0.1));
    return true;
}

(:test)
function hi_mildWeather_usesSimpleFormula(logger) {
    Test.assert(hiNear(heatIndexC(20, 50), 19.36, 0.1));
    return true;
}

(:test)
function hi_unknownHumidity_returnsAirTemp(logger) {
    Test.assert(hiNear(heatIndexC(33, null), 33.0, 0.001));
    return true;
}

(:test)
function hi_nullTemp_returnsNull(logger) {
    Test.assert(heatIndexC(null, 50) == null);
    return true;
}

(:test)
function hi_humidityClampedTo100(logger) {
    Test.assert(hiNear(heatIndexC(30, 150), heatIndexC(30, 100), 0.001));
    return true;
}

// =========================== alarm latch =====================================

(:test)
function heatAlarm_firesOnceAtThreshold(logger) {
    var a = new HeatAlarm();
    Test.assert(!a.update(31.0, 32, 0));
    Test.assert(!a.hot);
    Test.assert(a.update(32.0, 32, 60000));
    Test.assert(a.hot);
    Test.assert(!a.update(33.0, 32, 120000));
    return true;
}

(:test)
function heatAlarm_hysteresis_staysHotUntilTwoBelow(logger) {
    var a = new HeatAlarm();
    a.update(33.0, 32, 0);
    Test.assert(!a.update(30.5, 32, 60000));
    Test.assert(a.hot);
    Test.assert(!a.update(29.9, 32, 120000));
    Test.assert(!a.hot);
    // Re-armed: crossing again fires again.
    Test.assert(a.update(32.5, 32, 180000));
    return true;
}

(:test)
function heatAlarm_remindsEvery20MinWhileHot(logger) {
    var a = new HeatAlarm();
    Test.assert(a.update(35.0, 32, 1000));
    Test.assert(!a.update(35.0, 32, 1000 + 19 * 60000));
    Test.assert(a.update(35.0, 32, 1000 + 20 * 60000));
    Test.assert(!a.update(35.0, 32, 1000 + 21 * 60000));
    return true;
}

(:test)
function heatAlarm_offOrNoReading_clearsWithoutAlert(logger) {
    var a = new HeatAlarm();
    a.update(35.0, 32, 0);
    Test.assert(!a.update(35.0, 0, 60000));
    Test.assert(!a.hot);
    a.update(35.0, 32, 120000);
    Test.assert(!a.update(null, 32, 180000));
    Test.assert(!a.hot);
    return true;
}

(:test)
function heatLabel_roundsToWholeDegrees(logger) {
    Test.assert(heatLabel(41.07).equals("HITTE 41°C"));
    Test.assert(heatLabel(39.6).equals("HITTE 40°C"));
    return true;
}
