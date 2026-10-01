using Toybox.Test;

// Coverage for issue #262 in the widget: the optional top-level "un" display-unit bitmask.

function wUnitsPayload(un) {
    var p = { "v" => 3, "mode" => "route", "routeId" => "wunits", "climbs" => [
        { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
          "segs" => [400, 30, 40, 2, 400, 30, 110, 5] } ] };
    if (un != null) { p.put("un", un); }
    return p;
}

(:test)
function widgetUnits_parsed_andResetWhenAbsent(logger) {
    var d = wData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(wUnitsPayload(1));
    Test.assertEqual(d.units, 1);
    cb.onMessage(wUnitsPayload(null));
    Test.assertEqual(d.units, 0);
    cb.onMessage(wUnitsPayload("x"));
    Test.assertEqual(d.units, 0);
    return true;
}

(:test)
function widgetUnits_formatDist(logger) {
    Test.assertEqual(Units.formatDist(1250, 0), "1.2km");
    Test.assertEqual(Units.formatDist(1250, 1), "0.7mi");
    Test.assertEqual(Units.formatElev(60, 1), "196ft");
    return true;
}
