// Issue #184: eat/drink reminder ("eet- en drinkherinnering") during the ride. Watch-only,
// no wire format change: the activity timer, total ascent and air temperature come in, a
// reminder comes out. Interval, climb trigger and temperature correction are Connect IQ app
// settings (set from the phone in Garmin Connect Mobile), like the heat-index threshold.
//
//  - every `fuelIntervalMin` minutes of activity time (timerTime, so pauses don't count);
//  - and/or every `fuelClimbM` metres of total ascent (climbing burns more);
//  - whichever comes first; both counters restart at every reminder.
//  - Hot weather shortens both: from `fuelHotC` °C to 75 %, from fuelHotC + 8 °C to 50 %.
//
// Pure class (no Activity/Properties/Attention access) so it is unit-testable -- see
// garmin/test/FuelReminderTest.mc. ClimbProView feeds it each tick.

const FUEL_MIN_GAP_MS = 10 * 60 * 1000;   // never two reminders closer than this
const FUEL_VERY_HOT_DELTA_C = 8;          // fuelHotC + this -> half interval

// Percentage the interval/ascent step is scaled to at tempC: 100 normally, 75 from hotC,
// 50 from hotC + FUEL_VERY_HOT_DELTA_C. hotC <= 0 (off) or an unknown temperature -> 100.
function fuelHeatFactorPct(tempC, hotC) {
    if (tempC == null || hotC == null || hotC <= 0) { return 100; }
    if (tempC >= hotC + FUEL_VERY_HOT_DELTA_C) { return 50; }
    if (tempC >= hotC) { return 75; }
    return 100;
}

// Effective time interval in ms (0 = time trigger off). A shortened interval never drops
// below FUEL_MIN_GAP_MS (unless the setting itself is shorter).
function fuelIntervalMs(intervalMin, tempC, hotC) {
    if (intervalMin == null || intervalMin <= 0) { return 0; }
    var base = intervalMin * 60000;
    var ms = base * fuelHeatFactorPct(tempC, hotC) / 100;
    var floor = (base < FUEL_MIN_GAP_MS) ? base : FUEL_MIN_GAP_MS;
    return (ms < floor) ? floor : ms;
}

// Effective ascent step in metres (0 = ascent trigger off).
function fuelClimbStepM(climbM, tempC, hotC) {
    if (climbM == null || climbM <= 0) { return 0; }
    return climbM * fuelHeatFactorPct(tempC, hotC) / 100;
}

class FuelReminder {
    var count = 0;                 // reminders given this ride
    hidden var baseTimerMs = -1;   // activity timer at the last reminder (or ride start)
    hidden var baseAscentM = null; // total ascent at the last reminder (or first reading)

    function initialize() {
    }

    function reset() {
        count = 0;
        baseTimerMs = -1;
        baseAscentM = null;
    }

    // timerMs: Activity.Info.timerTime (ms); ascentM: totalAscent (m) or null; tempC: air
    // temperature or null; intervalMin / climbM / hotC: the settings (<= 0 = off).
    // Returns true exactly once per trigger: the baselines move to "now" when it fires, so
    // GPS/altitude jitter can't re-fire it, and a new activity (timer back to ~0) restarts.
    function update(timerMs, ascentM, tempC, intervalMin, climbM, hotC) {
        if (timerMs == null) { return false; }
        if (baseTimerMs < 0 || timerMs < baseTimerMs) {
            // First tick, or the timer went back (new activity): restart the ride.
            reset();
            baseTimerMs = timerMs;
            baseAscentM = ascentM;
            return false;
        }
        if (ascentM != null && (baseAscentM == null || ascentM < baseAscentM)) {
            baseAscentM = ascentM;
        }
        var intervalMs = fuelIntervalMs(intervalMin, tempC, hotC);
        var stepM = fuelClimbStepM(climbM, tempC, hotC);
        if (intervalMs <= 0 && stepM <= 0) {
            // Off: keep the baselines current so switching it on mid-ride starts fresh.
            baseTimerMs = timerMs;
            baseAscentM = ascentM;
            return false;
        }
        var sinceMs = timerMs - baseTimerMs;
        var due = intervalMs > 0 && sinceMs >= intervalMs;
        if (!due && stepM > 0 && ascentM != null && baseAscentM != null) {
            due = (ascentM - baseAscentM) >= stepM && sinceMs >= FUEL_MIN_GAP_MS;
        }
        if (!due) { return false; }
        baseTimerMs = timerMs;
        baseAscentM = ascentM;
        count++;
        return true;
    }
}

// Banner text.
function fuelReminderLabel() {
    return "ETEN & DRINKEN";
}
