using Toybox.Test;
using Toybox.Math as Math;

// Issue #198: lights reminder at dusk. Covers the sunrise equation against known sun times
// (Amsterdam summer/winter, Sydney, San Francisco across UTC midnight), polar night and
// midnight sun, the lead time before sunset, and the once-per-ride latch + check throttle.
// Reference epochs are UTC; expected sun times from the same equation cross-checked against
// published tables (within ~2 min).

const LR_AMS_LAT = 52.37;
const LR_AMS_LON = 4.90;
// 2026-12-21 00:00 UTC.
const LR_DEC21 = 1797811200;

function lrNear(actual, expected, tolSec) {
    var d = actual - expected;
    if (d < 0) { d = -d; }
    return d <= tolSec;
}

// Solar day index for a UTC epoch at a longitude, as lightsNeeded() computes it.
function lrDay(epoch, lon) {
    return Math.round((epoch - SUN_J2000_EPOCH) / 86400.0d + lon / 360.0d).toLong();
}

(:test)
function lr_amsterdamJune_sunTimes(logger) {
    var day = 1781913600;   // 2026-06-20 00:00 UTC
    day += 86400;           // 2026-06-21
    var st = sunTimesForDay(lrDay(day + 43200, LR_AMS_LON), LR_AMS_LAT, LR_AMS_LON);
    // Sunrise 03:17 UTC, sunset 20:06 UTC.
    Test.assert(lrNear(st[0], day + 3 * 3600 + 17 * 60, 120));
    Test.assert(lrNear(st[1], day + 20 * 3600 + 6 * 60, 120));
    return true;
}

(:test)
function lr_amsterdamDecember_sunTimes(logger) {
    var st = sunTimesForDay(lrDay(LR_DEC21 + 43200, LR_AMS_LON), LR_AMS_LAT, LR_AMS_LON);
    // Sunrise 07:48 UTC, sunset 15:28 UTC.
    Test.assert(lrNear(st[0], LR_DEC21 + 7 * 3600 + 48 * 60, 120));
    Test.assert(lrNear(st[1], LR_DEC21 + 15 * 3600 + 28 * 60, 120));
    return true;
}

(:test)
function lr_amsterdamDecember_leadTimeBeforeSunset(logger) {
    // Sunset 15:28 UTC; with a 15 min lead lights are needed from ~15:13.
    Test.assert(!lightsNeeded(LR_DEC21 + 12 * 3600, LR_AMS_LAT, LR_AMS_LON, 15));
    Test.assert(!lightsNeeded(LR_DEC21 + 15 * 3600 + 10 * 60, LR_AMS_LAT, LR_AMS_LON, 15));
    Test.assert(lightsNeeded(LR_DEC21 + 15 * 3600 + 16 * 60, LR_AMS_LAT, LR_AMS_LON, 15));
    // Without lead it's still light at 15:16.
    Test.assert(!lightsNeeded(LR_DEC21 + 15 * 3600 + 16 * 60, LR_AMS_LAT, LR_AMS_LON, 0));
    return true;
}

(:test)
function lr_beforeSunriseNeedsLights(logger) {
    // Amsterdam 21 Dec: 06:30 UTC is before the 07:48 sunrise; 23:00 UTC is night.
    Test.assert(lightsNeeded(LR_DEC21 + 6 * 3600 + 30 * 60, LR_AMS_LAT, LR_AMS_LON, 15));
    Test.assert(lightsNeeded(LR_DEC21 + 23 * 3600, LR_AMS_LAT, LR_AMS_LON, 15));
    Test.assert(!lightsNeeded(LR_DEC21 + 8 * 3600, LR_AMS_LAT, LR_AMS_LON, 15));
    return true;
}

(:test)
function lr_sanFrancisco_sunsetAfterUtcMidnight(logger) {
    // 21 June: sunset 20:34 PDT = 03:34 UTC on 22 June. 20:00 PDT is light, 20:25 PDT
    // (inside the 15 min lead) needs lights.
    var june22 = 1782086400;   // 2026-06-22 00:00 UTC
    Test.assert(!lightsNeeded(june22 + 3 * 3600, 37.77, -122.42, 15));
    Test.assert(lightsNeeded(june22 + 3 * 3600 + 25 * 60, 37.77, -122.42, 15));
    return true;
}

