using Toybox.Math as Math;

// Issue #217: Everesting tracker. The phone plans the attempt (wire "ev": target metres,
// planned repeats, start + top coordinates of the climb); the watch only counts summit
// passes and compares the activity's total ascent with the target.
//
// Pure class (no Activity/Attention access) so the counting is unit-testable -- see
// garmin/test/EverestingTest.mc. ClimbProView feeds it every tick.

// Events returned by EverestTracker.update().
const EVEREST_NONE = 0;
const EVEREST_REPEAT = 1;            // a repeat was completed (summit reached)
const EVEREST_DONE = 2;              // total ascent reached the target (fires once)

class EverestTracker {
    const TOP_RADIUS_M = 60.0;       // within this of the top coordinate = summit reached
    const START_RADIUS_M = 100.0;    // back within this of the start = next repeat armed

    var repeats = 0;
    var done = false;
    hidden var armed = true;         // true = climbing, waiting for the top
    hidden var plan = null;          // [targetM, repeats, sLat, sLon, tLat, tLon]

    function initialize() {
    }

    // Starts over for a new plan (or null = no attempt). Same plan array -> no reset, so a
    // resync of the same route mid-attempt keeps the count.
    function setPlan(p) {
        if (p == null) {
            plan = null;
            reset();
            return;
        }
        if (plan != null && samePlan(plan, p)) { return; }
        plan = p;
        reset();
    }

    function reset() {
        repeats = 0;
        done = false;
        armed = true;
    }

    function active() {
        return plan != null;
    }

    function targetM() {
        return plan == null ? 0 : plan[0];
    }

    function plannedRepeats() {
        return plan == null ? 0 : plan[1];
    }

    // lat/lon: current position (degrees) or null; ascentM: activity total ascent (m) or
    // null. Returns one EVEREST_* value. A done event takes precedence over a repeat on the
    // same tick (the repeat is still counted).
    function update(lat, lon, ascentM) {
        if (plan == null) { return EVEREST_NONE; }
        var event = EVEREST_NONE;
        if (lat != null && lon != null) {
            if (armed && distM(lat, lon, plan[4], plan[5]) <= TOP_RADIUS_M) {
                repeats++;
                armed = false;
                event = EVEREST_REPEAT;
            } else if (!armed && distM(lat, lon, plan[2], plan[3]) <= START_RADIUS_M) {
                armed = true;
            }
        }
        if (!done && ascentM != null && ascentM >= plan[0]) {
            done = true;
            event = EVEREST_DONE;
        }
        return event;
    }

    hidden function samePlan(a, b) {
        for (var i = 0; i < 6; i++) {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }

    // Equirectangular distance in metres; accurate to well under a metre at these ranges.
    hidden function distM(lat1, lon1, lat2, lon2) {
        var r = 6371000.0;
        var dLat = (lat2 - lat1) * Math.PI / 180.0;
        var dLon = (lon2 - lon1) * Math.PI / 180.0 * Math.cos((lat1 + lat2) / 2.0 * Math.PI / 180.0);
        return r * Math.sqrt(dLat * dLat + dLon * dLon);
    }
}

// Banner text, e.g. "EVEREST 3/12  2650/8848m"; "EVEREST GEHAALD 8848m" once done.
function everestLabel(repeats, planned, ascentM, targetM) {
    var up = (ascentM == null) ? 0 : ascentM.toNumber();
    if (up >= targetM) {
        return "EVEREST GEHAALD " + up + "m";
    }
    return "EVEREST " + repeats + "/" + planned + "  " + up + "/" + targetM + "m";
}
