using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;

// Coverage for issue #66: the optional per-segment FTP intensity-zone colors ("zc") and
// the "colorMode" app setting (0 = Helling / gradient, default; 1 = FTP-zone). The parser
// must keep zc optional (absent, short or malformed -> gradient colors), and the renderer
// must use zc only in FTP-zone mode on a climb that has it.

function zoneDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function zoneData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function zonePayload(zc) {
    var climb = { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
                  "segs" => [400, 30, 40, 2, 400, 30, 110, 5] };
    if (zc != null) { climb.put("zc", zc); }
    return { "v" => 3, "mode" => "route", "routeId" => "zones", "climbs" => [ climb ] };
}

// Restores the default so this test doesn't leak state into other tests in the suite.
function resetColorMode() {
    try {
        Properties.setValue("colorMode", 0);
    } catch (e) {
        // Property store unavailable in this harness; nothing to reset.
    }
}

// =========================== parser ==========================================

(:test)
function zc_parsed_fromFullPayload(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());
    Test.assert(d.segZone[0] != null);
    Test.assertEqual(d.segZone[0].size(), 4);
    Test.assertEqual(d.segZone[0][0], 3);
    Test.assertEqual(d.segZone[0][2], 4);
    Test.assertEqual(d.segZone[0][3], 3);
    // The gradient colors in segs are untouched.
    Test.assertEqual(d.segColor[0][2], 1);
    return true;
}

(:test)
function zc_absent_segZoneNull(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload(null));
    Test.assert(d.segZone[0] == null);
    return true;
}

(:test)
function zc_shorterThanSegs_ignored(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload([3]));
    Test.assert(d.segZone[0] == null);
    return true;
}

(:test)
function zc_notAnArray_ignored(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload("3,4"));
    Test.assert(d.segZone[0] == null);
    return true;
}

(:test)
function zc_invalidEntry_fallsBackToGradientColor(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload([9, 1]));
    Test.assert(d.segZone[0] != null);
    Test.assertEqual(d.segZone[0][0], 2);   // 9 is out of range -> segs colorIndex 2
    Test.assertEqual(d.segZone[0][1], 1);
    return true;
}

(:test)
function zc_resyncWithoutZc_clearsStaleZones(logger) {
    var d = zoneData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(zonePayload([3, 4]));
    Test.assert(d.segZone[0] != null);
    cb.onMessage(zonePayload(null));
    Test.assert(d.segZone[0] == null);
    return true;
}

// =========================== color selection =================================

(:test)
function colorIndexAt_gradientMode_usesSegColor(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload([3, 4]));
    Test.assertEqual(d.colorIndexAt(0, 0, false), 2);
    Test.assertEqual(d.colorIndexAt(0, 1, false), 5);
    return true;
}

(:test)
function colorIndexAt_zoneMode_usesZc(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload([3, 4]));
    Test.assertEqual(d.colorIndexAt(0, 0, true), 3);
    Test.assertEqual(d.colorIndexAt(0, 1, true), 4);
    return true;
}

(:test)
function colorIndexAt_zoneModeWithoutZc_fallsBackToGradient(logger) {
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload(null));
    Test.assertEqual(d.colorIndexAt(0, 0, true), 2);
    Test.assertEqual(d.colorIndexAt(0, 1, true), 5);
    return true;
}

(:test)
function colorIndexAt_clampsOutOfRangeSegColor(logger) {
    var d = zoneData();
    d.segColor[0][0] = 9;
    d.segColor[0][1] = -2;
    Test.assertEqual(d.colorIndexAt(0, 0, false), 5);
    Test.assertEqual(d.colorIndexAt(0, 1, false), 0);
    return true;
}

// =========================== setting ========================================

(:test)
function colorMode_defaultIsGradient(logger) {
    resetColorMode();
    Test.assertEqual(Properties.getValue("colorMode"), 0);
    return true;
}

(:test)
function zoneColorsSelected_onlyForFtpZoneValue(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.zoneColorsSelected(1), true);
    Test.assertEqual(v.zoneColorsSelected(0), false);
    Test.assertEqual(v.zoneColorsSelected(null), false);
    Test.assertEqual(v.zoneColorsSelected(true), false);
    Test.assertEqual(v.zoneColorsSelected(7), false);
    return true;
}

(:test)
function colorMode_ftpZone_onUpdate_rendersWithoutThrow(logger) {
    resetColorMode();
    Properties.setValue("colorMode", 1);
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload([3, 4]));
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 100;
    new ClimbProView().onUpdate(zoneDc());
    resetColorMode();
    return true;
}

(:test)
function colorMode_ftpZone_withoutZc_onUpdate_rendersWithoutThrow(logger) {
    resetColorMode();
    Properties.setValue("colorMode", 1);
    var d = zoneData();
    new PhoneMessageCallback().onMessage(zonePayload(null));
    d.activeClimbIndex = -1;
    d.nextClimbIndex = 0;
    d.distToNextClimb = 300;
    new ClimbProView().onUpdate(zoneDc());
    resetColorMode();
    return true;
}
