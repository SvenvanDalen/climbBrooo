using Toybox.Test;

// Issue #179: cadence coach. Covers the 30 s sustain, coasting/no-sensor pausing (not
// "too low"), in-band reset, the high side, re-arm only after 20 s back inside the band
// (with margin), the 2-min cooldown, one-sided bands, off/invalid settings and labels.

// Feeds `cad` once a second from t0 for `secs` seconds (inclusive) with band 80-100;
// returns the last non-OK result, or CADENCE_OK when nothing fired.
function cadFeed(c, cad, t0, secs) {
    var fired = CADENCE_OK;
    for (var i = 0; i <= secs; i++) {
        var r = c.update(cad, 80, 100, true, t0 + i * 1000);
        if (r != CADENCE_OK) { fired = r; }
    }
    return fired;
}

// Counts nudges over the same feed.
function cadCount(c, cad, t0, secs) {
    var n = 0;
    for (var i = 0; i <= secs; i++) {
        if (c.update(cad, 80, 100, true, t0 + i * 1000) != CADENCE_OK) { n++; }
    }
    return n;
}

(:test)
function cadence_lowFiresAfterThirtySeconds(logger) {
    var c = new CadenceCoach();
    Test.assert(cadFeed(c, 70, 0, 29) == CADENCE_OK);
    Test.assert(c.update(70, 80, 100, true, 30000) == CADENCE_LOW);
    Test.assert(c.shownDir == CADENCE_LOW);
    return true;
}

(:test)
function cadence_highFiresAfterThirtySeconds(logger) {
    var c = new CadenceCoach();
    Test.assert(cadFeed(c, 110, 0, 30) == CADENCE_HIGH);
    Test.assert(c.shownDir == CADENCE_HIGH);
    return true;
}

(:test)
function cadence_bandEdgesAreInside(logger) {
    var c = new CadenceCoach();
    Test.assert(cadCount(c, 80, 0, 60) == 0);
    Test.assert(cadCount(c, 100, 61000, 60) == 0);
    return true;
}

(:test)
function cadence_coastingIsNotTooLow(logger) {
    var c = new CadenceCoach();
    Test.assert(cadCount(c, 0, 0, 120) == 0);
    Test.assert(cadCount(c, null, 121000, 120) == 0);
    Test.assert(c.shownDir == CADENCE_OK);
    return true;
}

(:test)
function cadence_coastingPausesWithoutReset(logger) {
    var c = new CadenceCoach();
    cadFeed(c, 70, 0, 20);                         // 20 s low
    cadFeed(c, 0, 21000, 30);                      // 31 s freewheeling: not counted
    Test.assert(c.update(70, 80, 100, true, 52000) == CADENCE_OK);   // restarts dt reference
    Test.assert(cadFeed(c, 70, 53000, 8) == CADENCE_OK);           // 20 + 9 = 29 s
    Test.assert(c.update(70, 80, 100, true, 62000) == CADENCE_LOW); // 30 s pedalled low
    return true;
}

(:test)
function cadence_inBandResetsSustain(logger) {
    var c = new CadenceCoach();
    cadFeed(c, 70, 0, 25);
    c.update(90, 80, 100, true, 26000);
    Test.assert(cadFeed(c, 70, 27000, 29) == CADENCE_OK);
    Test.assert(c.update(70, 80, 100, true, 57000) == CADENCE_LOW);
    return true;
}

(:test)
function cadence_noRepeatWhileStillOut(logger) {
    var c = new CadenceCoach();
    Test.assert(cadCount(c, 70, 0, 600) == 1);
    Test.assert(c.shownDir == CADENCE_LOW);
    return true;
}

