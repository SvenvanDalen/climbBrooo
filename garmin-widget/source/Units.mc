// Display units (issue #262). The phone sends the rider's unit choice as the optional
// top-level payload key "un", a bitmask: 1 = imperial distance/elevation (mi/ft),
// 2 = psi, 4 = degrees F. Absent or 0 = metric. Everything on the wire stays metric;
// only rendering converts. Kept identical in garmin, garmin-widget and garmin-surface.
module Units {

    const FLAG_IMPERIAL = 1;
    const FLAG_FAHRENHEIT = 4;
    // Below this many metres (~0.2 mi) imperial distances render in feet, not miles.
    const MILES_FROM_M = 322;

    // Parses the payload's "un" value: a non-negative Number, anything else = 0 (metric).
    function parseFlags(v) {
        if (v != null && v instanceof Toybox.Lang.Number && v >= 0) { return v; }
        return 0;
    }

    function isImperial(flags) {
        return flags != null && flags instanceof Toybox.Lang.Number
            && (flags & FLAG_IMPERIAL) != 0;
    }

    // Whole metres to whole feet (integer maths, no Float allocation).
    function toFeet(meters) {
        return (meters * 3281) / 1000;
    }

    // Distance label: "1.2km" / "850m" metric; "0.7mi" / "520ft" imperial (truncated
    // tenths, same as the metric formatting).
    function formatDist(meters, flags) {
        if (isImperial(flags)) {
            if (meters >= MILES_FROM_M) {
                var tenths = (meters * 10) / 1609;
                return (tenths / 10) + "." + (tenths % 10) + "mi";
            }
            return toFeet(meters) + "ft";
        }
        if (meters >= 1000) {
            return (meters / 1000) + "." + ((meters % 1000) / 100) + "km";
        }
        return meters + "m";
    }

    // Elevation label with unit: "80m" / "262ft".
    function formatElev(meters, flags) {
        if (isImperial(flags)) { return toFeet(meters) + "ft"; }
        return meters + "m";
    }

    // Temperature label from degrees Celsius (Number or Float), rounded to whole degrees:
    // "41°C" / "106°F". Only the label converts; thresholds stay in Celsius.
    function formatTemp(celsius, flags) {
        var v = celsius.toFloat();
        var unit = "°C";
        if (flags != null && flags instanceof Toybox.Lang.Number
                && (flags & FLAG_FAHRENHEIT) != 0) {
            v = v * 9.0 / 5.0 + 32.0;
            unit = "°F";
        }
        return (v + ((v < 0) ? -0.5 : 0.5)).toNumber() + unit;
    }
}
