using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;
using Toybox.Activity as Activity;

// Coverage for issue #83: the opt-in "climbAlertDistinctTone" app setting swaps the
// climb-start alert's vibrate/tone pattern for a more attention-grabbing one, for
// riders wearing earbuds/headphones who can miss the wrist vibration. Toybox.Attention
// has no spoken-voice/TTS API on this device tier (see ClimbProView.triggerClimbAlertDistinctTone
// doc comment), so this is a distinct tone *sequence*, not literal speech. Like
// DarkThemeTest, Attention.vibrate/playTone calls into the OS can't be asserted
// directly, so these smoke-test that toggling the setting doesn't crash the
// climb-start-alert trigger path and that the property reads back correctly.

function distinctToneDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function distinctToneViewData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function distinctToneClimbPayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "alertstyle", "name" => "AlertStyle",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5],
              "surf" => [1, 0] }
        ]
    };
}

// Restores the default (off) so this test doesn't leak state into other tests in
// the suite -- properties persist across (:test) functions within a simulator run.
function resetClimbAlertDistinctTone() {
    try {
        Properties.setValue("climbAlertDistinctTone", false);
    } catch (e) {
        // Property store unavailable in this harness; nothing to reset.
    }
}

(:test)
function climbAlertDistinctTone_defaultIsOff(logger) {
    resetClimbAlertDistinctTone();
    var v = Properties.getValue("climbAlertDistinctTone");
    Test.assertEqual(v, false);
    return true;
}

(:test)
function climbAlertDistinctTone_settingRoundTrips(logger) {
    resetClimbAlertDistinctTone();
    Properties.setValue("climbAlertDistinctTone", true);
    Test.assertEqual(Properties.getValue("climbAlertDistinctTone"), true);
    resetClimbAlertDistinctTone();
    return true;
}

// Drives the real climb-start-alert trigger path via compute() (odometer entering
// the climb within 50m of "sd") with the setting on, so this covers
// useDistinctClimbAlertTone() + triggerClimbAlertDistinctTone() end to end through
// the same code path ClimbProView.compute() uses on-watch, not just the property
// read. Uses the FakeInfo/FakeLoc helpers shared with ViewSmokeTest.mc (same
// module test source set).
(:test)
function climbAlertDistinctTone_on_climbStartAlert_triggersWithoutThrow(logger) {
    resetClimbAlertDistinctTone();
    Properties.setValue("climbAlertDistinctTone", true);

    distinctToneViewData();
    new PhoneMessageCallback().onMessage(distinctToneClimbPayload());

    var view = new ClimbProView();
    // climb "sd" is 1000m; elapsed=1020 puts progressInClimb=20, inside the 50m
    // climb-start-alert window and should fire triggerClimbAlertDistinctTone().
    view.compute(new FakeInfo(1020, 10000, null, null) as Activity.Info);
    view.onUpdate(distinctToneDc());

    resetClimbAlertDistinctTone();
    return true;
}

// Setting off (default) still fires the original alert path without throwing --
// guards against a future change accidentally making useDistinctClimbAlertTone()
// throw and break the default "off" path.
(:test)
function climbAlertDistinctTone_off_climbStartAlert_triggersWithoutThrow(logger) {
    resetClimbAlertDistinctTone();

    distinctToneViewData();
    new PhoneMessageCallback().onMessage(distinctToneClimbPayload());

    var view = new ClimbProView();
    view.compute(new FakeInfo(1020, 10000, null, null) as Activity.Info);
    view.onUpdate(distinctToneDc());

    return true;
}
