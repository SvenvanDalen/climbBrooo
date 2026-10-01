using Toybox.Test;
using Toybox.Graphics as Gfx;
using Toybox.Activity as Activity;

// Issue #213: "vlakker stuk" notice during a climb. Covers the stretch finder (drop and
// length thresholds, end-of-climb), the label rounding, and the tracker's lookahead,
// once-per-stretch latch, jitter safety, off-route/disabled gating and reset.

// 10 segments of 100 m: 8 % x3, then 3 % x3 (the easier stretch), then 8 % x4.
function eaDist() { return [100, 100, 100, 100, 100, 100, 100, 100, 100, 100]; }
function eaGrad() { return [80, 80, 80, 30, 30, 30, 80, 80, 80, 80]; }

// =========================== finder ==========================================

(:test)
function easier_find_fromSegmentBeforeStretch(logger) {
    var r = findEasierStretch(eaDist(), eaGrad(), 10, 2, 30, 200);
    Test.assert(r != null);
    Test.assertEqual(r[0], 300);   // starts 300 m into the climb
    Test.assertEqual(r[1], 300);   // three 100 m segments
    Test.assertEqual(r[2], 3);
    return true;
}

(:test)
function easier_find_notAdjacent_returnsNull(logger) {
    // From segment 1 the next segment is still 8 %: nothing directly ahead.
    Test.assert(findEasierStretch(eaDist(), eaGrad(), 10, 1, 30, 200) == null);
    return true;
}

(:test)
function easier_find_tooShort_returnsNull(logger) {
    var g = [80, 80, 30, 80, 80];
    Test.assert(findEasierStretch([100, 100, 100, 100, 100], g, 5, 1, 30, 200) == null);
    return true;
}

(:test)
function easier_find_dropTooSmall_returnsNull(logger) {
    // 8 % -> 6 % is only 2 points easier.
    var g = [80, 80, 60, 60, 60];
    Test.assert(findEasierStretch([100, 100, 100, 100, 100], g, 5, 1, 30, 200) == null);
    return true;
}

(:test)
function easier_find_exactDrop_counts(logger) {
    var g = [80, 80, 50, 50, 80];
    var r = findEasierStretch([100, 100, 100, 100, 100], g, 5, 1, 30, 200);
    Test.assert(r != null);
    Test.assertEqual(r[1], 200);
    return true;
}

(:test)
function easier_find_lastSegment_returnsNull(logger) {
    Test.assert(findEasierStretch(eaDist(), eaGrad(), 10, 9, 30, 200) == null);
    return true;
}

(:test)
function easier_find_runsToClimbEnd(logger) {
    var g = [90, 90, 20, 20, 20];
    var r = findEasierStretch([100, 100, 100, 100, 100], g, 5, 1, 30, 200);
    Test.assertEqual(r[1], 300);
    return true;
}

// =========================== label ===========================================

(:test)
function easier_label_roundsTo50m(logger) {
    Test.assertEqual(easierAheadLabel(300), "300 m vlakker");
    Test.assertEqual(easierAheadLabel(324), "300 m vlakker");
    Test.assertEqual(easierAheadLabel(326), "350 m vlakker");
    Test.assertEqual(easierAheadLabel(10), "50 m vlakker");
    return true;
}

(:test)
function easier_label_kilometres(logger) {
    Test.assertEqual(easierAheadLabel(1234), "1.2 km vlakker");
    Test.assertEqual(easierAheadLabel(1960), "2.0 km vlakker");
    return true;
}

// =========================== tracker =========================================

function eaTick(t, seg, progress) {
    return t.update(0, seg, progress, eaDist(), eaGrad(), 10, false, true);
}

(:test)
function easier_tracker_quietBeforeLookahead(logger) {
    var t = new EasierAheadTracker();
    Test.assertEqual(eaTick(t, 2, 140), false);   // 160 m before the stretch
    Test.assert(t.shownLenM == null);
    return true;
}

(:test)
function easier_tracker_firesOnceInLookahead(logger) {
    var t = new EasierAheadTracker();
    Test.assertEqual(eaTick(t, 2, 160), true);    // 140 m before
    Test.assertEqual(t.shownLenM, 300);
    Test.assertEqual(eaTick(t, 2, 200), false);   // still shown, no re-fire
    Test.assertEqual(t.shownLenM, 300);
    return true;
}

(:test)
function easier_tracker_jitterBackKeepsBannerNoRefire(logger) {
    var t = new EasierAheadTracker();
    eaTick(t, 2, 160);
    Test.assertEqual(eaTick(t, 2, 140), false);   // GPS drifts back past the lookahead
    Test.assertEqual(t.shownLenM, 300);
    Test.assertEqual(eaTick(t, 2, 170), false);
    return true;
}

(:test)
function easier_tracker_hidesOnEnteringStretch(logger) {
    var t = new EasierAheadTracker();
    eaTick(t, 2, 160);
    Test.assertEqual(eaTick(t, 3, 320), false);
    Test.assert(t.shownLenM == null);
    // Jitter back into the steep segment: banner returns, but no second buzz.
    Test.assertEqual(eaTick(t, 2, 295), false);
    Test.assertEqual(t.shownLenM, 300);
    return true;
}

(:test)
function easier_tracker_quietInStartAlertWindow(logger) {
    // Stretch starting 100 m into the climb: the rider is in the lookahead from the start,
    // but the climb-start alert owns the first 50 m.
    var d = [100, 100, 100, 100];
    var g = [90, 20, 20, 90];
    var t = new EasierAheadTracker();
    Test.assertEqual(t.update(0, 0, 30, d, g, 4, false, true), false);
    Test.assertEqual(t.update(0, 0, 60, d, g, 4, false, true), true);
    return true;
}

(:test)
function easier_tracker_offRouteSuppresses(logger) {
    var t = new EasierAheadTracker();
    Test.assertEqual(t.update(0, 2, 200, eaDist(), eaGrad(), 10, true, true), false);
    Test.assert(t.shownLenM == null);
    return true;
}

(:test)
function easier_tracker_disabledSuppresses(logger) {
    var t = new EasierAheadTracker();
    Test.assertEqual(t.update(0, 2, 200, eaDist(), eaGrad(), 10, false, false), false);
    Test.assert(t.shownLenM == null);
    return true;
}

(:test)
function easier_tracker_offClimbClears(logger) {
    var t = new EasierAheadTracker();
    eaTick(t, 2, 200);
    Test.assertEqual(t.update(-1, -1, 0, null, null, 0, false, true), false);
    Test.assert(t.shownLenM == null);
    return true;
}

(:test)
function easier_tracker_resetRearms(logger) {
    var t = new EasierAheadTracker();
    eaTick(t, 2, 200);
    t.reset();
    Test.assertEqual(eaTick(t, 2, 200), true);
    return true;
}

// End to end through ClimbProView.compute()/onUpdate() with the FakeInfo helper shared with
// ViewSmokeTest.mc: must not throw with the notice active.
(:test)
function easier_view_computeAndDraw_noThrow(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "easier", "name" => "Easier",
        "climbs" => [
            { "sd" => 1000, "ed" => 2000, "len" => 1000, "eg" => 60, "ag" => 60,
              "segs" => [300, 24, 80, 3, 300, 9, 30, 1, 400, 32, 80, 3] }
        ]
    });
    var v = new ClimbProView();
    v.compute(new FakeInfo(1200, 10000, null, null) as Activity.Info);
    Test.assertEqual(d.activeSegmentIndex, 0);
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    v.onUpdate(ref.get().getDc());
    return true;
}
