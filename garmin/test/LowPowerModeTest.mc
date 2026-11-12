using Toybox.Test;

// Issue #50: low power mode -- threshold, hysteresis, off/null handling.

(:test)
function lowPower_activatesAtThreshold(logger) {
    var m = new LowPowerMode();
    Test.assert(!m.update(30, 15));
    Test.assert(!m.update(16, 15));
    Test.assert(m.update(15, 15));
    return true;
}

(:test)
function lowPower_hysteresisKeepsItOn(logger) {
    var m = new LowPowerMode();
    m.update(10, 15);
    Test.assert(m.update(17, 15));    // still < threshold + 3
    Test.assert(!m.update(18, 15));   // threshold + 3 releases it
    Test.assert(!m.update(16, 15));   // and 16 doesn't re-trigger
    return true;
}

(:test)
function lowPower_offOrUnknown(logger) {
    var m = new LowPowerMode();
    Test.assert(!m.update(5, 0));
    Test.assert(!m.update(null, 15));
    Test.assert(!m.update(5, null));
    m.update(5, 15);
    Test.assert(!m.update(5, 0));     // switching the setting off clears it
    return true;
}
