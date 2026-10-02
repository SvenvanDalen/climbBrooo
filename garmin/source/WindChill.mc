using Toybox.Math as Math;

// Issue #248: felt temperature ("gevoelstemperatuur") on descents. Watch-only, no wire
// format change: ambient temperature + rider speed (the riding wind) in, windchill out.
//
// Pure helpers only (no Sensor/Activity access) so the formula and the descent gating
// are unit-testable -- see garmin/test/WindChillTest.mc. ClimbProView feeds them.

// Standard windchill (JAG/TI, Environment Canada / NWS 2001):
//   T_wc = 13.12 + 0.6215 T - 11.37 v^0.16 + 0.3965 T v^0.16   (T in °C, v in km/h)
// Only defined for T <= 10 °C and v >= 4.8 km/h; outside that range the felt temperature
// is the air temperature itself. Never returns more than tempC.
function windChillC(tempC, speedKmh) {
    if (tempC == null || speedKmh == null) { return tempC; }
    var t = tempC.toFloat();
    var v = speedKmh.toFloat();
    if (t > 10.0 || v < 4.8) { return t; }
    var vp = Math.pow(v, 0.16);
    var wc = 13.12 + 0.6215 * t - 11.37 * vp + 0.3965 * t * vp;
    return (wc < t) ? wc : t;
}

// Tracks whether the rider is on a descent, from odometer + barometric altitude samples
// the datafield already gets every tick. Grade is measured over >= WINDOW_M of distance
// (not per tick, so GPS/baro noise can't flip it), and the state has hysteresis on both
// grade and speed so it doesn't flicker at the thresholds.
class DescentTracker {
    const WINDOW_M = 150.0;          // distance over which one grade sample is taken
    const ENTER_GRADE = -0.03;       // <= -3 % to start showing
    const EXIT_GRADE = -0.01;        // > -1 % stops it
    const ENTER_SPEED_MPS = 6.944;   // 25 km/h
    const EXIT_SPEED_MPS = 5.556;    // 20 km/h

    var descending = false;
    var lastGrade = 0.0;
    hidden var anchorDist = -1.0;
    hidden var anchorAlt = 0.0;

    function initialize() {
    }

    function reset() {
        descending = false;
        lastGrade = 0.0;
        anchorDist = -1.0;
    }

    // distM: elapsed distance (m); altM: altitude (m); speedMps: current speed (m/s).
    // Any null input leaves the state untouched except that a missing speed ends the
    // descent (no speed means no riding wind to speak of). Returns `descending`.
    function update(distM, altM, speedMps) {
        if (speedMps == null) {
            descending = false;
            return descending;
        }
        if (distM != null && altM != null) {
            var d = distM.toFloat();
            var a = altM.toFloat();
            if (anchorDist < 0.0 || d < anchorDist) {
                // First sample, or the odometer went backwards (new activity/reset).
                anchorDist = d;
                anchorAlt = a;
            } else if (d - anchorDist >= WINDOW_M) {
                lastGrade = (a - anchorAlt) / (d - anchorDist);
                anchorDist = d;
                anchorAlt = a;
            }
        }
        var v = speedMps.toFloat();
        if (descending) {
            if (v < EXIT_SPEED_MPS || lastGrade > EXIT_GRADE) { descending = false; }
        } else {
            if (v >= ENTER_SPEED_MPS && lastGrade <= ENTER_GRADE) { descending = true; }
        }
        return descending;
    }
}

// Display latch: the shown whole-degree value only moves when the new felt temperature
// differs by >= 1 °C from what's on screen (battery rule: no redraw churn from sensor
// noise around a rounding boundary). null in -> null out (nothing shown).
function latchFeltTemp(shown, feltC) {
    if (feltC == null) { return null; }
    var f = feltC.toFloat();
    if (shown == null) { return Math.round(f).toNumber(); }
    var diff = f - shown.toFloat();
    if (diff < 0) { diff = -diff; }
    return (diff >= 1.0) ? Math.round(f).toNumber() : shown;
}

// Per-tick decision for the datafield: the whole-degree felt temperature to show, or null
// to show nothing. Shown only while descending and not on a climb (the climb view stays
// untouched), and only when a temperature reading exists. speedMps is the rider speed,
// used as the wind speed (weather wind is ignored).
function feltTempToShow(descending, onClimb, tempC, speedMps, shown) {
    if (!descending || onClimb || tempC == null || speedMps == null) { return null; }
    return latchFeltTemp(shown, windChillC(tempC, speedMps.toFloat() * 3.6));
}

// Banner text for the felt temperature, e.g. "VOELT -4°C" / "VOELT 25°F" (units = payload
// "un" bitmask).
function feltTempLabel(c, units) {
    return "VOELT " + Units.formatTemp(c, units);
}
