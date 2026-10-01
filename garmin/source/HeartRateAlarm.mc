// Issue #228: heart-rate alarm ("hartslag-alarm"). Watch-only, no wire format change:
// Activity.Info.currentHeartRate in, alerts out. Two independent detectors:
//  - HrLimitAlarm: heart rate above the "hrAlarmBpm" app setting for a sustained stretch.
//  - HrIrregularDetector: repeated sudden jumps between consecutive samples
//    ("opvallend onregelmatig"), opt-in via "hrIrregularAlarm".
//
// Pure classes only (no Activity/Attention access, time passed in as ms) so they are
// unit-testable -- see garmin/test/HeartRateAlarmTest.mc. ClimbProView feeds them each tick.
// This is a training/safety nudge, not a medical device: an optical wrist sensor produces
// spikes of its own, which is why both detectors need a sustained or repeated signal.

class HrLimitAlarm {
    const SUSTAIN_MS = 10000;        // above the limit this long before alerting
    const REARM_MARGIN_BPM = 5;      // must drop this far below the limit to re-arm
    const REMIND_MS = 5 * 60 * 1000; // reminder while it stays high

    var high = false;
    hidden var aboveSinceMs = -1;
    hidden var lastAlertMs = 0;

    function initialize() {
    }

    // hr: bpm or null; limit: setting (<= 0 = off); nowMs: monotonic ms.
    // Returns true when an alert should fire now. Off clears the state; a missing sample
    // (sensor dropout) only breaks a pending sustain window, it doesn't end a high state.
    function update(hr, limit, nowMs) {
        if (limit == null || limit <= 0) {
            high = false;
            aboveSinceMs = -1;
            return false;
        }
        if (hr == null) {
            aboveSinceMs = -1;
            return false;
        }
        if (high) {
            if (hr < limit - REARM_MARGIN_BPM) {
                high = false;
                aboveSinceMs = -1;
                return false;
            }
            if (nowMs - lastAlertMs >= REMIND_MS) {
                lastAlertMs = nowMs;
                return true;
            }
            return false;
        }
        if (hr <= limit) {
            aboveSinceMs = -1;
            return false;
        }
        if (aboveSinceMs < 0) {
            aboveSinceMs = nowMs;
        }
        if (nowMs - aboveSinceMs >= SUSTAIN_MS) {
            high = true;
            lastAlertMs = nowMs;
            return true;
        }
        return false;
    }
}

class HrIrregularDetector {
    const JUMP_BPM = 25;             // sample-to-sample change counted as a jump
    const MAX_GAP_MS = 3000;         // samples further apart are not compared
    const WINDOW_MS = 60000;         // jumps are counted over this window
    const JUMPS_TO_ALERT = 3;
    const COOLDOWN_MS = 10 * 60 * 1000;

    hidden var prevHr = null;
    hidden var prevMs = 0;
    hidden var jumps = [];           // timestamps (ms) of recent jumps
    hidden var lastAlertMs = -1;

    function initialize() {
    }

    // Returns true when an "irregular" alert should fire now.
    function update(hr, nowMs) {
        if (hr == null || hr <= 0) {
            prevHr = null;
            return false;
        }
        if (prevHr != null && nowMs - prevMs <= MAX_GAP_MS) {
            var d = hr - prevHr;
            if (d < 0) { d = -d; }
            if (d >= JUMP_BPM) {
                jumps.add(nowMs);
            }
        }
        prevHr = hr;
        prevMs = nowMs;

        // Drop jumps that fell out of the window.
        while (jumps.size() > 0 && nowMs - jumps[0] > WINDOW_MS) {
            jumps = jumps.slice(1, null);
        }
        if (jumps.size() >= JUMPS_TO_ALERT
                && (lastAlertMs < 0 || nowMs - lastAlertMs >= COOLDOWN_MS)) {
            lastAlertMs = nowMs;
            jumps = [];
            return true;
        }
        return false;
    }
}

// Banner texts.
function hrHighLabel(hr) {
    return "HARTSLAG " + hr;
}

function hrIrregularLabel() {
    return "HARTSLAG ONREGELMATIG";
}
