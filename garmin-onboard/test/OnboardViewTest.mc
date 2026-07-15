using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;
using Toybox.Activity as Activity;

// Fakes for Activity.Info/Position.Location, since a datafield's compute()
// receives these from the system and the test harness can't construct real
// ones — same pattern as garmin-surface's SInfo/SLoc.
class OInfo {
    var currentLocation;
    function initialize(loc) { currentLocation = loc; }
}
class OLoc {
    var lat; var lon;
    function initialize(la, lo) { lat = la; lon = lo; }
    function toDegrees() { return [lat, lon]; }
}

(:test)
function view_rendersWindowStates(logger) {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    var dc = onbMakeDc();
    var v = new OnboardView();
    // 1: no data
    v.onUpdate(dc);
    // 2: parsed, before the climb (neutral terrain in window)
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);
    app.climbData.updatePosition(app.store.lat[2], app.store.lon[2]);
    v.onUpdate(dc);
    // 3: on the climb (colored segment inside the window)
    app.climbData.updatePosition(app.store.lat[18], app.store.lon[18]);
    Test.assert(app.climbData.activeClimbIndex == 0);
    v.onUpdate(dc);
    // 4: at the route's last point (window clamps to zero length)
    var n = app.store.pointCount;
    app.climbData.updatePosition(app.store.lat[n - 1], app.store.lon[n - 1]);
    v.onUpdate(dc);
    return true;
}

(:test)
function view_offRoute_bannerDrawnWithoutCrash(logger) {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    var dc = onbMakeDc();
    var v = new OnboardView();
    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);
    app.climbData.updatePosition(app.store.lat[5], app.store.lon[5]);
    v.onUpdate(dc);
    // ~0.01 deg lon east of the line (> 700 m at lat 50) => off route
    app.climbData.updatePosition(app.store.lat[5], app.store.lon[5] + 0.01);
    Test.assert(app.climbData.offRoute);
    v.onUpdate(dc);   // window still renders the frozen progress + banner
    return true;
}

// Datafield contract: compute(info) is how position reaches the view now
// (no Position.enableLocationEvents/onPosition — the activity feeds fixes
// directly via Activity.Info.currentLocation each tick).
(:test)
function view_compute_updatesProgressAndConsumesAlert(logger) {
    var app = App.getApp() as OnboardApp;
    app.store = new RawRouteStore();
    app.climbData = new OnboardClimbData();
    var v = new OnboardView();

    // null info (no GPS fix yet): must not crash or touch state.
    v.compute(null as Activity.Info);
    Test.assertEqual(app.climbData.routeProgress, 0.0);

    var msgs = onbWireMessages();
    var cb = new OnboardMessageCallback();
    cb.onMessage(msgs[0]);
    cb.onMessage(msgs[1]);

    // A real fix must advance progress via compute(), same contract as
    // OnboardClimbData.updatePosition.
    v.compute(new OInfo(new OLoc(app.store.lat[5], app.store.lon[5])) as Activity.Info);
    Test.assert((app.climbData.routeProgress - app.store.dist[5]).abs() < 15.0);

    // compute() must drain any pending climb-start alert (vibrate/tone),
    // not leave it queued for someone else to consume.
    app.climbData.pendingAlert = true;
    v.compute(new OInfo(new OLoc(app.store.lat[6], app.store.lon[6])) as Activity.Info);
    Test.assert(!app.climbData.takeAlert());
    return true;
}

(:test)
function app_getInitialView_returnsSingleDataFieldView(logger) {
    var app = App.getApp() as OnboardApp;
    var views = app.getInitialView();
    Test.assert(views != null && views.size() == 1);
    Test.assert(views[0] instanceof OnboardView);
    return true;
}
