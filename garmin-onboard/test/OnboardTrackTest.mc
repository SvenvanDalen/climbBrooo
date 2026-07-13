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

(:test)
function track_offRouteDebouncesRescanAndRecovers(logger) {
    var d = onbTrackData();
    var st = d.store;
    d.updatePosition(st.lat[5], st.lon[5]);
    Test.assert(!d.offRoute);
    // Go off-route: ~0.01 deg lon east of the line (> 700 m at lat 50).
    d.updatePosition(st.lat[5], st.lon[5] + 0.01);
    Test.assert(d.offRoute);
    // Repeated ticks while still far off-route: stays off-route, no crash,
    // and does not falsely "recover" just because the expensive rescan is
    // skipped on most ticks.
    for (var i = 0; i < 10; i++) {
        d.updatePosition(st.lat[5], st.lon[5] + 0.01);
        Test.assert(d.offRoute);
    }
    // Genuine recovery: back on the route line clears offRoute again.
    d.updatePosition(st.lat[6], st.lon[6]);
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
