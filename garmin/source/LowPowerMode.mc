// Issue #50: low power mode. When the watch battery drops to `lowPowerPct` the datafield
// stops drawing the (expensive) elevation profile and shows only the stat rows. Watch-only,
// no wire format change. Hysteresis: it switches back off only LOW_POWER_HYSTERESIS_PCT above
// the threshold, so a battery reading flickering around the limit can't toggle the layout.
//
// Pure class so it is unit-testable -- see garmin/test/LowPowerModeTest.mc.

const LOW_POWER_HYSTERESIS_PCT = 3;

class LowPowerMode {
    var active = false;

    function initialize() {
    }

    // batteryPct: Sys.getSystemStats().battery (0..100) or null; thresholdPct <= 0 = off.
    // Returns the (possibly updated) active state.
    function update(batteryPct, thresholdPct) {
        if (batteryPct == null || thresholdPct == null || thresholdPct <= 0) {
            active = false;
            return false;
        }
        if (active) {
            if (batteryPct >= thresholdPct + LOW_POWER_HYSTERESIS_PCT) { active = false; }
        } else if (batteryPct <= thresholdPct) {
            active = true;
        }
        return active;
    }
}