(:test)
function cadence_rearmNeedsTwentySecondsInsideMargin(logger) {
    var c = new CadenceCoach();
    cadFeed(c, 70, 0, 30);                          // nudge at 30 s
    // 81 rpm is in band but within the 3 rpm margin: never re-arms.
    cadFeed(c, 81, 31000, 60);
    Test.assert(c.shownDir == CADENCE_OK);
    Test.assert(cadCount(c, 70, 92000, 60) == 0);   // still latched low
    // 19 s properly inside, then low again: still latched.
    cadFeed(c, 90, 153000, 18);
    Test.assert(cadCount(c, 70, 172000, 60) == 0);
    // 20 s inside re-arms; the next sustained low stretch nudges again.
    cadFeed(c, 90, 233000, 20);
    Test.assert(cadFeed(c, 70, 254000, 30) == CADENCE_LOW);
    return true;
}

(:test)
function cadence_cooldownTwoMinutes(logger) {
    var c = new CadenceCoach();
    Test.assert(c.update(70, 80, 100, true, 0) == CADENCE_OK);
    cadFeed(c, 70, 1000, 29);                       // nudge at 30 s
    cadFeed(c, 90, 31000, 20);                      // re-armed at 51 s
    // Low again from 52 s: sustain met at 82 s but cooldown runs until 150 s.
    Test.assert(cadCount(c, 70, 52000, 97) == 0);   // up to 149 s
    Test.assert(c.update(70, 80, 100, true, 150000) == CADENCE_LOW);
    return true;
}

(:test)
function cadence_otherDirectionAfterCooldown(logger) {
    var c = new CadenceCoach();
    cadFeed(c, 70, 0, 30);                          // low nudge at 30 s
    Test.assert(cadCount(c, 110, 31000, 118) == 0); // high sustained, cooldown until 150 s
    Test.assert(c.update(110, 80, 100, true, 150000) == CADENCE_HIGH);
    return true;
}

(:test)
function cadence_oneSidedBand(logger) {
    var c = new CadenceCoach();
    var fired = false;
    for (var i = 0; i <= 60; i++) {
        if (c.update(130, 85, 0, true, i * 1000) != CADENCE_OK) { fired = true; }
    }
    Test.assert(!fired);                            // high side off
    var lowFired = CADENCE_OK;
    for (var j = 0; j <= 30; j++) {
        var r = c.update(60, 85, 0, true, 61000 + j * 1000);
        if (r != CADENCE_OK) { lowFired = r; }
    }
    Test.assert(lowFired == CADENCE_LOW);
    return true;
}

(:test)
function cadence_offAndInvalidBandNeverFire(logger) {
    var c = new CadenceCoach();
    var fired = false;
    for (var i = 0; i <= 60; i++) {
        if (c.update(50, 80, 100, false, i * 1000) != CADENCE_OK) { fired = true; }
        if (c.update(50, 0, 0, true, i * 1000) != CADENCE_OK) { fired = true; }
        if (c.update(50, 100, 80, true, i * 1000) != CADENCE_OK) { fired = true; }
        if (c.update(50, null, 100, true, i * 1000) != CADENCE_OK) { fired = true; }
    }
    Test.assert(!fired);
    Test.assert(c.shownDir == CADENCE_OK);
    return true;
}

(:test)
function cadence_switchingOffClearsLatch(logger) {
    var c = new CadenceCoach();
    cadFeed(c, 70, 0, 30);
    Test.assert(c.shownDir == CADENCE_LOW);
    c.update(70, 80, 100, false, 31000);
    Test.assert(c.shownDir == CADENCE_OK);
    // Re-enabled: the latch is gone, only the cooldown (until 150 s) still applies.
    Test.assert(cadCount(c, 70, 32000, 117) == 0);
    Test.assert(c.update(70, 80, 100, true, 150000) == CADENCE_LOW);
    return true;
}

(:test)
function cadence_labels(logger) {
    Test.assert(cadenceLabel(CADENCE_LOW, 68).equals("CADANS LAAG 68"));
    Test.assert(cadenceLabel(CADENCE_HIGH, 112).equals("CADANS HOOG 112"));
    return true;
}
