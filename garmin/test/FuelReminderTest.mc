using Toybox.Test;

// Issue #184: eat/drink reminder. Covers the temperature correction (factor bands, floor),
// the time trigger, the ascent trigger (+ minimum gap), once-per-trigger idempotency,
// off/null handling, switching on mid-ride and a new activity resetting the ride.

const FUEL_T_MIN = 60000;

// =========================== temperature correction ==========================

(:test)
function fuel_heatFactor_bands(logger) {
    Test.assert(fuelHeatFactorPct(20, 25) == 100);
    Test.assert(fuelHeatFactorPct(25, 25) == 75);
    Test.assert(fuelHeatFactorPct(32, 25) == 75);
    Test.assert(fuelHeatFactorPct(33, 25) == 50);
    return true;
}

(:test)
function fuel_heatFactor_offOrUnknown(logger) {
    Test.assert(fuelHeatFactorPct(35, 0) == 100);
    Test.assert(fuelHeatFactorPct(null, 25) == 100);
    Test.assert(fuelHeatFactorPct(35, null) == 100);
    return true;
}

(:test)
function fuel_intervalMs_shortenedAndFloored(logger) {
    Test.assert(fuelIntervalMs(30, 15, 25) == 30 * FUEL_T_MIN);
    Test.assert(fuelIntervalMs(40, 26, 25) == 30 * FUEL_T_MIN);
    Test.assert(fuelIntervalMs(30, 34, 25) == 15 * FUEL_T_MIN);
    // 15 min halved would be 7.5 min: floored at the 10-min minimum gap.
    Test.assert(fuelIntervalMs(15, 34, 25) == 10 * FUEL_T_MIN);
    Test.assert(fuelIntervalMs(0, 34, 25) == 0);
    Test.assert(fuelIntervalMs(null, 34, 25) == 0);
    return true;
}

(:test)
function fuel_climbStep_shortened(logger) {
    Test.assert(fuelClimbStepM(500, 20, 25) == 500);
    Test.assert(fuelClimbStepM(500, 34, 25) == 250);
    Test.assert(fuelClimbStepM(0, 34, 25) == 0);
    return true;
}

// =========================== time trigger ====================================

(:test)
function fuel_time_firesAtInterval(logger) {
    var r = new FuelReminder();
    Test.assert(!r.update(0, 0, null, 30, 0, 25));
    Test.assert(!r.update(29 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.update(30 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.count == 1);
    return true;
}

(:test)
function fuel_time_oncePerTrigger(logger) {
    var r = new FuelReminder();
    r.update(0, 0, null, 30, 0, 25);
    Test.assert(r.update(30 * FUEL_T_MIN, 0, null, 30, 0, 25));
    // Following ticks don't re-fire; the next reminder is a full interval later.
    Test.assert(!r.update(30 * FUEL_T_MIN + 1000, 0, null, 30, 0, 25));
    Test.assert(!r.update(59 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.update(60 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.count == 2);
    return true;
}

(:test)
function fuel_time_hotWeatherShortens(logger) {
    var r = new FuelReminder();
    r.update(0, 0, 28, 40, 0, 25);
    Test.assert(!r.update(29 * FUEL_T_MIN, 0, 28, 40, 0, 25));
    Test.assert(r.update(30 * FUEL_T_MIN, 0, 28, 40, 0, 25));
    return true;
}

(:test)
function fuel_time_warmingUpFiresWhenOverdue(logger) {
    // 25 min in at 20 °C: not due. It turns hot (40 -> 20 min): due right away, once.
    var r = new FuelReminder();
    r.update(0, 0, 20, 40, 0, 25);
    Test.assert(!r.update(25 * FUEL_T_MIN, 0, 20, 40, 0, 25));
    Test.assert(r.update(25 * FUEL_T_MIN + 1000, 0, 34, 40, 0, 25));
    Test.assert(!r.update(25 * FUEL_T_MIN + 2000, 0, 34, 40, 0, 25));
    return true;
}

// =========================== ascent trigger ==================================

(:test)
function fuel_ascent_firesAfterClimbing(logger) {
    var r = new FuelReminder();
    r.update(0, 100, null, 0, 300, 25);
    Test.assert(!r.update(15 * FUEL_T_MIN, 399, null, 0, 300, 25));
    Test.assert(r.update(16 * FUEL_T_MIN, 400, null, 0, 300, 25));
    // Counter restarts at the reminder: next one 300 m higher.
    Test.assert(!r.update(30 * FUEL_T_MIN, 650, null, 0, 300, 25));
    Test.assert(r.update(31 * FUEL_T_MIN, 700, null, 0, 300, 25));
    return true;
}

(:test)
function fuel_ascent_respectsMinimumGap(logger) {
    var r = new FuelReminder();
    r.update(0, 0, null, 0, 200, 25);
    Test.assert(!r.update(9 * FUEL_T_MIN, 250, null, 0, 200, 25));
    Test.assert(r.update(10 * FUEL_T_MIN, 250, null, 0, 200, 25));
    return true;
}

(:test)
function fuel_ascent_resetsTimeCounter(logger) {
    // Ascent fires at 20 min; the 30-min time trigger then counts from there (50 min).
    var r = new FuelReminder();
    r.update(0, 0, null, 30, 300, 25);
    Test.assert(r.update(20 * FUEL_T_MIN, 300, null, 30, 300, 25));
    Test.assert(!r.update(30 * FUEL_T_MIN, 320, null, 30, 300, 25));
    Test.assert(r.update(50 * FUEL_T_MIN, 320, null, 30, 300, 25));
    return true;
}

(:test)
function fuel_ascent_lateFirstReadingBecomesBaseline(logger) {
    // No barometric ascent at the start; the first reading must not count as climbed.
    var r = new FuelReminder();
    r.update(0, null, null, 0, 300, 25);
    Test.assert(!r.update(15 * FUEL_T_MIN, 500, null, 0, 300, 25));
    Test.assert(!r.update(16 * FUEL_T_MIN, 700, null, 0, 300, 25));
    Test.assert(r.update(17 * FUEL_T_MIN, 800, null, 0, 300, 25));
    return true;
}

// =========================== off / reset =====================================

(:test)
function fuel_off_neverFires(logger) {
    var r = new FuelReminder();
    r.update(0, 0, 35, 0, 0, 25);
    Test.assert(!r.update(300 * FUEL_T_MIN, 5000, 35, 0, 0, 25));
    Test.assert(!r.update(null, 5000, 35, 30, 300, 25));
    return true;
}

(:test)
function fuel_switchedOnMidRideStartsFresh(logger) {
    var r = new FuelReminder();
    r.update(0, 0, null, 0, 0, 25);
    r.update(90 * FUEL_T_MIN, 0, null, 0, 0, 25);
    Test.assert(!r.update(91 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.update(120 * FUEL_T_MIN, 0, null, 30, 0, 25));
    return true;
}

(:test)
function fuel_newActivityResets(logger) {
    var r = new FuelReminder();
    r.update(0, 0, null, 30, 0, 25);
    Test.assert(r.update(30 * FUEL_T_MIN, 0, null, 30, 0, 25));
    // Timer back to 0: a new ride, counting from scratch.
    Test.assert(!r.update(0, 0, null, 30, 0, 25));
    Test.assert(r.count == 0);
    Test.assert(!r.update(29 * FUEL_T_MIN, 0, null, 30, 0, 25));
    Test.assert(r.update(30 * FUEL_T_MIN, 0, null, 30, 0, 25));
    return true;
}

(:test)
function fuel_label(logger) {
    Test.assert(fuelReminderLabel().equals("ETEN & DRINKEN"));
    return true;
}
