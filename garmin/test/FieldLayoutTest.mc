using Toybox.Test;
using Toybox.Application as App;

// Phone-chosen stat-slot layout ('lay'): parsing guards and the pure text formatter.

function fieldLayoutVals() {
    return {
        :remaining => 1500, :remElev => 120, :curGrad => 74, :avgGrad => 61,
        :hasVam => true, :vamAvg => 900, :vamPeak => 1100, :etaSec => 200,
        :speedMps => 5.0, :hr => null, :power => null, :cadence => null,
        :timerMs => 3725000
    };
}

function fieldLayoutPayload(lay) {
    var p = {
        "v" => 3, "mode" => "route", "routeId" => "lay", "name" => "Lay",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 5] }
        ]
    };
    if (lay != null) { p.put("lay", lay); }
    return p;
}

function fieldLayoutData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

(:test)
function fieldLayout_parse_missingGivesDefaults(logger) {
    var l = FieldLayout.parse(null);
    Test.assertEqual(l.size(), 5);
    Test.assertEqual(l[0], FieldLayout.REM_DIST);
    Test.assertEqual(l[1], FieldLayout.REM_ELEV);
    Test.assertEqual(l[2], FieldLayout.CUR_GRAD);
    Test.assertEqual(l[3], FieldLayout.AUTO_ROW4);
    Test.assertEqual(l[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_parse_wrongShapeGivesDefaults(logger) {
    Test.assertEqual(FieldLayout.parse([1, 2])[0], FieldLayout.REM_DIST);
    Test.assertEqual(FieldLayout.parse("8,9")[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_parse_badElementFallsBackPerSlot(logger) {
    var l = FieldLayout.parse([8, 99, -1, "x", 12]);
    Test.assertEqual(l[0], 8);
    Test.assertEqual(l[1], FieldLayout.REM_ELEV);
    Test.assertEqual(l[2], FieldLayout.CUR_GRAD);
    Test.assertEqual(l[3], FieldLayout.AUTO_ROW4);
    Test.assertEqual(l[4], 12);
    return true;
}

(:test)
function fieldLayout_message_setsLayout(logger) {
    var d = fieldLayoutData();
    new PhoneMessageCallback().onMessage(fieldLayoutPayload([8, 9, 10, 6, 5]));
    Test.assertEqual(d.layout[0], 8);
    Test.assertEqual(d.layout[4], 5);
    return true;
}

(:test)
function fieldLayout_resyncWithoutLay_restoresDefaults(logger) {
    var d = fieldLayoutData();
    new PhoneMessageCallback().onMessage(fieldLayoutPayload([8, 9, 10, 6, 5]));
    new PhoneMessageCallback().onMessage(fieldLayoutPayload(null));
    Test.assertEqual(d.layout[0], FieldLayout.REM_DIST);
    Test.assertEqual(d.layout[4], FieldLayout.AUTO_BOTTOM);
    return true;
}

(:test)
function fieldLayout_metricText_climbValues(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.REM_DIST, v, false), "1.5km");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.REM_ELEV, v, false), "120m↑");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CUR_GRAD, v, false), "7.4%");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AVG_GRAD, v, false), "~6.1%");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.ETA, v, true), "ETA 3:20");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.ELAPSED, v, true), "1:02:05");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.SPEED, v, true), "18.0km/u");
    return true;
}

(:test)
function fieldLayout_metricText_vamWideVsCompact(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, true), "VAM 900/1100");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, false), "900");
    v[:hasVam] = false;
    Test.assertEqual(FieldLayout.metricText(FieldLayout.VAM, v, true), "--");
    return true;
}

(:test)
function fieldLayout_metricText_missingSensorsShowDashes(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.HEART_RATE, v, false), "--");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.POWER, v, false), "--");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CADENCE, v, false), "--");
    v[:hr] = 142;
    v[:power] = 252;
    v[:cadence] = 88;
    Test.assertEqual(FieldLayout.metricText(FieldLayout.HEART_RATE, v, false), "142bpm");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.POWER, v, false), "252W");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.CADENCE, v, false), "88rpm");
    return true;
}

(:test)
function fieldLayout_metricText_specialCodes(logger) {
    var v = fieldLayoutVals();
    Test.assertEqual(FieldLayout.metricText(FieldLayout.EMPTY, v, true), "");
    Test.assertEqual(FieldLayout.metricText(FieldLayout.GHOST, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.BLOCK, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AUTO_ROW4, v, true), null);
    Test.assertEqual(FieldLayout.metricText(FieldLayout.AUTO_BOTTOM, v, true), null);
    return true;
}

(:test)
function fieldLayout_formatEta_negativeIsPlaceholder(logger) {
    Test.assertEqual(FieldLayout.formatEta(-1), "--:--");
    return true;
}
