using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;

// Coverage for issue #82: the accessibility "largeTextMode" app setting bumps fonts
// and drops secondary stats/lines across the datafield's screens, independent of and
// composable with "darkTheme" (see DarkThemeTest.mc). The font/line-selection logic
// itself is extracted as small pure functions on ClimbProView (nameFont, statFont,
// showSecondaryStat) so it's asserted directly here; the rendering call sites can
// only be smoke-tested (Toybox.Test can't assert pixel output), so those tests just
// confirm toggling the setting doesn't crash onUpdate().

function largeTextDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function largeTextViewData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function largeTextClimbPayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "large", "name" => "LargeText",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5],
              "surf" => [1, 0], "tsec" => [80, 90] }
        ]
    };
}

// Restores the default (off) so this test doesn't leak state into other tests in the
// suite -- properties persist across (:test) functions within a simulator run.
function resetLargeTextMode() {
    try {
        Properties.setValue("largeTextMode", false);
    } catch (e) {
        // Property store unavailable in this harness; nothing to reset.
    }
}

// =========================== property plumbing ==============================

(:test)
function largeTextMode_defaultIsOff(logger) {
    resetLargeTextMode();
    var v = Properties.getValue("largeTextMode");
    Test.assertEqual(v, false);
    return true;
}

(:test)
function largeTextMode_settingRoundTrips(logger) {
    resetLargeTextMode();
    Properties.setValue("largeTextMode", true);
    Test.assertEqual(Properties.getValue("largeTextMode"), true);
    resetLargeTextMode();
    return true;
}

// =========================== pure font/layout logic ==========================

(:test)
function nameFont_off_isCompact(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.nameFont(false), Gfx.FONT_TINY);
    return true;
}

(:test)
function nameFont_large_isBigger(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.nameFont(true), Gfx.FONT_MEDIUM);
    return true;
}

(:test)
function statFont_off_isCompact(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.statFont(false), Gfx.FONT_XTINY);
    return true;
}

(:test)
function statFont_large_isBigger(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.statFont(true), Gfx.FONT_SMALL);
    return true;
}

(:test)
function showSecondaryStat_off_showsEverything(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.showSecondaryStat(false), true);
    return true;
}

(:test)
function showSecondaryStat_large_dropsSecondaryLines(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.showSecondaryStat(true), false);
    return true;
}

// =========================== onUpdate smoke (no throw) =======================

(:test)
function largeTextMode_onUpdate_activeClimb_rendersWithoutThrow(logger) {
    resetLargeTextMode();
    Properties.setValue("largeTextMode", true);

    var d = largeTextViewData();
    new PhoneMessageCallback().onMessage(largeTextClimbPayload());
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;
    d.climbStartTimerMs = 0;

    new ClimbProView().onUpdate(largeTextDc());

    resetLargeTextMode();
    return true;
}

(:test)
function largeTextMode_onUpdate_nextClimbPreview_rendersWithoutThrow(logger) {
    resetLargeTextMode();
    Properties.setValue("largeTextMode", true);

    var d = largeTextViewData();
    new PhoneMessageCallback().onMessage(largeTextClimbPayload());
    d.activeClimbIndex = -1;
    d.nextClimbIndex = 0;
    d.distToNextClimb = 300;

    new ClimbProView().onUpdate(largeTextDc());

    resetLargeTextMode();
    return true;
}

// Composability: both darkTheme and largeTextMode on at once must still render
// without throwing -- the two settings are independent and additive (issue #82).
(:test)
function largeTextMode_withDarkTheme_bothOn_rendersWithoutThrow(logger) {
    resetLargeTextMode();
    resetDarkTheme();
    Properties.setValue("largeTextMode", true);
    Properties.setValue("darkTheme", true);

    var d = largeTextViewData();
    new PhoneMessageCallback().onMessage(largeTextClimbPayload());
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 100;

    new ClimbProView().onUpdate(largeTextDc());

    resetLargeTextMode();
    resetDarkTheme();
    return true;
}

// Normal (off/off) still renders fine -- guards against a future change accidentally
// making largeTextModeActive() throw on the "off" path.
(:test)
function largeTextMode_off_onUpdate_activeClimb_rendersWithoutThrow(logger) {
    resetLargeTextMode();

    var d = largeTextViewData();
    new PhoneMessageCallback().onMessage(largeTextClimbPayload());
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 100;

    new ClimbProView().onUpdate(largeTextDc());
    return true;
}
