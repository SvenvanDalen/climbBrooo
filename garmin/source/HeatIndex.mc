using Toybox.Math as Math;

// Issue #227: heat-index warning ("hitte-index") during the ride. Watch-only, no wire
// format change: air temperature + relative humidity in, NWS heat index out, compared
// against the "heatIndexThreshold" app setting.
//
// Pure helpers only (no Sensor/Weather/Attention access) so the formula, the alarm latch
// and the banner text are unit-testable -- see garmin/test/HeatIndexTest.mc.
// ClimbProView feeds them once a minute.

// NWS heat index (Rothfusz regression with the NWS low/high-humidity adjustments, and
// Steadman's simple formula below ~27 °C where the regression is not valid). Works in °F
// internally, returns °C. A null humidity means "unknown": the air temperature itself is
// returned, so no humidity bonus is invented. null temperature -> null.
function heatIndexC(tempC, rhPct) {
    if (tempC == null) { return null; }
    var tc = tempC.toFloat();
    if (rhPct == null) { return tc; }
    var rh = rhPct.toFloat();
    if (rh < 0.0) { rh = 0.0; }
    if (rh > 100.0) { rh = 100.0; }
    var t = tc * 9.0 / 5.0 + 32.0;
    var simple = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094);
    var hi = simple;
    if ((simple + t) / 2.0 >= 80.0) {
        hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh
                - 0.00683783 * t * t - 0.05481717 * rh * rh + 0.00122874 * t * t * rh
                + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh;
        if (rh < 13.0 && t >= 80.0 && t <= 112.0) {
            var dt = t - 95.0;
            if (dt < 0) { dt = -dt; }
            hi -= ((13.0 - rh) / 4.0) * Math.sqrt((17.0 - dt) / 17.0);
        } else if (rh > 85.0 && t >= 80.0 && t <= 87.0) {
            hi += ((rh - 85.0) / 10.0) * ((87.0 - t) / 5.0);
        }
    }
    return (hi - 32.0) * 5.0 / 9.0;
}

// Alarm latch: fires once when the heat index reaches the threshold, then stays "hot"
// (banner on) until it drops REARM_MARGIN_C below it -- so a value hovering around the
// threshold can't buzz every minute. While it stays hot, a reminder fires every
// REMIND_MS (drink/cool-down nudge on a long hot ride).
class HeatAlarm {
    const REARM_MARGIN_C = 2.0;
    const REMIND_MS = 20 * 60 * 1000;

    var hot = false;
    hidden var lastAlertMs = 0;

    function initialize() {
    }

    // hiC: current heat index (°C) or null; thresholdC: setting (<= 0 = off); nowMs:
    // monotonic ms (System.getTimer). Returns true when an alert should fire now.
    // Off or no reading clears the state without alerting.
    function update(hiC, thresholdC, nowMs) {
        if (hiC == null || thresholdC == null || thresholdC <= 0) {
            hot = false;
            return false;
        }
        var h = hiC.toFloat();
        var th = thresholdC.toFloat();
        if (!hot) {
            if (h >= th) {
                hot = true;
                lastAlertMs = nowMs;
                return true;
            }
            return false;
        }
        if (h < th - REARM_MARGIN_C) {
            hot = false;
            return false;
        }
        if (nowMs - lastAlertMs >= REMIND_MS) {
            lastAlertMs = nowMs;
            return true;
        }
        return false;
    }
}

// Banner text, e.g. "HITTE 41°C" (heat index rounded to whole degrees).
function heatLabel(hiC) {
    return "HITTE " + Math.round(hiC.toFloat()).toNumber() + "°C";
}
