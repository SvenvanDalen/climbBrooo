using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Properties as Properties;
using Toybox.Graphics as Gfx;

// Rendering every metric code in every slot must never throw, with or without
// large-text mode. Pixel output can't be asserted (see LargeTextModeTest).

function fieldLayoutViewDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

function fieldLayoutViewRender(code, large) {
    try { Properties.setValue("largeTextMode", large); } catch (e) { }
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    var d = app.climbData;
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "lay" + code, "name" => "Lay",
        "lay" => [code, code, code, code, code],
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5],
              "tsec" => [80, 90], "vam" => [900, 1100, 950, 1200], "ib" => [250, 240, 260] }
        ]
    });
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;
    d.climbStartTimerMs = 0;
    new ClimbProView().onUpdate(fieldLayoutViewDc());
}

(:test)
function fieldLayout_onUpdate_everyCodeInEverySlot_rendersWithoutThrow(logger) {
    for (var c = 0; c <= FieldLayout.MAX_CODE; c++) {
        fieldLayoutViewRender(c, false);
        fieldLayoutViewRender(c, true);
    }
    try { Properties.setValue("largeTextMode", false); } catch (e) { }
    return true;
}

(:test)
function fieldLayout_slotShowsBlock(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.slotShowsBlock(FieldLayout.BLOCK, false), true);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.AUTO_ROW4, true), true);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.AUTO_ROW4, false), false);
    Test.assertEqual(v.slotShowsBlock(FieldLayout.VAM, true), false);
    return true;
}
