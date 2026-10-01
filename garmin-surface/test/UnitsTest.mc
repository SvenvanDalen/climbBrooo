using Toybox.Test;

// Coverage for issue #262 in the surface datafield: the optional "un" display-unit bitmask.

(:test)
function surface_units_parsed_andDefaultMetric(logger) {
    var d = new SurfaceData();
    Test.assert(d.parse({ "v" => 3, "un" => 1,
        "surfSec" => [ { "s" => 0, "e" => 400, "t" => 1 } ] }));
    Test.assertEqual(d.units, 1);
    Test.assert(d.parse({ "v" => 3, "surfSec" => [ { "s" => 0, "e" => 400, "t" => 1 } ] }));
    Test.assertEqual(d.units, 0);
    return true;
}

(:test)
function surface_units_formatDist(logger) {
    Test.assertEqual(Units.formatDist(300, 0), "300m");
    Test.assertEqual(Units.formatDist(300, 1), "984ft");
    Test.assertEqual(Units.formatDist(3218, 1), "2.0mi");
    return true;
}
