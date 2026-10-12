using Toybox.Lang;

// Issue #6: pacing alert per segment. Buzzes when the rider is clearly faster than the
// reference for the distance covered on this climb -- the classic "too hard at the foot"
// mistake. Watch-only, no wire format change: the reference is the per-segment pacing plan
// ("tsec") or, without one, the per-segment PR ("refsec"), both already in the payload.
//
// Pure class (no Attention/Properties access, time passed in) so it is unit-testable --
// see garmin/test/PacingAlertTest.mc. ClimbProView feeds it each tick.
//
// Anti-spam, like the climb-start alert:
//  - quiet for the first MIN_PROGRESS_M of a climb (start alert window, timer jitter);
//  - "clearly faster" = ahead by at least max(AHEAD_MIN_SEC, AHEAD_FRAC of the reference);
//  - one buzz latches; it re-arms only after the rider is back within REARM_SEC of the
//    reference, and never sooner than COOLDOWN_MS after the previous buzz;
//  - a new climb starts with a fresh latch.
class PacingAlert {
    const MIN_PROGRESS_M = 100;
    const AHEAD_MIN_SEC = 15;
    const AHEAD_FRAC = 0.10;
    const REARM_SEC = 5;
    const COOLDOWN_MS = 2 * 60 * 1000;

    // Seconds ahead to show in the banner while the rider is too fast; null = no banner.
    var shownAheadSec = null;

    hidden var climb = -1;
    hidden var latched = false;
    hidden var lastAlertMs = -1;

    function initialize() {
    }

    function reset() {
        shownAheadSec = null;
        climb = -1;
        latched = false;
        lastAlertMs = -1;
    }

    // ci: active climb (-1 = none); actualSec: time on this climb so far; refSec: reference
    // seconds for the same distance (< 0 = none); progressM: metres into the climb;
    // enabled: setting; nowMs: activity timer. Returns true when a buzz should fire now.
    function update(ci, actualSec, refSec, progressM, enabled, nowMs) {
        if (ci != climb) {
            climb = ci;
            latched = false;
            shownAheadSec = null;
        }
        if (!enabled || ci < 0 || refSec == null || refSec < 0 || progressM < MIN_PROGRESS_M) {
            shownAheadSec = null;
            return false;
        }
        var ahead = (refSec - actualSec).toNumber();
        var threshold = (refSec * AHEAD_FRAC).toNumber();
        if (threshold < AHEAD_MIN_SEC) { threshold = AHEAD_MIN_SEC; }

        if (ahead <= REARM_SEC) {
            latched = false;
            shownAheadSec = null;
            return false;
        }
        if (ahead < threshold) {
            // In between: keep showing an earlier warning, but don't start a new one.
            shownAheadSec = latched ? ahead : null;
            return false;
        }
        if (latched) {
            shownAheadSec = ahead;
            return false;
        }
        if (lastAlertMs >= 0 && nowMs - lastAlertMs < COOLDOWN_MS) {
            return false;
        }
        latched = true;
        lastAlertMs = nowMs;
        shownAheadSec = ahead;
        return true;
    }
}

// Banner text: "Rustig: 20s te snel".
function pacingAlertLabel(aheadSec) {
    return "Rustig: " + aheadSec + "s te snel";
}

// Issue #8: true when the rider left a climb at (or just before) its top, i.e. the climb
// was really finished rather than abandoned. The last progress reading is taken a tick
// before the climb ends, so SUMMIT_TOL_M of slack absorbs the gap between GPS ticks.
const SUMMIT_TOL_M = 100;
function summitReached(lastProgressM, climbLenM) {
    return climbLenM > 0 && lastProgressM >= climbLenM - SUMMIT_TOL_M;
}
