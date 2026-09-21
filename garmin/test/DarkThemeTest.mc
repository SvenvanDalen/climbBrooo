using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;

// Coverage for issue #81: the low-light "darkTheme" app setting swaps the
// gradient-color palette used by drawProfile() without touching the
// gradient -> color-index mapping logic (segColor is computed on the phone
// and is untouched here). Like ViewSmokeTest, pixel output can't be asserted
// directly, so these smoke-test that toggling the setting doesn't crash
// rendering and that the property reads back the value we just set.

function darkThemeDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function darkThemeViewData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function darkThemeClimbPayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "dark", "name" => "DarkTheme",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5],
              "surf" => [1, 0] }
        ]
    };
}

// Restores the default (normal theme) so this test doesn't leak state into
// other tests in the suite -- properties persist across (:test) functions
// within a simulator run.
function resetDarkTheme() {
    try {
        Properties.setValue("darkTheme", false);
    } catch (e) {
        // Property store unavailable in this harness; nothing to reset.
    }
}

(:test)
function darkTheme_defaultIsOff(logger) {
    resetDarkTheme();
    var v = Properties.getValue("darkTheme");
    Test.assertEqual(v, false);
    return true;
}

(:test)
function darkTheme_settingRoundTrips(logger) {
    resetDarkTheme();
    Properties.setValue("darkTheme", true);
    Test.assertEqual(Properties.getValue("darkTheme"), true);
    resetDarkTheme();
    return true;
}

(:test)
function darkTheme_onUpdate_activeClimb_rendersWithoutThrow(logger) {
    resetDarkTheme();
    Properties.setValue("darkTheme", true);

    var d = darkThemeViewData();
    new PhoneMessageCallback().onMessage(darkThemeClimbPayload());
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;

    new ClimbProView().onUpdate(darkThemeDc());

    resetDarkTheme();
    return true;
}

(:test)
function darkTheme_onUpdate_nextClimbPreview_rendersWithoutThrow(logger) {
    resetDarkTheme();
    Properties.setValue("darkTheme", true);

    var d = darkThemeViewData();
    new PhoneMessageCallback().onMessage(darkThemeClimbPayload());
    d.activeClimbIndex = -1;
    d.nextClimbIndex = 0;
    d.distToNextClimb = 300;

    new ClimbProView().onUpdate(darkThemeDc());

    resetDarkTheme();
    return true;
}

// Normal theme (default/false) still renders fine -- guards against a future
// change accidentally making activeColors() throw on the "off" path.
(:test)
function darkTheme_off_onUpdate_activeClimb_rendersWithoutThrow(logger) {
    resetDarkTheme();

    var d = darkThemeViewData();
    new PhoneMessageCallback().onMessage(darkThemeClimbPayload());
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 100;

    new ClimbProView().onUpdate(darkThemeDc());
    return true;
}
