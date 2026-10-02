using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;

// Coverage for issue #258 in the widget: the optional "pal" payload key is remembered for
// every screen (and the glance), saved-route replays don't overwrite it, and WidgetPalette
// hands out the matching colors.

function wPalPayload(pal) {
    var climb = { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
                  "segs" => [400, 30, 15, 0, 400, 30, 110, 5] };
    var msg = { "v" => 3, "mode" => "route", "routeId" => "wpal", "climbs" => [ climb ] };
    if (pal != null) { msg.put("pal", pal); }
    return msg;
}

(:test)
function widgetPalette_rememberedFromLivePayload(logger) {
    wData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(wPalPayload(1));
    Test.assertEqual(WidgetPalette.current(), 1);
    cb.onMessage(wPalPayload(null));         // phone turned it off -> default again
    Test.assertEqual(WidgetPalette.current(), 0);
    return true;
}

(:test)
function widgetPalette_replayKeepsCurrentPalette(logger) {
    wData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(wPalPayload(1));
    cb.replaying = true;                      // saved route stored before the switch
    cb.onMessage(wPalPayload(null));
    cb.replaying = false;
    Test.assertEqual(WidgetPalette.current(), 1);
    WidgetPalette.remember(null);
    return true;
}

(:test)
function widgetPalette_parseOnlyAcceptsOne(logger) {
    Test.assertEqual(WidgetPalette.parse(1), 1);
    Test.assertEqual(WidgetPalette.parse(0), 0);
    Test.assertEqual(WidgetPalette.parse(2), 0);
    Test.assertEqual(WidgetPalette.parse(null), 0);
    Test.assertEqual(WidgetPalette.parse("1"), 0);
    return true;
}

(:test)
function widgetPalette_colors(logger) {
    var cvd = WidgetPalette.gradientColors(1);
    Test.assertEqual(cvd.size(), 6);
    Test.assertEqual(cvd[0], 0xFFFFAA);
    Test.assertEqual(cvd[5], 0x0000AA);
    Test.assertEqual(WidgetPalette.gradientColors(0)[0], 0x99FF99);
    Test.assertEqual(WidgetPalette.okColor(1, Gfx.COLOR_GREEN), 0x00AAFF);
    Test.assertEqual(WidgetPalette.okColor(0, Gfx.COLOR_GREEN), Gfx.COLOR_GREEN);
    Test.assertEqual(WidgetPalette.badColor(1), 0xFF5500);
    Test.assertEqual(WidgetPalette.badColor(0), Gfx.COLOR_RED);
    return true;
}

(:test)
function widgetPalette_drawing_noThrow(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(wPalPayload(1));
    new ProfileDrawer().drawProfile(wMakeDc(), d, 0, 10, 40, 190, 80);
    WidgetPalette.drawConnectionDot(wMakeDc(), 100, 10, false, 1);
    WidgetPalette.drawConnectionDot(wMakeDc(), 100, 10, true, 1);
    WidgetPalette.remember(null);
    return true;
}
