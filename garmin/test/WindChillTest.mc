using Toybox.Test;

// Issue #248: felt temperature on descents. Covers the windchill formula (reference values
// from the Environment Canada table + validity bounds), the descent tracker's grade window
// and hysteresis, the 1 °C display latch, and the combined show/hide gate.

function wcNear(actual, expected, tol) {
    var d = actual - expected;
    if (d < 0) { d = -d; }
    return d <= tol;
}

// Feeds the tracker a straight line of `steps` samples, `stepM` apart, at `grade`.
function wcFeed(t, startDist, startAlt, steps, stepM, grade, speedMps) {
    var d = startDist;
    var a = startAlt;
    for (var i = 0; i < steps; i++) {
        d += stepM;
        a += stepM * grade;
        t.update(d, a, speedMps);
    }
    return t.descending;
}

// =========================== formula =========================================

(:test)
function wc_reference_minus10_at20(logger) {
    // Environment Canada table: -10 °C, 20 km/h -> -18 (formula: -17.86).
    Test.assert(wcNear(windChillC(-10, 20), -17.86, 0.1));
    return true;
}

(:test)
function wc_reference_zero_at30(logger) {
    Test.assert(wcNear(windChillC(0, 30), -6.47, 0.1));
    return true;
}

(:test)
function wc_reference_five_at40(logger) {
    Test.assert(wcNear(windChillC(5, 40), -0.71, 0.1));
    return true;
}

(:test)
function wc_aboveTenDegrees_returnsAirTemp(logger) {
    Test.assert(wcNear(windChillC(15, 60), 15.0, 0.001));
    Test.assert(wcNear(windChillC(10.5, 50), 10.5, 0.001));
    return true;
}

(:test)
function wc_belowMinWind_returnsAirTemp(logger) {
    Test.assert(wcNear(windChillC(0, 4.0), 0.0, 0.001));
    return true;
}

(:test)
function wc_atTenDegrees_isApplied(logger) {
    // Boundary is inclusive: 10 °C at 50 km/h feels ~5.5 °C.
    var wc = windChillC(10, 50);
    Test.assert(wc < 10.0);
    Test.assert(wcNear(wc, 5.49, 0.1));
    return true;
}

(:test)
function wc_neverWarmerThanAir(logger) {
    Test.assert(windChillC(10, 4.8) <= 10.0);
    Test.assert(windChillC(-20, 5) <= -20.0);
    return true;
}

(:test)
function wc_fasterFeelsColder(logger) {
    Test.assert(windChillC(5, 60) < windChillC(5, 30));
    return true;
}

(:test)
function wc_nullTemp_returnsNull(logger) {
    Test.assert(windChillC(null, 40) == null);
    return true;
}

// =========================== descent tracker =================================

(:test)
function descent_steepAndFast_enters(logger) {
    var t = new DescentTracker();
    Test.assert(wcFeed(t, 0, 500, 20, 20, -0.06, 12.0));
    return true;
}

(:test)
function descent_slow_doesNotEnter(logger) {
    // -6 % but only 18 km/h: below the 25 km/h enter threshold.
    var t = new DescentTracker();
    Test.assertEqual(wcFeed(t, 0, 500, 20, 20, -0.06, 5.0), false);
    return true;
}

(:test)
function descent_flatFast_doesNotEnter(logger) {
    // 40 km/h on the flat (tailwind) is not a descent.
    var t = new DescentTracker();
    Test.assertEqual(wcFeed(t, 0, 100, 20, 20, 0.0, 11.0), false);
    return true;
}

(:test)
function descent_climbing_doesNotEnter(logger) {
    var t = new DescentTracker();
    Test.assertEqual(wcFeed(t, 0, 100, 20, 20, 0.05, 8.0), false);
    return true;
}

(:test)
function descent_needsFullWindow(logger) {
    // Less than 150 m of samples: no grade yet, so no descent even at speed.
    var t = new DescentTracker();
    Test.assertEqual(wcFeed(t, 0, 500, 7, 20, -0.08, 14.0), false);
    return true;
}

(:test)
function descent_speedHysteresis(logger) {
    var t = new DescentTracker();
    wcFeed(t, 0, 500, 20, 20, -0.06, 12.0);
    // 22 km/h: below enter (25) but above exit (20) -> stays on.
    Test.assert(wcFeed(t, 400, 476, 2, 20, -0.06, 6.1));
    // 18 km/h -> off.
    Test.assertEqual(wcFeed(t, 440, 473.6, 1, 20, -0.06, 5.0), false);
    return true;
}

(:test)
function descent_gradeHysteresis_exitsOnFlat(logger) {
    var t = new DescentTracker();
    wcFeed(t, 0, 500, 20, 20, -0.06, 12.0);
    // -2 %: between exit (-1 %) and enter (-3 %) -> stays on after a full -2 % window.
    Test.assert(wcFeed(t, 400, 476, 15, 20, -0.02, 12.0));
    Test.assert(wcNear(t.lastGrade, -0.02, 0.001));
    // Flat at speed for a full window -> off.
    Test.assertEqual(wcFeed(t, 700, 470, 10, 20, 0.0, 12.0), false);
    return true;
}

(:test)
function descent_nullSpeed_exits(logger) {
    var t = new DescentTracker();
    wcFeed(t, 0, 500, 20, 20, -0.06, 12.0);
    Test.assertEqual(t.update(420, 474, null), false);
    return true;
}

(:test)
function descent_odometerReset_reanchors(logger) {
    var t = new DescentTracker();
    wcFeed(t, 0, 500, 20, 20, -0.06, 12.0);
    // Odometer jumps back to 0: must not produce a bogus huge grade / crash.
    t.update(0, 200, 12.0);
    Test.assert(wcNear(t.lastGrade, -0.06, 0.001));
    return true;
}

// =========================== latch + gate ====================================

(:test)
function latch_firstValueRounded(logger) {
    Test.assertEqual(latchFeltTemp(null, -3.4), -3);
    return true;
}

(:test)
function latch_smallChangeKeepsShown(logger) {
    Test.assertEqual(latchFeltTemp(-3, -3.8), -3);
    Test.assertEqual(latchFeltTemp(-3, -2.1), -3);
    return true;
}

(:test)
function latch_oneDegreeMoves(logger) {
    Test.assertEqual(latchFeltTemp(-3, -4.2), -4);
    return true;
}

(:test)
function latch_nullClears(logger) {
    Test.assert(latchFeltTemp(-3, null) == null);
    return true;
}

(:test)
function gate_descendingShowsFeltTemp(logger) {
    // 5 °C at 40 km/h (11.11 m/s) -> about -0.7 -> -1.
    Test.assertEqual(feltTempToShow(true, false, 5, 11.111, null), -1);
    return true;
}

(:test)
function gate_hiddenWhenNotDescending(logger) {
    Test.assert(feltTempToShow(false, false, 5, 11.1, -1) == null);
    return true;
}

(:test)
function gate_hiddenOnClimb(logger) {
    Test.assert(feltTempToShow(true, true, 5, 11.1, null) == null);
    return true;
}

(:test)
function gate_hiddenWithoutTemperature(logger) {
    Test.assert(feltTempToShow(true, false, null, 11.1, null) == null);
    return true;
}

(:test)
function gate_warmDayShowsAirTemp(logger) {
    // Above 10 °C the formula is out of range: the banner shows the air temperature.
    Test.assertEqual(feltTempToShow(true, false, 18, 15.0, null), 18);
    return true;
}

(:test)
function label_format(logger) {
    Test.assertEqual(feltTempLabel(-4), "VOELT -4°C");
    return true;
}
