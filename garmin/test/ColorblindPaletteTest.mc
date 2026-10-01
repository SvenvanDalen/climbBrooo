using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;

// Coverage for issue #258: the optional top-level "pal" payload key (1 = colorblind-friendly
// palette) and the colors the datafield picks from it. The colorIndex values themselves are
// untouched -- only the rendered colors change.

function palDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function palData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function palPayload(pal) {
    var msg = {
        "v" => 3, "mode" => "route", "routeId" => "pal", "name" => "Palette",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 15, 0, 400, 30, 110, 5],
              "tsec" => [100, 100] }
        ]
    };
    if (pal != null) { msg.put("pal", pal); }
    return msg;
}

(:test)
function palette_parsedFromPayload(logger) {
    var d = palData();
    new PhoneMessageCallback().onMessage(palPayload(1));
    Test.assertEqual(d.palette, 1);
    Test.assertEqual(d.segColor[0][0], 0);   // color indices unchanged
    Test.assertEqual(d.segColor[0][1], 5);
    return true;
}

(:test)
function palette_absentOrUnknown_isDefault(logger) {
    var d = palData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(palPayload(1));
    cb.onMessage(palPayload(null));          // resync without pal -> back to default
    Test.assertEqual(d.palette, 0);
    cb.onMessage(palPayload(2));             // future/unknown palette -> default
    Test.assertEqual(d.palette, 0);
    cb.onMessage(palPayload("1"));           // wrong type -> default
    Test.assertEqual(d.palette, 0);
    return true;
}

(:test)
function palette_colorsPerPalette(logger) {
    var v = new ClimbProView();
    var cvd = v.paletteColors(1, false);
    Test.assertEqual(cvd.size(), 6);
    Test.assertEqual(cvd[0], 0xFFFFAA);
    Test.assertEqual(cvd[3], 0x0055FF);
    Test.assertEqual(cvd[5], 0x0000AA);
    // Colorblind palette wins over the dark theme.
    Test.assertEqual(v.paletteColors(1, true)[5], 0x0000AA);
    Test.assertEqual(v.paletteColors(0, false)[0], 0x99FF99);
    Test.assertEqual(v.paletteColors(0, true)[0], 0x2E4D2E);
    return true;
}

(:test)
function palette_statusColors(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.okColor(0), Gfx.COLOR_GREEN);
    Test.assertEqual(v.badColor(0), Gfx.COLOR_RED);
    Test.assertEqual(v.okColor(1), 0x00AAFF);
    Test.assertEqual(v.badColor(1), 0xFF5500);
    return true;
}

(:test)
function palette_intervalZoneColors(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.intervalZoneColorFor(0, 0), Gfx.COLOR_DK_GREEN);
    Test.assertEqual(v.intervalZoneColorFor(1, 0), Gfx.COLOR_RED);
    Test.assertEqual(v.intervalZoneColorFor(-1, 1), 0x0000AA);
    Test.assertEqual(v.intervalZoneColorFor(0, 1), 0x00AAFF);
    Test.assertEqual(v.intervalZoneColorFor(1, 1), 0xFF5500);
    Test.assertEqual(v.intervalZoneColorFor(-2, 1), Gfx.COLOR_DK_GRAY);
    return true;
}

(:test)
function palette_colorblind_onUpdate_rendersWithoutThrow(logger) {
    var d = palData();
    new PhoneMessageCallback().onMessage(palPayload(1));
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;
    new ClimbProView().onUpdate(palDc());

    d.activeClimbIndex = -1;
    d.nextClimbIndex = 0;
    d.distToNextClimb = 300;
    new ClimbProView().onUpdate(palDc());
    return true;
}
