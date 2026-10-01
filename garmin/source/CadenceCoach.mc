// Issue #179: cadence coach ("cadans-coach"). Watch-only, no wire format change:
// Activity.Info.currentCadence in, a nudge out when the cadence stays below or above the
// target band set in the Connect IQ app settings ("cadenceCoach", "cadenceLow",
// "cadenceHigh"; edited from the phone in Garmin Connect Mobile).
//
// Pure class (no Activity/Attention access, time passed in as ms) so it is unit-testable --
// see garmin/test/CadenceCoachTest.mc. ClimbProView feeds it each tick.
//
// Anti-spam (hysteresis):
//  - out of band for SUSTAIN_MS of pedalling before the first nudge;
//  - a nudge latches its direction: no repeat until the cadence was back inside the band
//    (REARM_MARGIN_RPM inside the edge) for REARM_MS;
//  - at most one nudge per COOLDOWN_MS, whichever direction.
// Coasting (cadence 0) and a missing sensor (null) are not "too low": they neither count
// towards nor reset a pending out-of-band stretch, they just pause it.

const CADENCE_OK = 0;
const CADENCE_LOW = 1;
const CADENCE_HIGH = 2;

class CadenceCoach {
    const SUSTAIN_MS = 30000;          // out of band this long (pedalling) before a nudge
    const REARM_MS = 20000;            // back in band this long before the same nudge re-arms
    const REARM_MARGIN_RPM = 3;        // "back in band" = this far inside the band edge
    const COOLDOWN_MS = 2 * 60 * 1000; // minimum time between two nudges

    // Direction the banner should show (CADENCE_LOW/HIGH) while the rider is still out of
    // band after a nudge; CADENCE_OK otherwise.
    var shownDir = CADENCE_OK;

    hidden var outDir = CADENCE_OK;    // direction of the pending out-of-band stretch
    hidden var outMs = 0;              // pedalling time accumulated in outDir
    hidden var prevOutMs = -1;         // timestamp of the previous out-of-band sample, -1 = none
    hidden var latchedDir = CADENCE_OK; // last nudged direction, cleared on re-arm
    hidden var inSinceMs = -1;         // start of the current "back in band" stretch
    hidden var lastAlertMs = -1;

    function initialize() {
    }

    function reset() {
        shownDir = CADENCE_OK;
        outDir = CADENCE_OK;
        outMs = 0;
        prevOutMs = -1;
        latchedDir = CADENCE_OK;
        inSinceMs = -1;
    }

    // cad: rpm or null; low/high: band edges in rpm (<= 0 = that side off); enabled: master
    // toggle; nowMs: monotonic ms. Returns CADENCE_LOW / CADENCE_HIGH when a nudge should
    // fire now, else CADENCE_OK.
    function update(cad, low, high, enabled, nowMs) {
        if (!enabled || low == null || high == null
                || (low <= 0 && high <= 0) || (low > 0 && high > 0 && low >= high)) {
            reset();
            return CADENCE_OK;
        }
        if (cad == null || cad <= 0) {
            // Freewheeling / no sensor: pause, don't reset.
            prevOutMs = -1;
            inSinceMs = -1;
            shownDir = CADENCE_OK;
            return CADENCE_OK;
        }

        var dir = CADENCE_OK;
        if (low > 0 && cad < low) {
            dir = CADENCE_LOW;
        } else if (high > 0 && cad > high) {
            dir = CADENCE_HIGH;
        }

        if (dir == CADENCE_OK) {
            outDir = CADENCE_OK;
            outMs = 0;
            prevOutMs = -1;
            shownDir = CADENCE_OK;
            if (latchedDir != CADENCE_OK) {
                if (insideWithMargin(cad, low, high)) {
                    if (inSinceMs < 0) { inSinceMs = nowMs; }
                    if (nowMs - inSinceMs >= REARM_MS) {
                        latchedDir = CADENCE_OK;
                        inSinceMs = -1;
                    }
                } else {
                    inSinceMs = -1;
                }
            }
            return CADENCE_OK;
        }

        inSinceMs = -1;
        if (dir != outDir) {
            outDir = dir;
            outMs = 0;
        } else if (prevOutMs >= 0) {
            outMs += nowMs - prevOutMs;
        }
        prevOutMs = nowMs;

        if (dir == latchedDir) {
            shownDir = dir;
            return CADENCE_OK;
        }
        shownDir = CADENCE_OK;
        if (outMs >= SUSTAIN_MS
                && (lastAlertMs < 0 || nowMs - lastAlertMs >= COOLDOWN_MS)) {
            latchedDir = dir;
            lastAlertMs = nowMs;
            shownDir = dir;
            return dir;
        }
        return CADENCE_OK;
    }

    // Inside the band by REARM_MARGIN_RPM on each enabled side. A band narrower than twice
    // the margin uses the plain band, otherwise re-arming would be impossible.
    hidden function insideWithMargin(cad, low, high) {
        var m = REARM_MARGIN_RPM;
        if (low > 0 && high > 0 && high - low < 2 * m) { m = 0; }
        if (low > 0 && cad < low + m) { return false; }
        if (high > 0 && cad > high - m) { return false; }
        return true;
    }
}

// Banner text, e.g. "CADANS LAAG 68" / "CADANS HOOG 112".
function cadenceLabel(dir, cad) {
    return (dir == CADENCE_HIGH ? "CADANS HOOG " : "CADANS LAAG ") + cad;
}
