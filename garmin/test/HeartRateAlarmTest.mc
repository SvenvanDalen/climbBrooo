using Toybox.Test;

// Issue #228: heart-rate alarm. Covers the limit alarm (10 s sustain, dropout breaks the
// sustain window, re-arm margin, 5-min reminder, off) and the irregular-jump detector
// (3 jumps of >= 25 bpm within 60 s, sample gap, window expiry, 10-min cooldown).

// Feeds `hr` once a second from t0 for `secs` seconds; returns how many alerts fired.
function hrFeedLimit(a, hr, limit, t0, secs) {
    var fired = 0;
    for (var i = 0; i <= secs; i++) {
        if (a.update(hr, limit, t0 + i * 1000)) { fired++; }
    }
    return fired;
}

// =========================== limit alarm =====================================

(:test)
function hrLimit_firesAfterTenSecondsAbove(logger) {
    var a = new HrLimitAlarm();
    Test.assert(!a.update(181, 180, 0));
    Test.assert(!a.update(182, 180, 9000));
    Test.assert(a.update(182, 180, 10000));
    Test.assert(a.high);
    return true;
}

(:test)
function hrLimit_atLimitDoesNotCount(logger) {
    var a = new HrLimitAlarm();
    Test.assert(hrFeedLimit(a, 180, 180, 0, 30) == 0);
    return true;
}

(:test)
function hrLimit_shortSpikeDoesNotFire(logger) {
    var a = new HrLimitAlarm();
    Test.assert(hrFeedLimit(a, 190, 180, 0, 8) == 0);
    Test.assert(!a.update(170, 180, 9000));
    Test.assert(!a.update(190, 180, 10000));
    Test.assert(!a.high);
    return true;
}

(:test)
function hrLimit_dropoutBreaksSustainWindow(logger) {
    var a = new HrLimitAlarm();
    a.update(190, 180, 0);
    a.update(null, 180, 5000);
    Test.assert(!a.update(190, 180, 10000));
    Test.assert(a.update(190, 180, 20000));
    return true;
}

(:test)
function hrLimit_rearmNeedsFiveBelow(logger) {
    var a = new HrLimitAlarm();
    hrFeedLimit(a, 190, 180, 0, 10);
    Test.assert(a.high);
    Test.assert(!a.update(176, 180, 20000));
    Test.assert(a.high);
    Test.assert(!a.update(174, 180, 21000));
    Test.assert(!a.high);
    return true;
}

(:test)
function hrLimit_remindsEveryFiveMinutes(logger) {
    var a = new HrLimitAlarm();
    Test.assert(hrFeedLimit(a, 190, 180, 0, 10) == 1);
    Test.assert(!a.update(190, 180, 10000 + 4 * 60000));
    Test.assert(a.update(190, 180, 10000 + 5 * 60000));
    return true;
}

(:test)
function hrLimit_offClearsState(logger) {
    var a = new HrLimitAlarm();
    hrFeedLimit(a, 190, 180, 0, 10);
    Test.assert(!a.update(190, 0, 11000));
    Test.assert(!a.high);
    return true;
}

// =========================== irregular detector ==============================

(:test)
function hrIrregular_threeJumpsFire(logger) {
    var d = new HrIrregularDetector();
    Test.assert(!d.update(120, 0));
    Test.assert(!d.update(150, 1000));   // jump 1
    Test.assert(!d.update(120, 2000));   // jump 2
    Test.assert(d.update(160, 3000));    // jump 3
    return true;
}

(:test)
function hrIrregular_smallChangesDoNotCount(logger) {
    var d = new HrIrregularDetector();
    var hr = [120, 140, 120, 140, 120, 140];
    var fired = false;
    for (var i = 0; i < hr.size(); i++) {
        if (d.update(hr[i], i * 1000)) { fired = true; }
    }
    Test.assert(!fired);
    return true;
}

(:test)
function hrIrregular_largeGapIsNotAJump(logger) {
    var d = new HrIrregularDetector();
    d.update(100, 0);
    d.update(150, 5000);    // 5 s gap: not compared
    d.update(100, 6000);    // jump 1
    Test.assert(!d.update(140, 7000));   // jump 2 only
    return true;
}

(:test)
function hrIrregular_jumpsExpireAfterWindow(logger) {
    var d = new HrIrregularDetector();
    d.update(120, 0);
    d.update(150, 1000);     // jump at 1 s
    d.update(120, 2000);     // jump at 2 s
    d.update(121, 70000);    // gap, no jump
    Test.assert(!d.update(150, 71000));  // older jumps expired -> only 1 in window
    return true;
}

(:test)
function hrIrregular_cooldownTenMinutes(logger) {
    var d = new HrIrregularDetector();
    d.update(120, 0);
    d.update(150, 1000);
    d.update(120, 2000);
    Test.assert(d.update(160, 3000));
    d.update(120, 4000);
    d.update(160, 5000);
    Test.assert(!d.update(120, 6000));
    var t = 3000 + 10 * 60000;
    d.update(120, t);
    d.update(150, t + 1000);
    d.update(120, t + 2000);
    Test.assert(d.update(160, t + 3000));
    return true;
}

(:test)
function hr_labels(logger) {
    Test.assert(hrHighLabel(185).equals("HARTSLAG 185"));
    Test.assert(hrIrregularLabel().equals("HARTSLAG ONREGELMATIG"));
    return true;
}
