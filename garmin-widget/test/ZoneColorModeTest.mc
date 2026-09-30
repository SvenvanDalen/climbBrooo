using Toybox.Test;
using Toybox.Application.Properties as Properties;

// Coverage for issue #66 in the widget: optional per-segment FTP intensity-zone colors
// ("zc") and the "colorMode" app setting (0 = Helling, default; 1 = FTP-zone).

function wZonePayload(zc) {
    var climb = { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
                  "segs" => [400, 30, 40, 2, 400, 30, 110, 5] };
    if (zc != null) { climb.put("zc", zc); }
    return { "v" => 3, "mode" => "route", "routeId" => "wzones", "climbs" => [ climb ] };
}

function wResetColorMode() {
    try {
        Properties.setValue("colorMode", 0);
    } catch (e) {
        // Property store unavailable in this harness; nothing to reset.
    }
}

(:test)
function widgetZc_parsed(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(wZonePayload([3, 4]));
    Test.assert(d.segZone[0] != null);
    Test.assertEqual(d.segZone[0][0], 3);
    Test.assertEqual(d.segZone[0][1], 4);
    Test.assertEqual(d.segColor[0][0], 2);   // gradient colors untouched
    return true;
}

(:test)
function widgetZc_absentOrShort_ignored(logger) {
    var d = wData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(wZonePayload([3, 4]));
    cb.onMessage(wZonePayload(null));        // resync without zc clears stale zones
    Test.assert(d.segZone[0] == null);
    cb.onMessage(wZonePayload([3]));         // shorter than segs
    Test.assert(d.segZone[0] == null);
    return true;
}

(:test)
function widgetZc_invalidEntry_fallsBackToGradientColor(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(wZonePayload([-1, 4]));
    Test.assertEqual(d.segZone[0][0], 2);
    Test.assertEqual(d.segZone[0][1], 4);
    return true;
}

(:test)
function widgetColorIndexAt_modes(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(wZonePayload([3, 4]));
    Test.assertEqual(d.colorIndexAt(0, 1, false), 5);   // gradient
    Test.assertEqual(d.colorIndexAt(0, 1, true), 4);    // FTP-zone
    new PhoneMessageCallback().onMessage(wZonePayload(null));
    Test.assertEqual(d.colorIndexAt(0, 1, true), 5);    // no zc -> gradient fallback
    return true;
}

(:test)
function widgetZoneColorsSelected_onlyForFtpZoneValue(logger) {
    var pd = new ProfileDrawer();
    Test.assertEqual(pd.zoneColorsSelected(1), true);
    Test.assertEqual(pd.zoneColorsSelected(0), false);
    Test.assertEqual(pd.zoneColorsSelected(null), false);
    Test.assertEqual(pd.zoneColorsSelected(true), false);
    return true;
}

(:test)
function widgetColorMode_ftpZone_drawProfile_noThrow(logger) {
    wResetColorMode();
    Properties.setValue("colorMode", 1);
    var d = wData();
    new PhoneMessageCallback().onMessage(wZonePayload([3, 4]));
    new ProfileDrawer().drawProfile(wMakeDc(), d, 0, 10, 40, 190, 80);
    wResetColorMode();
    return true;
}
