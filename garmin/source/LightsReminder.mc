using Toybox.Math as Math;

// Issue #198: "verlichtingsherinnering bij schemering". Watch-only, no wire format change:
// the sun times are computed on the watch from the GPS position and the clock, so the
// reminder works offline and without any route payload.
//
// Pure helpers only (no Position/Time access) so the solar maths and the once-per-ride
// latch are unit-testable -- see garmin/test/LightsReminderTest.mc. ClimbProView feeds them.

// Unix epoch second of J2000.0 (2000-01-01 12:00 UTC).
const SUN_J2000_EPOCH = 946728000;
// Sun altitude at "official" sunrise/sunset: refraction + solar disc radius.
const SUN_ALTITUDE_DEG = -0.833;

// Sunrise and sunset for solar day `n` (whole days since J2000.0) at latDeg/lonDeg (east
// positive), via the standard sunrise equation (accuracy ~1-2 min below the polar circles).
// Returns [riseEpochSec, setEpochSec] as Doubles, or:
//   [null, null]   -- polar night, the sun stays below the horizon all day;
//   [-1.0, -1.0]   -- midnight sun, the sun never sets.
function sunTimesForDay(n, latDeg, lonDeg) {
    var toRad = Math.PI / 180.0d;
    var jStar = n.toDouble() - lonDeg.toDouble() / 360.0d;
    var m = mod360(357.5291d + 0.98560028d * jStar);
    var mr = m * toRad;
    var c = 1.9148d * Math.sin(mr) + 0.02d * Math.sin(2 * mr) + 0.0003d * Math.sin(3 * mr);
    var lambda = mod360(m + c + 180.0d + 102.9372d) * toRad;
    var transit = jStar + 0.0053d * Math.sin(mr) - 0.0069d * Math.sin(2 * lambda);
    var sinDecl = Math.sin(lambda) * Math.sin(23.4397d * toRad);
    var cosDecl = Math.sqrt(1.0d - sinDecl * sinDecl);
    var phi = latDeg.toDouble() * toRad;
    var cosOmega = (Math.sin(SUN_ALTITUDE_DEG * toRad) - Math.sin(phi) * sinDecl)
            / (Math.cos(phi) * cosDecl);
    if (cosOmega > 1.0d) { return [null, null]; }
    if (cosOmega < -1.0d) { return [-1.0d, -1.0d]; }
    var halfDay = Math.acos(cosOmega) / toRad / 360.0d;
    return [SUN_J2000_EPOCH + (transit - halfDay) * 86400.0d,
            SUN_J2000_EPOCH + (transit + halfDay) * 86400.0d];
}

function mod360(x) {
    var r = x - 360.0d * Math.floor(x / 360.0d);
    return r;
}

// True when lights should be on at epochSec: before sunrise, or from `leadMin` minutes before
// sunset onwards. Checks the solar day around the moment plus its neighbours, so it is right
// in any timezone and across midnight. Polar night is always dark; midnight sun never is.
function lightsNeeded(epochSec, latDeg, lonDeg, leadMin) {
    if (epochSec == null || latDeg == null || lonDeg == null) { return false; }
    var t = epochSec.toDouble();
    var days = (t - SUN_J2000_EPOCH) / 86400.0d + lonDeg.toDouble() / 360.0d;
    var n0 = Math.round(days).toLong();
    var lead = (leadMin == null ? 0 : leadMin) * 60.0d;
    for (var k = -1; k <= 1; k++) {
        var st = sunTimesForDay(n0 + k, latDeg, lonDeg);
        if (st[0] == null) { continue; }                // polar night: no daylight that day
        if (st[0] < 0) { return false; }                // midnight sun: never dark
        if (t >= st[0] && t < st[1] - lead) { return false; }
    }
    return true;
}

// Once-per-ride latch: fires the moment lights become needed (or at the first fix when a
// ride starts in the dark) and then never again for that ride, so a GPS jump or a cloud of
// minutes around the threshold can't re-trigger it. The sun maths runs at most once per
// CHECK_INTERVAL_S (battery rule: no trigonometry every tick).
class LightsReminder {
    const CHECK_INTERVAL_S = 60;
    const BANNER_S = 30;

    var fired = false;
    var bannerUntil = -1;           // epoch second until which the banner shows
    hidden var lastCheck = -1;

    function initialize() {
    }

    function reset() {
        fired = false;
        bannerUntil = -1;
        lastCheck = -1;
    }

    // Returns true exactly once: on the check where lights first turn out to be needed.
    // A null position (no GPS fix yet) or a disabled setting never fires.
    function update(epochSec, latDeg, lonDeg, leadMin, enabled) {
        if (!enabled || fired || epochSec == null || latDeg == null || lonDeg == null) {
            return false;
        }
        if (lastCheck >= 0 && epochSec - lastCheck < CHECK_INTERVAL_S && epochSec >= lastCheck) {
            return false;
        }
        lastCheck = epochSec;
        if (!lightsNeeded(epochSec, latDeg, lonDeg, leadMin)) { return false; }
        fired = true;
        bannerUntil = epochSec + BANNER_S;
        return true;
    }

    function bannerVisible(epochSec) {
        return epochSec != null && bannerUntil >= 0 && epochSec < bannerUntil;
    }
}

function lightsReminderLabel() {
    return "LICHT AAN";
}
