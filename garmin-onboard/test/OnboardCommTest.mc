using Toybox.Test;
using Toybox.Application as App;

// Encode a synthetic route (8 flat pts + 21 pts at 5%, ~100 m spacing) into
// RAW_HDR/RAW_CHUNK dictionaries exactly as the phone would push them.
function onbWireMessages() {
    var n = 29;
    var la = new [n];
    var lo = new [n];
    var el = new [n];
    for (var i = 0; i < n; i++) {
        la[i] = 5000000 + i * 90;    // 0.0009 deg steps x 1e5
        lo[i] = 500000;
        var e = (i < 8) ? 100.0 : 100.0 + (i - 8) * 5;
        el[i] = (e * 10).toNumber(); // decimeters
    }
    return [
        { "type" => "RAW_HDR", "id" => "e2e", "name" => "EndToEnd", "n" => n, "tot" => 1 },
        { "type" => "RAW_CHUNK", "id" => "e2e", "seq" => 0, "lat" => la, "lon" => lo, "ele" => el }
    ];
}

function onbApp() {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    app.pendingStore = null;
    app.pendingClimbData = null;
    return app;
}

(:test)
function comm_rawTransfer_parsesClimbEndToEnd(logger) {
    var app = onbApp();
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    Test.assert(!app.climbData.parsed);
    cb.onMessage(msgs[1]);
    // last chunk arrived => store complete, parse ran, route persisted
    Test.assert(app.store.complete);
    Test.assert(app.climbData.parsed);
    Test.assertEqual(app.climbData.climbCount, 1);
    Test.assert(app.climbData.climbLength[0] >= 1800);
    Test.assert(app.climbData.segCount[0] == 13);
    // persisted: a fresh store restores it
    var st2 = new RawRouteStore();
    Test.assert(st2.restoreFromStorage());
    Test.assertEqual(st2.routeId, "e2e");
    return true;
}

(:test)
function comm_abortedResync_keepsLiveRouteIntact(logger) {
    var app = onbApp();
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);
    Test.assert(app.store.complete);
    Test.assert(app.climbData.parsed);
    var origClimbCount = app.climbData.climbCount;
    var origRouteId = app.store.routeId;
    // Start a replacement transfer for a different route, but withhold its chunk
    // (simulating a mid-transfer BT drop).
    cb.onMessage({ "type" => "RAW_HDR", "id" => "replacement", "name" => "New",
                   "n" => 29, "tot" => 1 });
    // The live route must still be the original, complete/parsed one.
    Test.assert(app.store.complete);
    Test.assert(app.climbData.parsed);
    Test.assertEqual(app.climbData.climbCount, origClimbCount);
    Test.assertEqual(app.store.routeId, origRouteId);
    // The incoming transfer is tracked separately, pending.
    Test.assert(app.pendingStore != null);
    Test.assert(!app.pendingStore.complete);
    return true;
}

(:test)
function comm_garbageMessages_ignored(logger) {
    var app = onbApp();
    var cb = new OnboardMessageCallback();
    cb.onMessage(null);
    cb.onMessage("string");
    cb.onMessage({ "type" => "UNKNOWN_TYPE" });
    cb.onMessage({ "type" => "RAW_CHUNK", "seq" => 0 });   // chunk before header
    Test.assert(!app.store.complete);
    Test.assert(!app.climbData.parsed);
    return true;
}
