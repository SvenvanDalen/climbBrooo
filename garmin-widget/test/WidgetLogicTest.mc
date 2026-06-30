using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Storage as Storage;
using Toybox.Communications as Comm;
using Toybox.Graphics as Gfx;

// Logic coverage for the widget: the wire parser + message dispatch, the
// Storage-backed route/climb catalogue, the phone route index, the profile
// drawer, the remaining ClimbData branches, and the app lifecycle.

class WMsg {
    var data;
    function initialize(d) { data = d; }
}

function wMakeDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

// v3 route payload mirroring protocol/examples (widget keys: no tsec; adds fss).
function widgetPayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "demo_w", "name" => "Widget demo",
        "climbs" => [
            { "sd" => 1000, "ed" => 3000, "len" => 2000, "eg" => 80, "ag" => 40, "n" => "Drag",
              "segs" => [500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1],
              "calib" => [0, 5150000, 510000, 1000, 5151000, 510500],
              "surf" => [1, 1, 0, 0] }
        ],
        "fss" => [ { "s" => 4000, "e" => 4600, "t" => 3, "n" => "Kasseistrook" } ]
    };
}

function wData() {
    var app = App.getApp() as ClimbWidgetApp;
    app.climbData = new ClimbData();
    app.phoneRouteIndex = new PhoneRouteIndex();
    return app.climbData;
}

// ============================ CommListener parser ===========================

(:test)
function widgetParse_v3_decodesClimbAndStarred(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(widgetPayload());

    Test.assertEqual(d.payloadReceived, true);
    Test.assertEqual(d.mode, "route");
    Test.assertEqual(d.routeId, "demo_w");
    Test.assertEqual(d.climbCount, 1);
    Test.assertEqual(d.climbStartDist[0], 1000);
    Test.assertEqual(d.climbLength[0], 2000);
    Test.assertEqual(d.segCount[0], 4);
    Test.assertEqual(d.segColor[0][0], 1);
    Test.assertEqual(d.calibCount[0], 2);
    Test.assertEqual(d.segSurf[0][2], 0);
    // fss decoded
    Test.assertEqual(d.flatStarredCount, 1);
    Test.assertEqual(d.flatStarredStart[0], 4000);
    Test.assertEqual(d.flatStarredSurf[0], 3);
    Test.assertEqual(d.flatStarredName[0], "Kasseistrook");
    // raw payload stashed for save-to-storage
    Test.assert((App.getApp() as ClimbWidgetApp).lastReceivedPayload != null);
    return true;
}

(:test)
function widgetParse_routeListType_populatesIndex(logger) {
    wData();
    new PhoneMessageCallback().onMessage({
        "type" => "ROUTE_LIST",
        "routes" => [ { "id" => "r1", "name" => "One", "climbCount" => 2 } ]
    });
    var idx = (App.getApp() as ClimbWidgetApp).phoneRouteIndex;
    Test.assert(idx.received);
    Test.assertEqual(idx.getCount(), 1);
    Test.assertEqual(idx.getName(0), "One");
    return true;
}

(:test)
function widgetParse_activeSetType_storesAck(logger) {
    wData();
    new PhoneMessageCallback().onMessage({ "type" => "ACTIVE_SET", "ok" => true, "name" => "RouteX" });
    var ack = (App.getApp() as ClimbWidgetApp).activeAck;
    Test.assertEqual(ack.get("ok"), true);
    return true;
}

(:test)
function widgetParse_unknownType_ignored(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage({ "type" => "WAT" });
    Test.assertEqual(d.payloadReceived, false);
    return true;
}

(:test)
function widgetParse_nullAndWrongVersion_rejected(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(null);
    Test.assertEqual(d.payloadReceived, false);
    new PhoneMessageCallback().onMessage({ "v" => 2, "climbs" => [] });
    Test.assertEqual(d.payloadReceived, false);
    return true;
}

(:test)
function commListener_callbacks_doNotThrow(logger) {
    var l = new CommListener();
    l.onComplete();
    l.onError();
    return true;
}

// ============================ StorageManager ================================

(:test)
function storage_routeRoundTrip_andMeta(logger) {
    StorageManager.deleteRoute("rt_a");
    Test.assertEqual(StorageManager.isRouteSaved("rt_a"), false);

    StorageManager.saveRoute("rt_a", { "name" => "Alpha", "climbs" => [ {}, {} ] });
    Test.assert(StorageManager.isRouteSaved("rt_a"));
    Test.assertEqual(StorageManager.loadRoute("rt_a").get("name"), "Alpha");

    var m = StorageManager.getSavedRouteMeta().get("rt_a");
    Test.assertEqual(m.get("name"), "Alpha");
    Test.assertEqual(m.get("climbCount"), 2);

    // Saving the same id again must not duplicate it in the id list.
    var before = StorageManager.getSavedRouteIds().size();
    StorageManager.saveRoute("rt_a", { "name" => "Alpha2", "climbs" => [] });
    Test.assertEqual(StorageManager.getSavedRouteIds().size(), before);

    StorageManager.deleteRoute("rt_a");
    Test.assertEqual(StorageManager.isRouteSaved("rt_a"), false);
    Test.assert(StorageManager.loadRoute("rt_a") == null);
    return true;
}

