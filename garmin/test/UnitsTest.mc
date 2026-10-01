using Toybox.Test;

// Coverage for issue #262 in the datafield: the optional top-level "un" display-unit
// bitmask and the Units formatting helpers.

function unitsPayload(un) {
    var p = { "v" => 3, "mode" => "route", "routeId" => "units", "climbs" => [
        { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
          "segs" => [400, 30, 40, 2, 400, 30, 110, 5] } ] };
    if (un != null) { p.put("un", un); }
    return p;
}

(:test)
function units_parse_imperialFlag(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(unitsPayload(1));
    Test.assertEqual(d.units, 1);
    Test.assert(Units.isImperial(d.units));
    return true;
}

(:test)
function units_parse_absentOrInvalid_isMetric(logger) {
    var d = freshData();
    var cb = new PhoneMessageCallback();
    cb.onMessage(unitsPayload(7));
    cb.onMessage(unitsPayload(null));      // resync without "un" resets to metric
    Test.assertEqual(d.units, 0);
    cb.onMessage(unitsPayload("1"));       // non-number
    Test.assertEqual(d.units, 0);
    cb.onMessage(unitsPayload(-1));        // negative
    Test.assertEqual(d.units, 0);
    return true;
}

(:test)
function units_parse_psiOnly_notImperial(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(unitsPayload(2));
    Test.assertEqual(d.units, 2);
    Test.assert(!Units.isImperial(d.units));
    return true;
}

(:test)
function units_formatDist_metric(logger) {
    Test.assertEqual(Units.formatDist(850, 0), "850m");
    Test.assertEqual(Units.formatDist(1250, 0), "1.2km");
    Test.assertEqual(Units.formatDist(1250, null), "1.2km");
    return true;
}

(:test)
function units_formatDist_imperial(logger) {
    Test.assertEqual(Units.formatDist(100, 1), "328ft");
    Test.assertEqual(Units.formatDist(1609, 1), "1.0mi");
    Test.assertEqual(Units.formatDist(5000, 1), "3.1mi");
    Test.assertEqual(Units.formatDist(400, 5), "0.2mi");
    return true;
}

(:test)
function units_formatElev(logger) {
    Test.assertEqual(Units.formatElev(80, 0), "80m");
    Test.assertEqual(Units.formatElev(80, 1), "262ft");
    return true;
}
