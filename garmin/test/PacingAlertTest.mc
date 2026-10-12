using Toybox.Test;

// Issue #6: pacing alert (too fast against plan/PR) and issue #8: summit detection.

(:test)
function pacing_firesOnceWhenClearlyAhead(logger) {
    var p = new PacingAlert();
    // Reference 120 s, actual 100 s: 20 s ahead >= max(15, 12) -> buzz.
    Test.assertEqual(p.update(0, 100, 120, 500, true, 1000), true);
    Test.assertEqual(p.shownAheadSec, 20);
    // Still ahead next tick: latched, banner stays, no new buzz.
    Test.assertEqual(p.update(0, 101, 122, 510, true, 2000), false);
    Test.assertEqual(p.shownAheadSec, 21);
    return true;
}

(:test)
function pacing_quietBelowThreshold(logger) {
    var p = new PacingAlert();
    // 10 s ahead on a 200 s reference: threshold is 20 s.
    Test.assertEqual(p.update(0, 190, 200, 800, true, 1000), false);
    Test.assert(p.shownAheadSec == null);
    return true;
}

(:test)
function pacing_quietAtClimbFootAndWithoutReference(logger) {
    var p = new PacingAlert();
    Test.assertEqual(p.update(0, 10, 60, 50, true, 1000), false);    // first 100 m
    Test.assertEqual(p.update(0, 10, -1, 500, true, 1000), false);   // no tsec/refsec
    Test.assertEqual(p.update(0, 10, 60, 500, false, 1000), false);  // setting off
    Test.assertEqual(p.update(-1, 10, 60, 500, true, 1000), false);  // not on a climb
    return true;
}

(:test)
function pacing_rearmsAfterBackOnPaceAndCooldown(logger) {
    var p = new PacingAlert();
    Test.assertEqual(p.update(0, 100, 120, 500, true, 0), true);
    // Back within 5 s of the reference: re-armed, banner gone.
    Test.assertEqual(p.update(0, 118, 121, 520, true, 30000), false);
    Test.assert(p.shownAheadSec == null);
    // Too fast again within the 2 min cooldown: no buzz.
    Test.assertEqual(p.update(0, 130, 160, 700, true, 60000), false);
    // After the cooldown: buzz.
    Test.assertEqual(p.update(0, 140, 170, 800, true, 130000), true);
    return true;
}

(:test)
function pacing_jitterAroundThresholdDoesNotRebuzz(logger) {
    var p = new PacingAlert();
    Test.assertEqual(p.update(0, 100, 116, 500, true, 0), true);     // 16 s ahead
    Test.assertEqual(p.update(0, 102, 116, 510, true, 1000), false); // 14 s: in between
    Test.assertEqual(p.shownAheadSec, 14);
    Test.assertEqual(p.update(0, 102, 118, 520, true, 200000), false); // 16 s again: latched
    return true;
}

(:test)
function pacing_newClimbStartsFresh(logger) {
    var p = new PacingAlert();
    Test.assertEqual(p.update(0, 100, 120, 500, true, 0), true);
    // Next climb, but within the cooldown: still quiet.
    Test.assertEqual(p.update(1, 100, 120, 500, true, 10000), false);
    Test.assertEqual(p.update(1, 100, 120, 500, true, 200000), true);
    return true;
}

(:test)
function pacing_label(logger) {
    Test.assertEqual(pacingAlertLabel(20), "Rustig: 20s te snel");
    return true;
}

(:test)
function summit_reachedNearTopOnly(logger) {
    Test.assertEqual(summitReached(1950, 2000), true);
    Test.assertEqual(summitReached(1900, 2000), true);
    Test.assertEqual(summitReached(1899, 2000), false);
    Test.assertEqual(summitReached(500, 0), false);
    return true;
}
