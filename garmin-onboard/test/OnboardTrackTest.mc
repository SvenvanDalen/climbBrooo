using Toybox.Test;

// Store with a climb from ~800 m to ~2800 m: 8 flat points then 21 points at 5%.
function onbTrackData() {
    var eles = new [29];
    for (var i = 0; i < 8; i++) { eles[i] = 100; }
    for (var i = 8; i < 29; i++) { eles[i] = 100 + (i - 8) * 5; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    // No smoothing: detect + segment directly so climb bounds are exact.
    RouteParser.detectClimbs(st, d);
    RouteParser.segmentClimb(st, d, 0);
    d.store = st;
    d.parsed = true;
    return d;
}

(:test)
function track_progressAdvancesAndClimbActivates(logger) {
    var d = onbTrackData();
    var st = d.store;
    // stand on point 2 (~200 m): before the climb
    d.updatePosition(st.lat[2], st.lon[2]);
    Test.assert(!d.offRoute);
    Test.assert((d.routeProgress - st.dist[2]).abs() < 15.0);
    Test.assertEqual(d.activeClimbIndex, -1);
    Test.assertEqual(d.nextClimbIndex, 0);
    Test.assert(d.distToNextClimb > 500);
    // stand halfway up (~point 18)
    d.updatePosition(st.lat[18], st.lon[18]);
    Test.assertEqual(d.activeClimbIndex, 0);
    Test.assert(d.progressInClimb > 800);
    Test.assert(d.activeSegmentIndex >= 0);
    return true;
}

(:test)
function track_hysteresisRejectsBackwardsJitter(logger) {
    var d = onbTrackData();
    var st = d.store;
    d.updatePosition(st.lat[10], st.lon[10]);
    var p = d.routeProgress;
    // GPS jitter: a fix ~100 m BACK must not move progress backwards
    d.updatePosition(st.lat[9], st.lon[9]);
    Test.assert(d.routeProgress >= p - 1.0);
    // but real forward movement still works afterwards
    d.updatePosition(st.lat[12], st.lon[12]);
    Test.assert(d.routeProgress > p);
    return true;
}

(:test)
function track_farFromRoute_setsOffRoute(logger) {
    var d = onbTrackData();
    var st = d.store;
    d.updatePosition(st.lat[5], st.lon[5]);
    Test.assert(!d.offRoute);
    // ~0.01 deg lon east of the line (> 700 m at lat 50)
    d.updatePosition(st.lat[5], st.lon[5] + 0.01);
    Test.assert(d.offRoute);
    return true;
}

// A flat, 300-point route (> 2 * SEARCH_WINDOW_PTS) used only by the debounce
// test below: it needs to be long enough that once lastMatchIdx sits in the
// middle, a windowed search (+/- SEARCH_WINDOW_PTS) is a strict sub-range of
// the route rather than the whole thing -- otherwise the "lo > 0 || hi < n-2"
// debounce guard can never actually gate anything (the false positive this
// test used to be). Elevation profile is irrelevant here, so it's kept flat.
function onbLargeFlatTrackData() {
    var eles = new [300];
    for (var i = 0; i < 300; i++) { eles[i] = 100; }
    var st = onbStoreFromEle(eles);
    var d = new OnboardClimbData();
    d.store = st;
    d.parsed = true;
    return d;
}

(:test)
function track_offRouteDebouncesRescanAndRecovers(logger) {
    var d = onbLargeFlatTrackData();
    var st = d.store;
    // Establish an on-route match in the middle of the route so the next
    // windowed search (idx 150 +/- 120 = [30, 270] of 300 points) is a real
    // sub-range, not the full [0, n-2] span.
    d.updatePosition(st.lat[150], st.lon[150]);
    Test.assert(!d.offRoute);
    // bestMatch() may tie-break to the adjacent vertex when standing exactly
    // on point 150 (both the incoming and outgoing segment score dist 0) --
    // either is fine here, we only need lastMatchIdx solidly mid-route so the
    // next windowed search is a strict sub-range, not the full route.
    Test.assert((d.lastMatchIdx - 150).abs() <= 1);

    // Go off-route: ~0.01 deg lon east of the line (> 700 m at lat 50).
    // First off-route tick: guard fires, full rescan runs and still fails,
    // and the tick counter is reset to 0 (not incremented).
    d.updatePosition(st.lat[150], st.lon[150] + 0.01);
    Test.assert(d.offRoute);
    Test.assertEqual(d.offRouteTickCount, 0);

    // Repeated ticks while still far off-route and well under
    // OFFROUTE_RESCAN_TICKS: stays off-route, and the tick counter genuinely
    // increments each call -- proving the expensive full rescan is being
    // skipped (debounced) rather than re-run on every tick.
    for (var i = 0; i < 5; i++) {
        d.updatePosition(st.lat[150], st.lon[150] + 0.01);
        Test.assert(d.offRoute);
        Test.assertEqual(d.offRouteTickCount, i + 1);
    }

    // Genuine recovery: back on the route line clears offRoute again, even
    // after several debounced (counter-only) ticks.
    d.updatePosition(st.lat[155], st.lon[155]);
    Test.assert(!d.offRoute);
    return true;
}

(:test)
function track_alertFiresOncePerClimb(logger) {
    var d = onbTrackData();
    var st = d.store;
    var climbStart = d.climbStartDist[0];
    d.updatePosition(st.lat[3], st.lon[3]);          // far before: no alert
    Test.assert(!d.takeAlert());
    // point 7 is ~700 m; climb starts ~800 m => within 50 m needs a position
    // ~30 m before the start: interpolate between points 7 and 8.
    var t = ((climbStart - 30.0) - st.dist[7]) / (st.dist[8] - st.dist[7]);
    var alat = st.lat[7] + (st.lat[8] - st.lat[7]) * t;
    d.updatePosition(alat, st.lon[7]);
    Test.assert(d.takeAlert());
    Test.assert(!d.takeAlert());                     // consumed
    // drift back and re-approach: idempotent, no second alert
    d.routeProgress = st.dist[6];
    d.lastMatchIdx = 6;
    d.updatePosition(alat, st.lon[7]);
    Test.assert(!d.takeAlert());
    return true;
}
