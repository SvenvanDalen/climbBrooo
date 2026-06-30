using Toybox.Test;
using Toybox.Application as App;

// Smoke coverage for every widget view's onUpdate (run against a real off-screen
// Dc to catch render crashes) plus the safe, non-navigating delegate handlers.
// Navigation handlers (onBack/onMenu that call Ui.pushView/popView) need a live
// view stack and are exercised on-device, not here.

// Brings the app into a fully initialised state with a route loaded.
function viewApp() {
    var app = App.getApp() as ClimbWidgetApp;
    app.getInitialView();                 // builds climbData, phoneRouteIndex, msgCallback
    app.processMessage(widgetPayload());  // climbData now has a climb + a starred segment
    return app;
}

// =============================== ClimbGlanceView ============================

(:test)
function view_glance_draws(logger) {
    viewApp();
    new ClimbGlanceView().onUpdate(wMakeDc());
    return true;
}

// =============================== ActiveSetView =============================

(:test)
function view_activeSet_allAckStates(logger) {
    var app = viewApp();
    var v = new ActiveSetView();

    v.onShow();                           // clears activeAck
    app.activeAck = null;
    v.onUpdate(wMakeDc());                // "Versturen..."

    app.activeAck = { "ok" => true, "name" => "RouteX" };
    v.onUpdate(wMakeDc());                // success + name

    app.activeAck = { "ok" => false };
    v.onUpdate(wMakeDc());                // "Mislukt"

    new ActiveSetDelegate();              // constructor coverage
    return true;
}

// =============================== SyncView =================================

(:test)
function view_sync_drawsConnecting(logger) {
    var app = viewApp();
    app.phoneRouteIndex.received = false; // stay on the connecting screen (no view switch)
    var v = new SyncView();
    v.onUpdate(wMakeDc());
    v.onHide();                           // timer is null -> safe
    new SyncDelegate();
    return true;
}

// =============================== ClimbListView ============================

(:test)
function view_climbList_neutralSource_drawsRows(logger) {
    viewApp();
    var v = new ClimbListView("demo_w", "x");   // neutral source: no transmit, no load
    Test.assertEqual(v.getRouteId(), "demo_w");
    v.refreshRouteSaved();
    v.onUpdate(wMakeDc());                       // climb row + starred row + action rows
    v.selectedIndex = 1;                        // scroll into the starred/action region
    v.onUpdate(wMakeDc());
    return true;
}

(:test)
function view_climbList_savedRouteMissing_drawsLoadFailed(logger) {
    viewApp();
    StorageManager.deleteRoute("ghost_route");
    var v = new ClimbListView("ghost_route", "saved_route");   // nothing saved -> loadError
    v.onUpdate(wMakeDc());
    return true;
}

(:test)
function view_climbList_delegate_safeHandlers(logger) {
    viewApp();
    var d = new ClimbListDelegate();
    // Current view is not a ClimbListView in the test harness, so these execute
    // their guard path and return true without navigating.
    Test.assert(d.onNextPage());
    Test.assert(d.onPreviousPage());
    Test.assert(d.onSelect());
    return true;
}

// =============================== ClimbDetailView ==========================

(:test)
function view_climbDetail_drawsAndToggles(logger) {
    var app = viewApp();
    var v = new ClimbDetailView(0, 0);
    Test.assertEqual(v.getOriginalClimbIndex(), 0);
    v.refreshClimbSaved();
    v.onUpdate(wMakeDc());                 // profile + stats + save hint

    // toggleSave persists then clears the climb in storage (routeId is set).
    StorageManager.deleteClimb("demo_w", 0);
    v.toggleSave();                        // saves
    Test.assert(StorageManager.isClimbSaved("demo_w", 0));
    v.toggleSave();                        // removes
    Test.assertEqual(StorageManager.isClimbSaved("demo_w", 0), false);
    v.onUpdate(wMakeDc());

    new ClimbDetailDelegate();
    return true;
}

// =============================== RouteListView ============================

(:test)
function view_routeList_withPhoneAndSavedRows(logger) {
    var app = viewApp();
    app.phoneRouteIndex.populate([
        { "id" => "p1", "name" => "Phone One", "climbCount" => 2 }
    ]);
    StorageManager.saveRoute("saved1", { "name" => "Saved One", "climbs" => [ {} ] });

    var v = new RouteListView();           // constructor calls refreshData
    v.onShow();
    Test.assert(v.getTotalCount() >= 2);
    v.onUpdate(wMakeDc());                  // phone row + divider + saved row
    v.selectedIndex = 1;
    v.onUpdate(wMakeDc());

    StorageManager.deleteRoute("saved1");
    new RouteListDelegate();
    return true;
}

(:test)
function view_routeList_emptyState(logger) {
    var app = viewApp();
    app.phoneRouteIndex.received = false;
    StorageManager.getSavedRouteIds();     // ensure module touched
    // Wipe the catalogue so the list renders its "No routes" empty state.
    Toybox.Application.Storage.deleteValue("saved_route_ids");
    Toybox.Application.Storage.deleteValue("saved_climb_ids");
    var v = new RouteListView();
    v.onUpdate(wMakeDc());
    return true;
}

(:test)
function view_routeList_delegate_safeHandlers(logger) {
    viewApp();
    var d = new RouteListDelegate();
    Test.assert(d.onNextPage());
    Test.assert(d.onPreviousPage());
    Test.assert(d.onSelect());
    return true;
}
