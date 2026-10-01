using Toybox.Test;
using Toybox.Application as App;

// Issue #217: Everesting tracker. Covers the "ev" parser (valid + malformed + stale clear),
// summit counting with start re-arm (no double count while loitering at the top), the
// once-only target event, plan-change reset vs same-plan keep, and the banner text.

// Start at (51.5, 5.1), top at (51.52, 5.11) -- ~2.3 km apart.
function evPlan() {
    return [8848, 12, 51.5, 5.1, 51.52, 5.11];
}

(:test)
function ev_parse_valid(logger) {
    var p = new PhoneMessageCallback().parseEverest([8848, 111, 5150000, 510000, 5152000, 511000]);
    Test.assert(p != null);
    Test.assert(p[0] == 8848 && p[1] == 111);
    var dl = p[4] - 51.52;
    if (dl < 0) { dl = -dl; }
    Test.assert(dl < 0.0001);
    return true;
}

(:test)
function ev_parse_malformed_returnsNull(logger) {
    var cb = new PhoneMessageCallback();
    Test.assert(cb.parseEverest(null) == null);
    Test.assert(cb.parseEverest([8848, 111, 5150000, 510000, 5152000]) == null);
    Test.assert(cb.parseEverest([8848, 0, 5150000, 510000, 5152000, 511000]) == null);
    Test.assert(cb.parseEverest([8848, 111, "x", 510000, 5152000, 511000]) == null);
    Test.assert(cb.parseEverest([8848, 111, 5150000, 510000, 0, 0]) == null);
    return true;
}

(:test)
function ev_payloadWithoutEv_clearsStalePlan(logger) {
    var d = App.getApp().climbData;
    d.everest = evPlan();
    d.everestClimb = 0;
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "r", "climbs" => [
            { "sd" => 0, "ed" => 1000, "len" => 1000, "eg" => 50, "ag" => 50,
              "segs" => [500, 25, 50, 2, 500, 25, 50, 2] }
        ]
    });
    Test.assert(d.everest == null);
    Test.assert(d.everestClimb == -1);
    return true;
}

(:test)
function ev_countsSummitPasses_withStartRearm(logger) {
    var t = new EverestTracker();
    t.setPlan(evPlan());
    Test.assert(t.update(51.5, 5.1, 0) == EVEREST_NONE);
    Test.assert(t.update(51.52, 5.11, 200) == EVEREST_REPEAT);
    Test.assert(t.repeats == 1);
    // Loitering at the top does not count again.
    Test.assert(t.update(51.5201, 5.1101, 201) == EVEREST_NONE);
    Test.assert(t.update(51.52, 5.11, 202) == EVEREST_NONE);
    // Back down to the start re-arms, next summit counts.
    t.update(51.5003, 5.1, 205);
    Test.assert(t.update(51.52, 5.11, 400) == EVEREST_REPEAT);
    Test.assert(t.repeats == 2);
    return true;
}

(:test)
function ev_midClimbDoesNotRearm(logger) {
    var t = new EverestTracker();
    t.setPlan(evPlan());
    t.update(51.52, 5.11, 200);
    t.update(51.51, 5.105, 250);   // halfway down, turns around
    Test.assert(t.update(51.52, 5.11, 300) == EVEREST_NONE);
    Test.assert(t.repeats == 1);
    return true;
}

(:test)
function ev_targetReached_firesOnce(logger) {
    var t = new EverestTracker();
    t.setPlan(evPlan());
    Test.assert(t.update(51.51, 5.105, 8847) == EVEREST_NONE);
    Test.assert(t.update(51.51, 5.105, 8848) == EVEREST_DONE);
    Test.assert(t.done);
    Test.assert(t.update(51.51, 5.105, 8900) == EVEREST_NONE);
    return true;
}

(:test)
function ev_nullInputs_noEvent(logger) {
    var t = new EverestTracker();
    t.setPlan(evPlan());
    Test.assert(t.update(null, null, null) == EVEREST_NONE);
    var idle = new EverestTracker();
    Test.assert(idle.update(51.52, 5.11, 9000) == EVEREST_NONE);
    Test.assert(!idle.active());
    return true;
}

(:test)
function ev_samePlanKeepsCount_newPlanResets(logger) {
    var t = new EverestTracker();
    t.setPlan(evPlan());
    t.update(51.52, 5.11, 200);
    t.setPlan(evPlan());
    Test.assert(t.repeats == 1);
    t.setPlan([4424, 6, 51.5, 5.1, 51.52, 5.11]);
    Test.assert(t.repeats == 0);
    t.setPlan(null);
    Test.assert(!t.active());
    return true;
}

(:test)
function ev_label(logger) {
    Test.assert(everestLabel(3, 12, 2650.4, 8848).equals("EVEREST 3/12  2650/8848m"));
    Test.assert(everestLabel(12, 12, 8850, 8848).equals("EVEREST GEHAALD 8850m"));
    Test.assert(everestLabel(0, 12, null, 8848).equals("EVEREST 0/12  0/8848m"));
    return true;
}
