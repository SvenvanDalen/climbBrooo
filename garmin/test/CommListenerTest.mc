using Toybox.Test;
using Toybox.Application as App;
using Toybox.Lang;

// Exercises the v3 wire parser (PhoneMessageCallback.onMessage) end to end by
// feeding it dictionaries that mirror protocol/examples/*.json and asserting the
// resulting ClimbData fields. This is the watch-side counterpart to the JVM
// ProtocolRoundTripTest: it proves the hand-written Monkey C parser actually
// decodes the packed format the phone produces, instead of being review-only.

// Build the route_mode_full.json payload as a Monkey C Dictionary.
function fullRoutePayload() {
    return {
        "v"       => 3,
        "mode"    => "route",
        "routeId" => "demo_route_full",
        "name"    => "Full demo climb",
        "rtl"     => 8000,
        "climbs"  => [
            {
                "sd" => 1000, "ed" => 3000, "len" => 2000,
                "eg" => 80,   "ag" => 40,   "n"   => "Long Drag",
                "segs"  => [500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1],
                "calib" => [0, 5150000, 510000, 1000, 5151000, 510500, 2000, 5152000, 511000],
                "surf"  => [1, 1, 0, 0],
                "tsec"  => [60, 62, 64, 66]
            }
        ]
    };
}

function freshData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

function fapprox(a, b) {
    var d = a - b;
    if (d < 0) { d = -d; }
    return d < 0.001;
}

(:test)
function parse_routeMode_decodesAllFields(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());

    Test.assertEqual(d.payloadReceived, true);
    Test.assertEqual(d.mode, "route");
    Test.assertEqual(d.routeId, "demo_route_full");
    Test.assertEqual(d.routeName, "Full demo climb");
    Test.assertEqual(d.routeTotalLen, 8000);
    Test.assertEqual(d.climbCount, 1);

    // Climb-level: anchors mirror sd/ed, scalars decoded.
    Test.assertEqual(d.climbStartDist0[0], 1000);
    Test.assertEqual(d.climbEndDist0[0], 3000);
    Test.assertEqual(d.climbStartDist[0], 1000);
    Test.assertEqual(d.climbLength[0], 2000);
    Test.assertEqual(d.climbElevGain[0], 80);
    Test.assertEqual(d.climbAvgGrad[0], 40);
    Test.assertEqual(d.climbName[0], "Long Drag");
    return true;
}

(:test)
function parse_routeMode_unpacksSegmentArray(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());

    // segs is a flat [dist, elevGain, gradient, colorIdx] x 4.
    Test.assertEqual(d.segCount[0], 4);
    Test.assertEqual(d.segDist[0][0], 500);
    Test.assertEqual(d.segElevGain[0][1], 20);
    Test.assertEqual(d.segGradient[0][2], 40);
    Test.assertEqual(d.segColor[0][3], 1);
    return true;
}

(:test)
function parse_routeMode_unpacksCalibAndScalesCoords(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());

    // calib is a flat [distFromStart, latInt, lonInt] x 3; coords are degrees*100000.
    Test.assertEqual(d.calibCount[0], 3);
    Test.assertEqual(d.calibDist[0][1], 1000);
    Test.assert(fapprox(d.calibLat[0][1], 51.51f));
    Test.assert(fapprox(d.calibLon[0][2], 5.11f));
    return true;
}

(:test)
function parse_routeMode_unpacksSurfaceAndTargets(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(fullRoutePayload());

    Test.assertEqual(d.segSurf[0][0], 1);   // gravel
    Test.assertEqual(d.segSurf[0][2], 0);   // asphalt
    Test.assert(d.hasTargets[0]);
    Test.assertEqual(d.segTargetSec[0][0], 60);
    Test.assertEqual(d.segTargetSec[0][3], 66);
    return true;
}

(:test)
function parse_radiusMode_decodesStartCoords(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v"      => 3,
        "mode"   => "radius",
        "climbs" => [
            { "slat" => 5084231, "slon" => 585412, "len" => 2900, "eg" => 210, "ag" => 72,
              "n" => "Cauberg", "segs" => [230, 13, 57, 2, 140, 12, 86, 4] }
        ]
    });

    Test.assertEqual(d.mode, "radius");
    Test.assertEqual(d.climbCount, 1);
    Test.assertEqual(d.routeTotalLen, 0);        // no "rtl" key -> 0
    Test.assert(fapprox(d.climbStartLat[0], 50.84231f));
    Test.assert(fapprox(d.climbStartLon[0], 5.85412f));
    Test.assertEqual(d.climbName[0], "Cauberg");
    return true;
}

(:test)
function parse_missingSurfAndTargets_defaultsSafely(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "r", "name" => "n", "climbs" => [
            { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
              "segs" => [900, 50, 55, 2] }
        ]
    });

    Test.assertEqual(d.segSurf[0][0], 5);    // UNKNOWN default when surf absent
    Test.assertEqual(d.hasTargets[0], false);
    Test.assertEqual(d.calibCount[0], 0);
    return true;
}

(:test)
function parse_nullMessage_isIgnored(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage(null);
    Test.assertEqual(d.payloadReceived, false);
    Test.assertEqual(d.climbCount, 0);
    return true;
}

