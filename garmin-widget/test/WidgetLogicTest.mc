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

// More starred flats than MAX_FLAT_STARRED are truncated to the array bound.
(:test)
function widgetData_parseFlatStarred_exceedsMax_caps(logger) {
    var d = wData();
    var n = d.MAX_FLAT_STARRED + 6;
    var fss = new [n];
    for (var i = 0; i < n; i++) {
        fss[i] = { "s" => i * 100, "e" => i * 100 + 50, "t" => 1 };
    }
    d.parseFlatStarred(fss);
    Test.assertEqual(d.flatStarredCount, d.MAX_FLAT_STARRED);
    return true;
}

// updateProgress records the odometer but activates nothing before a payload arrives.
(:test)
function widgetData_updateProgress_noPayload_earlyReturn(logger) {
    var d = wData();                              // payloadReceived false
    d.updateProgress(1500);
    Test.assertEqual(d.lastElapsedDistance, 1500);
    Test.assertEqual(d.activeClimbIndex, -1);
    return true;
}

// A non-array fss clears any stale starred flats.
(:test)
function widgetData_parseFlatStarred_nonArray_clears(logger) {
    var d = wData();
    d.parseFlatStarred([ { "s" => 100, "e" => 200, "t" => 1 } ]);
    Test.assertEqual(d.flatStarredCount, 1);
    d.parseFlatStarred("nope");
    Test.assertEqual(d.flatStarredCount, 0);
    return true;
}

// ============================ SyncRetryPolicy ===============================

(:test)
function syncRetryPolicy_initialViewForSavedData_picksRouteListWhenAnythingSaved(logger) {
    Test.assertEqual(SyncRetryPolicy.initialViewForSavedData(false, false), :sync);
    Test.assertEqual(SyncRetryPolicy.initialViewForSavedData(true, false), :routeList);
    Test.assertEqual(SyncRetryPolicy.initialViewForSavedData(false, true), :routeList);
    Test.assertEqual(SyncRetryPolicy.initialViewForSavedData(true, true), :routeList);
    return true;
}

// fastPathActionForTick backs getInitialView()'s background LIST_ROUTES retry (issue
// #131 review): same tick 3/6 retransmit schedule as actionForTick, but :giveUp is
// surfaced as :stop since the fast path never switches views.
(:test)
function syncRetryPolicy_fastPathActionForTick_retransmitsAt3And6(logger) {
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(3, false), :retransmit);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(6, false), :retransmit);
    return true;
}

(:test)
function syncRetryPolicy_fastPathActionForTick_waitsOnOtherTicksBeforeStop(logger) {
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(1, false), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(2, false), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(4, false), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(5, false), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(7, false), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(9, false), :wait);
    return true;
}

(:test)
function syncRetryPolicy_fastPathActionForTick_stopsFromTick10_neverGivesUp(logger) {
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(10, false), :stop);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(11, false), :stop);
    Test.assert(SyncRetryPolicy.fastPathActionForTick(10, false) != :giveUp);
    return true;
}

(:test)
function syncRetryPolicy_fastPathActionForTick_neverActsOnceReceived(logger) {
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(3, true), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(6, true), :wait);
    Test.assertEqual(SyncRetryPolicy.fastPathActionForTick(10, true), :wait);
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

// getInitialView() jumps straight to RouteListView when saved data already exists on the
// watch (issue #88), and only falls back to the SyncView wait when storage is empty.
(:test)
function widgetApp_getInitialView_routesOnSavedData_elseSync(logger) {
    var app = App.getApp() as ClimbWidgetApp;

    StorageManager.deleteRoute("giv_r1");
    Storage.deleteValue("saved_climb_ids");
    Test.assertEqual(StorageManager.getSavedRouteIds().size(), 0);
    Test.assertEqual(StorageManager.getSavedClimbKeys().size(), 0);

    var noneSaved = app.getInitialView();
    Test.assert(noneSaved[0] instanceof SyncView);

    StorageManager.saveRoute("giv_r1", { "name" => "GIV", "climbs" => [] });
    var withSaved = app.getInitialView();
    Test.assert(withSaved[0] instanceof RouteListView);

    StorageManager.deleteRoute("giv_r1");
    return true;
}

// getInitialView()'s fast-path arms a background retry timer (issue #131 review); its
// 1 Hz tick handler must be safe to invoke directly (retransmit ticks, stop tick, and
// the "already received" early-stop) without throwing.
(:test)
function widgetApp_onFastPathRetryTick_retransmitsThenStops_doesNotThrow(logger) {
    var app = App.getApp() as ClimbWidgetApp;

    StorageManager.saveRoute("giv_r2", { "name" => "GIV2", "climbs" => [] });
    app.getInitialView();                         // arms the fast-path retry timer

    app.phoneRouteIndex.received = false;
    for (var t = 0; t < 3; t++) { app.onFastPathRetryTick(); }  // ticks 1..3 -> retransmit at 3
    for (var t = 0; t < 10; t++) { app.onFastPathRetryTick(); } // runs past tick 10 -> stop

    app.phoneRouteIndex.received = true;
    app.onFastPathRetryTick();                    // already-received early stop, no throw

    StorageManager.deleteRoute("giv_r2");
    return true;
}
