using Toybox.Test;
using Toybox.Graphics as Gfx;

// Issue #180: interval block per climb (wire "ib" = [targetW, lowW, highW]).
// Covers the parser (valid, absent, malformed, resync-clear) and the pure
// under/in/over zone logic the datafield uses for its power-band line.

function blockClimb(ib) {
    var c = { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
              "segs" => [900, 50, 55, 2] };
    if (ib != null) { c.put("ib", ib); }
    return { "v" => 3, "mode" => "route", "climbs" => [ c ] };
}

(:test)
function ib_fullExample_parsed(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());
    Test.assert(d.hasBlock[0]);
    Test.assertEqual(d.blockTarget[0], 273);
    Test.assertEqual(d.blockLow[0], 266);
    Test.assertEqual(d.blockHigh[0], 280);
    return true;
}

(:test)
function ib_absent_noBlock(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb(null));
    Test.assertEqual(d.hasBlock[0], false);
    return true;
}

(:test)
function ib_tooShort_noBlock(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb([250, 240]));
    Test.assertEqual(d.hasBlock[0], false);
    return true;
}

(:test)
function ib_invertedOrNonNumeric_noBlock(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb([250, 280, 240]));
    Test.assertEqual(d.hasBlock[0], false);
    new PhoneMessageCallback().onMessage(blockClimb([250, "x", 260]));
    Test.assertEqual(d.hasBlock[0], false);
    return true;
}

// A resync without "ib" must clear a block from the previous payload.
(:test)
function ib_resyncWithoutBlock_clears(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb([250, 240, 260]));
    Test.assert(d.hasBlock[0]);
    new PhoneMessageCallback().onMessage(blockClimb(null));
    Test.assertEqual(d.hasBlock[0], false);
    return true;
}

(:test)
function powerZone_underInOverAndInclusiveEdges(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.powerZone(200, 240, 260), d.ZONE_UNDER);
    Test.assertEqual(d.powerZone(240, 240, 260), d.ZONE_IN);
    Test.assertEqual(d.powerZone(250, 240, 260), d.ZONE_IN);
    Test.assertEqual(d.powerZone(260, 240, 260), d.ZONE_IN);
    Test.assertEqual(d.powerZone(261, 240, 260), d.ZONE_OVER);
    return true;
}

// No power meter: currentPower is null, so the view shows only the target.
(:test)
function powerZone_nullPower_none(logger) {
    var d = new ClimbData();
    Test.assertEqual(d.powerZone(null, 240, 260), d.ZONE_NONE);
    return true;
}

(:test)
function blockZone_climbWithoutBlockOrOutOfRange_none(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb(null));
    Test.assertEqual(d.blockZone(0, 250), d.ZONE_NONE);
    Test.assertEqual(d.blockZone(-1, 250), d.ZONE_NONE);
    Test.assertEqual(d.blockZone(5, 250), d.ZONE_NONE);
    new PhoneMessageCallback().onMessage(blockClimb([250, 240, 260]));
    Test.assertEqual(d.blockZone(0, 300), d.ZONE_OVER);
    return true;
}

// "Blok klaar" only once the rider reaches the climb's end, and only with a block.
(:test)
function blockFinished_onlyAtTopWithBlock(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(blockClimb([250, 240, 260]));
    Test.assertEqual(d.blockFinished(0, 500), false);
    Test.assertEqual(d.blockFinished(0, 900), true);
    Test.assertEqual(d.blockFinished(0, 950), true);
    new PhoneMessageCallback().onMessage(blockClimb(null));
    Test.assertEqual(d.blockFinished(0, 950), false);
    return true;
}

(:test)
function intervalZoneColor_perZone(logger) {
    var v = new ClimbProView();
    Test.assertEqual(v.intervalZoneColor(-1), Gfx.COLOR_BLUE);
    Test.assertEqual(v.intervalZoneColor(0), Gfx.COLOR_DK_GREEN);
    Test.assertEqual(v.intervalZoneColor(1), Gfx.COLOR_RED);
    Test.assertEqual(v.intervalZoneColor(-2), Gfx.COLOR_DK_GRAY);
    return true;
}