(:test)
function storage_saveRoute_nonDictPayload_defaults(logger) {
    StorageManager.saveRoute("rt_b", "not a dict");
    var m = StorageManager.getSavedRouteMeta().get("rt_b");
    Test.assertEqual(m.get("name"), "rt_b");
    Test.assertEqual(m.get("climbCount"), 0);
    StorageManager.deleteRoute("rt_b");
    return true;
}

(:test)
function storage_climbRoundTrip(logger) {
    StorageManager.deleteClimb("rt_c", 2);
    Test.assertEqual(StorageManager.isClimbSaved("rt_c", 2), false);

    StorageManager.saveClimb("rt_c", 2, { "n" => "Climbo" });
    Test.assert(StorageManager.isClimbSaved("rt_c", 2));
    Test.assertEqual(StorageManager.loadClimb("rt_c", 2).get("n"), "Climbo");

    StorageManager.deleteClimb("rt_c", 2);
    Test.assertEqual(StorageManager.isClimbSaved("rt_c", 2), false);
    Test.assert(StorageManager.loadClimb("rt_c", 2) == null);
    return true;
}

(:test)
function storage_gettersDefaultWhenAbsent(logger) {
    Storage.deleteValue("saved_route_ids");
    Storage.deleteValue("saved_climb_ids");
    Storage.deleteValue("saved_route_meta");
    Test.assertEqual(StorageManager.getSavedRouteIds().size(), 0);
    Test.assertEqual(StorageManager.getSavedClimbKeys().size(), 0);
    Test.assertEqual(StorageManager.getSavedRouteMeta().size(), 0);
    return true;
}

// ============================ PhoneRouteIndex ===============================

(:test)
function phoneIndex_populateAndGetters(logger) {
    var idx = new PhoneRouteIndex();
    Test.assertEqual(idx.getCount(), 0);
    idx.populate([ { "id" => "r1", "name" => "Route One", "climbCount" => 3 }, { "id" => "r2" } ]);
    Test.assert(idx.received);
    Test.assertEqual(idx.getCount(), 2);
    Test.assertEqual(idx.getId(0), "r1");
    Test.assertEqual(idx.getName(0), "Route One");
    Test.assertEqual(idx.getClimbCount(0), 3);
    Test.assertEqual(idx.getName(1), "Route");   // missing name -> default
    Test.assertEqual(idx.getClimbCount(1), 0);    // missing climbCount -> default
    return true;
}

(:test)
function phoneIndex_nonArrayAndNonDict_safe(logger) {
    var idx = new PhoneRouteIndex();
    idx.populate("nope");
    Test.assertEqual(idx.getCount(), 0);
    idx.populate([ 42 ]);                          // element not a dict
    Test.assert(idx.getId(0) == null);
    Test.assertEqual(idx.getName(0), "Route");
    Test.assertEqual(idx.getClimbCount(0), 0);
    return true;
}

// ============================ ProfileDrawer ================================

(:test)
function profileDrawer_drawsAndEarlyReturns(logger) {
    var d = wData();
    new PhoneMessageCallback().onMessage(widgetPayload());
    var pd = new ProfileDrawer();
    var dc = wMakeDc();
    pd.drawProfile(dc, d, 0, 8, 30, 200, 60);     // real climb -> draws
    pd.drawSurfaceBar(dc, d, 0, 8, 95, 200);      // mixed surfaces -> draws
    pd.drawProfile(dc, d, 5, 0, 0, 10, 10);       // empty climb (len 0) -> early return
    pd.drawSurfaceBar(dc, d, 5, 0, 0, 10);        // segCount 0 -> early return
    return true;
}

// ============================ ClimbData branches ===========================

(:test)
function widgetData_updateProgress_radiusMode_earlyReturn(logger) {
    var d = wData();
    d.payloadReceived = true;
    d.mode = "radius";
    d.updateProgress(1000);
    Test.assertEqual(d.lastElapsedDistance, 1000);
    Test.assertEqual(d.activeClimbIndex, -1);     // radius mode never sets an active climb here
    return true;
}

(:test)
function widgetData_checkCalibration_noActiveClimb_returns(logger) {
    var d = wData();
    d.activeClimbIndex = -1;
    d.checkCalibration(52.0f, 5.0f);              // must not throw
    return true;
}

// ============================ App lifecycle ================================

(:test)
function widgetApp_lifecycle_initGlanceMessageStop(logger) {
    var app = App.getApp() as ClimbWidgetApp;
    app.onStart(null);                            // intentionally minimal
    Test.assert(app.getGlanceView() != null);

    var init = app.getInitialView();              // builds climbData/index/callback
    Test.assert(init != null && init.size() == 2);
    Test.assert(app.climbData != null);

    app.processMessage(widgetPayload());
    Test.assert(app.climbData.payloadReceived);

    app.onPhoneMessage(new WMsg(widgetPayload()) as Comm.PhoneAppMessage);
    app.onPhoneMessage(new WMsg(null) as Comm.PhoneAppMessage);  // ignored
    app.onStop(null);
    return true;
}
