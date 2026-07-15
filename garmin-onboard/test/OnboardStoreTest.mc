using Toybox.Test;
using Toybox.Application.Storage as Storage;

// Shared helper (also used by parser/segment/track tests): builds a completed
// store with points every ~100 m of latitude (0.0009 deg * 111111 m/deg) and
// the given elevations (meters).
function onbStoreFromEle(eles) {
    var st = new RawRouteStore();
    var n = eles.size();
    st.beginRoute("t1", "Test", n, 1);
    for (var i = 0; i < n; i++) {
        st.lat[i] = 50.0 + (i * 0.0009);
        st.lon[i] = 5.0;
        st.ele[i] = eles[i].toFloat();
    }
    st.finalizeRoute();
    return st;
}

(:test)
function store_assemblesChunksAndComputesDistance(logger) {
    var st = new RawRouteStore();
    Test.assert(st.beginRoute("r1", "Rit", 3, 1));
    // 0.0009 deg lat between points => ~100 m each; ele 10.0 m, 15.0 m, 20.0 m
    var ok = st.addChunk(0,
        [5000000, 5000090, 5000180],
        [500000, 500000, 500000],
        [100, 150, 200]);
    Test.assert(ok);
    Test.assert(st.complete);
    Test.assertEqual(st.pointCount, 3);
    // fixed-point decode
    Test.assert((st.lat[1] - 50.0009).abs() < 0.00001);
    Test.assert((st.ele[2] - 20.0).abs() < 0.01);
    // cumulative distance ~100 m per step
    Test.assert(st.dist[0] == 0);
    Test.assert((st.dist[1] - 100.0).abs() < 2.0);
    Test.assert((st.dist[2] - 200.0).abs() < 4.0);
    return true;
}

(:test)
function store_outOfOrderAndDuplicateChunks(logger) {
    var st = new RawRouteStore();
    // 251 points => 2 chunks (250 + 1)
    var n = 251;
    Test.assert(st.beginRoute("r2", "Rit2", n, 2));
    var lat0 = new [250]; var lon0 = new [250]; var ele0 = new [250];
    for (var i = 0; i < 250; i++) {
        lat0[i] = 5000000 + i * 90; lon0[i] = 500000; ele0[i] = 100;
    }
    // chunk 1 first (out of order); its single point is index 250
    Test.assert(st.addChunk(1, [5022500], [500000], [100]));
    Test.assert(!st.complete);
    // duplicate seq rejected
    Test.assert(!st.addChunk(1, [5022500], [500000], [100]));
    Test.assert(st.addChunk(0, lat0, lon0, ele0));
    Test.assert(st.complete);
    Test.assertEqual(st.chunksReceived, 2);
    return true;
}

(:test)
function store_rejectsGarbage(logger) {
    var st = new RawRouteStore();
    Test.assert(!st.beginRoute("x", "X", 0, 1));      // no points
    Test.assert(!st.beginRoute("x", "X", null, 1));   // bad n
    Test.assert(st.beginRoute("x", "X", 3, 1));
    Test.assert(!st.addChunk(null, [1], [1], [1]));   // bad seq
    Test.assert(!st.addChunk(5, [1], [1], [1]));      // seq out of range
    Test.assert(!st.addChunk(0, "no", [1], [1]));     // non-array
    Test.assert(!st.complete);
    return true;
}

(:test)
function store_clampsPointCountToMax(logger) {
    var st = new RawRouteStore();
    Test.assert(st.beginRoute("big", "Big", 9999, 84));
    Test.assertEqual(st.pointCount, st.MAX_POINTS);
    return true;
}

(:test)
function store_storageRoundTrip(logger) {
    var st = onbStoreFromEle([100, 105, 110, 115]);
    st.saveToStorage();
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assert(st2.complete);
    Test.assertEqual(st2.pointCount, 4);
    Test.assertEqual(st2.routeId, "t1");
    Test.assert((st2.ele[3] - 115.0).abs() < 0.01);
    Test.assert((st2.dist[3] - st.dist[3]).abs() < 1.0);
    // empty storage => restore fails
    Storage.deleteValue("onb_raw_meta");
    var st3 = new RawRouteStore();
    Test.assert(!st3.restoreFromStorage());
    return true;
}

(:test)
function store_saveToStorage_clearsOrphanedSlicesFromLargerPriorRoute(logger) {
    // First save a big route spanning 2 slices (2500 pts => slice 0 + slice 1).
    var bigEles = new [2500];
    for (var i = 0; i < 2500; i++) { bigEles[i] = 100; }
    var big = onbStoreFromEle(bigEles);
    big.saveToStorage();
    Test.assert(Storage.getValue("onb_raw_lat_1") != null);

    // A new, smaller route (500 pts => 1 slice) replaces it.
    var smallEles = new [500];
    for (var i = 0; i < 500; i++) { smallEles[i] = 100; }
    var small = onbStoreFromEle(smallEles);
    small.saveToStorage();

    // The orphaned slice-1 keys from the old, bigger route must be gone —
    // otherwise they'd sit in the watch's limited Storage forever.
    Test.assert(Storage.getValue("onb_raw_lat_1") == null);
    Test.assert(Storage.getValue("onb_raw_lon_1") == null);
    Test.assert(Storage.getValue("onb_raw_ele_1") == null);

    // A fresh restore must reflect exactly the smaller route, not a mix.
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assertEqual(st2.pointCount, 500);
    return true;
}

(:test)
function store_storageMultiSliceRoundTrip(logger) {
    // 2500 points spans two STORAGE_SLICE blocks (2000 + 500)
    var eles = new [2500];
    for (var i = 0; i < 2500; i++) { eles[i] = 100 + (i % 50); }
    var st = onbStoreFromEle(eles);
    st.saveToStorage();
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assertEqual(st2.pointCount, 2500);
    Test.assert((st2.ele[1999] - st.ele[1999]).abs() < 0.01);   // last of slice 0
    Test.assert((st2.ele[2400] - st.ele[2400]).abs() < 0.01);   // inside slice 1
    Test.assert((st2.dist[2499] - st.dist[2499]).abs() < 2.0);
    return true;
}