(:test)
function lr_sydney_southernHemisphere(logger) {
    // 20 March: sunrise 06:57 AEDT = 19:57 UTC on 19 March, sunset 08:07 UTC.
    var mar20 = 1773964800;    // 2026-03-20 00:00 UTC
    Test.assert(!lightsNeeded(mar20 + 2 * 3600, -33.87, 151.21, 15));
    Test.assert(lightsNeeded(mar20 + 9 * 3600, -33.87, 151.21, 15));
    return true;
}

(:test)
function lr_polarNightAndMidnightSun(logger) {
    // Tromsø: midnight sun in June, polar night in December.
    var june21 = 1782000000;
    Test.assert(!lightsNeeded(june21 + 23 * 3600, 69.65, 18.96, 15));
    var dec = sunTimesForDay(lrDay(LR_DEC21 + 43200, 18.96), 69.65, 18.96);
    Test.assert(dec[0] == null);
    Test.assert(lightsNeeded(LR_DEC21 + 12 * 3600, 69.65, 18.96, 15));
    return true;
}

(:test)
function lr_nullInputsNeverNeedLights(logger) {
    Test.assert(!lightsNeeded(null, LR_AMS_LAT, LR_AMS_LON, 15));
    Test.assert(!lightsNeeded(LR_DEC21, null, LR_AMS_LON, 15));
    Test.assert(!lightsNeeded(LR_DEC21, LR_AMS_LAT, null, 15));
    return true;
}

(:test)
function lr_reminder_firesOnceAtDusk(logger) {
    var r = new LightsReminder();
    var t = LR_DEC21 + 14 * 3600;           // 14:00 UTC, light
    Test.assert(!r.update(t, LR_AMS_LAT, LR_AMS_LON, 15, true));
    var fired = 0;
    for (var i = 1; i <= 120; i++) {        // two hours, one tick per minute
        if (r.update(t + i * 60, LR_AMS_LAT, LR_AMS_LON, 15, true)) { fired++; }
    }
    Test.assert(fired == 1);
    Test.assert(r.fired);
    return true;
}

(:test)
function lr_reminder_firesAtFirstFixWhenStartingInTheDark(logger) {
    var r = new LightsReminder();
    var t = LR_DEC21 + 18 * 3600;
    Test.assert(!r.update(t, null, null, 15, true));      // no GPS fix yet
    Test.assert(r.update(t + 5, LR_AMS_LAT, LR_AMS_LON, 15, true));
    Test.assert(r.bannerVisible(t + 10));
    Test.assert(!r.bannerVisible(t + 5 + r.BANNER_S));
    Test.assert(!r.update(t + 3600, LR_AMS_LAT, LR_AMS_LON, 15, true));
    return true;
}

(:test)
function lr_reminder_disabledNeverFires(logger) {
    var r = new LightsReminder();
    Test.assert(!r.update(LR_DEC21 + 20 * 3600, LR_AMS_LAT, LR_AMS_LON, 15, false));
    Test.assert(!r.fired);
    Test.assert(!r.bannerVisible(LR_DEC21 + 20 * 3600));
    return true;
}

(:test)
function lr_reminder_throttlesChecksAndResets(logger) {
    var r = new LightsReminder();
    var t = LR_DEC21 + 15 * 3600;           // 15:00, still light
    Test.assert(!r.update(t, LR_AMS_LAT, LR_AMS_LON, 15, true));
    // 30 s later it's dark per the maths, but within the 60 s throttle nothing is checked.
    Test.assert(!r.update(t + 30, LR_AMS_LAT, LR_AMS_LON, 60, true));
    Test.assert(r.update(t + 60, LR_AMS_LAT, LR_AMS_LON, 60, true));
    r.reset();
    Test.assert(!r.fired);
    Test.assert(r.update(t + 61, LR_AMS_LAT, LR_AMS_LON, 60, true));
    return true;
}