(:test)
function parse_nonDictMessage_isIgnored(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage("not a dict");
    Test.assertEqual(d.payloadReceived, false);
    return true;
}

(:test)
function parse_wrongVersion_isRejected(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({ "v" => 2, "mode" => "route", "climbs" => [] });
    Test.assertEqual(d.payloadReceived, false);
    return true;
}

(:test)
function parse_noClimbsKey_setsZeroCount(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({ "v" => 3, "mode" => "route" });
    Test.assertEqual(d.payloadReceived, true);
    Test.assertEqual(d.climbCount, 0);
    return true;
}

// CommListener (connection callbacks) just log; smoke them for coverage.
(:test)
function commListener_callbacks_doNotThrow(logger) {
    var l = new CommListener();
    l.onComplete();
    l.onError();
    return true;
}

// ===========================================================================
// Parser edge cases
// ===========================================================================

// More climbs than MAX_CLIMBS are truncated to the array bound.
(:test)
function parse_moreClimbsThanMax_truncates(logger) {
    var d = freshData();
    var n = d.MAX_CLIMBS + 3;
    var climbs = new [n];
    for (var i = 0; i < n; i++) {
        climbs[i] = { "sd" => i * 100, "ed" => i * 100 + 90, "len" => 90,
                      "eg" => 5, "ag" => 55, "segs" => [90, 5, 55, 2] };
    }
    new PhoneMessageCallback().onMessage({ "v" => 3, "mode" => "route", "climbs" => climbs });
    Test.assertEqual(d.climbCount, d.MAX_CLIMBS);
    return true;
}

// A segment array longer than MAX_SEGMENTS is truncated per climb.
(:test)
function parse_moreSegmentsThanMax_truncates(logger) {
    var d = freshData();
    var segN = d.MAX_SEGMENTS + 4;
    var segs = new [segN * 4];
    for (var s = 0; s < segN; s++) {
        segs[s * 4] = 100; segs[s * 4 + 1] = 5; segs[s * 4 + 2] = 55; segs[s * 4 + 3] = 2;
    }
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55, "segs" => segs } ]
    });
    Test.assertEqual(d.segCount[0], d.MAX_SEGMENTS);
    return true;
}

// A segs array whose length is not a multiple of 4 keeps only the whole quads.
(:test)
function parse_segsNotMultipleOfFour_dropsRemainder(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [400, 20, 40, 1, 500, 30, 50, 2, 999, 9] } ]  // 10 ints -> 2 segs
    });
    Test.assertEqual(d.segCount[0], 2);
    Test.assertEqual(d.segDist[0][1], 500);
    return true;
}

// A calib array shorter than one triple is ignored (no calibration geometry).
(:test)
function parse_calibTooShort_zeroCalib(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [900, 50, 55, 2], "calib" => [0, 5150000] } ]  // size 2 < 3
    });
    Test.assertEqual(d.calibCount[0], 0);
    return true;
}

// Surface values outside 0..5 are rejected and left as UNKNOWN (5).
(:test)
function parse_surfOutOfRange_staysUnknown(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [400, 20, 40, 1, 500, 30, 50, 2],
                        "surf" => [1, 9] } ]   // 9 is invalid
    });
    Test.assertEqual(d.segSurf[0][0], 1);   // valid kept
    Test.assertEqual(d.segSurf[0][1], 5);   // invalid -> UNKNOWN
    return true;
}

// A tsec array shorter than the segment count disables targets for that climb.
(:test)
function parse_tsecShorterThanSegs_noTargets(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [400, 20, 40, 1, 500, 30, 50, 2],
                        "tsec" => [60] } ]   // only 1 of 2
    });
    Test.assertEqual(d.hasTargets[0], false);
    return true;
}

// A null climb element is skipped without throwing; its segment count stays 0.
(:test)
function parse_nullClimbElement_zeroSegs(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({ "v" => 3, "mode" => "route", "climbs" => [null] });
    Test.assertEqual(d.payloadReceived, true);
    Test.assertEqual(d.climbCount, 1);
    Test.assertEqual(d.segCount[0], 0);
    return true;
}

// Resyncing with a payload that omits surf must clear surfaces from the prior sync.
(:test)
function parse_resyncWithoutSurf_clearsStale(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [400, 20, 40, 1, 500, 30, 50, 2], "surf" => [1, 1] } ]
    });
    Test.assertEqual(d.segSurf[0][0], 1);

    // Same ClimbData (no freshData) — resync without surf.
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [400, 20, 40, 1, 500, 30, 50, 2] } ]
    });
    Test.assertEqual(d.segSurf[0][0], 5);   // stale gravel cleared to UNKNOWN
    Test.assertEqual(d.segSurf[0][1], 5);
    return true;
}

// A non-numeric rtl falls back to 0 (unknown route length).
(:test)
function parse_rtlNotNumber_defaultsZero(logger) {
    var d = freshData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "rtl" => "8000",
        "climbs" => [ { "sd" => 0, "ed" => 900, "len" => 900, "eg" => 50, "ag" => 55,
                        "segs" => [900, 50, 55, 2] } ]
    });
    Test.assertEqual(d.routeTotalLen, 0);
    Test.assertEqual(d.payloadReceived, true);
    return true;
}
