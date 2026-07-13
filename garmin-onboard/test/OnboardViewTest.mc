using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;

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
