using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;
using Toybox.Activity as Activity;
using Toybox.Communications as Comm;

// Smoke coverage for the rendering + lifecycle paths that can't be asserted on
// pixels but CAN be executed against a real off-screen Dc to catch crashes
// (null derefs, bad API use) -- exactly the failures that otherwise only surface
// on the watch. Plus assertions for targetSecondsAt() (pure pacing logic).

// --- Off-screen graphics context -------------------------------------------
function makeDc() {
    // fr255m exposes the modern createBufferedBitmap() (the bare constructor is
    // deprecated post-4.0); it returns a reference whose .get() is the bitmap.
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

// --- Fake Activity.Info ------------------------------------------------------
class FakeLoc {
    var lat; var lon;
    function initialize(la, lo) { lat = la; lon = lo; }
    function toDegrees() { return [lat, lon]; }
}

class FakeInfo {
    var elapsedDistance;
    var timerTime;
    var distanceToDestination;
    var currentLocation;
    function initialize(elapsed, timer, distToDest, loc) {
        elapsedDistance = elapsed;
        timerTime = timer;
        distanceToDestination = distToDest;
        currentLocation = loc;
    }
}

class FakeMsg {
    var data;
    function initialize(d) { data = d; }
}

function viewData() {
    var app = App.getApp() as ClimbProApp;
    app.climbData = new ClimbData();
    return app.climbData;
}

// A single odometer-activated climb (no calib geometry) with pacing targets.
function noCalibClimb(d) {
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "nc", "name" => "NoCalib",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 3],
              "surf" => [1, 0],
              "tsec" => [80, 90] }
        ]
    });
}

// =========================== targetSecondsAt() ==============================

(:test)
function targetSecondsAt_noActiveClimb_returnsNegative(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = -1;
    Test.assertEqual(d.targetSecondsAt(), -1);
    return true;
}

(:test)
function targetSecondsAt_midFirstSegment_interpolates(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 200;          // 200/400 into seg0 (target 80) -> 40
    Test.assertEqual(d.targetSecondsAt().toNumber(), 40);
    return true;
}

(:test)
function targetSecondsAt_intoSecondSegment_accumulates(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 600;          // seg0 done (80) + 200/400 of seg1 (90) = 80 + 45
    Test.assertEqual(d.targetSecondsAt().toNumber(), 125);
    return true;
}

// =========================== onUpdate() smoke ===============================

(:test)
function onUpdate_noData_drawsPlaceholder(logger) {
    viewData();                       // payloadReceived == false
    new ClimbProView().onUpdate(makeDc());
    return true;
}

(:test)
function onUpdate_activeClimb_drawsProfileAndSurfaceAndGhost(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 1;
    d.progressInClimb = 500;
    d.climbStartTimerMs = 0;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

(:test)
function onUpdate_activeClimb_offRoute_drawsBanner(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 100;
    d.offRoute = true;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

(:test)
function onUpdate_nextClimb_drawsPreview(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = -1;
    d.nextClimbIndex = 0;
    d.distToNextClimb = 500;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

(:test)
function onUpdate_noClimbsAhead_drawsMessage(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage({ "v" => 3, "mode" => "route", "climbs" => [] });
    d.activeClimbIndex = -1;
    d.nextClimbIndex = -1;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

// =========================== compute() smoke ================================

(:test)
function compute_nullInfo_noThrow(logger) {
    var d = viewData();
    noCalibClimb(d);
    new ClimbProView().compute(null as Activity.Info);
    return true;
}

(:test)
function compute_withNavAndLocation_runsMatching(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "demo_route_full",
        "name" => "Full", "rtl" => 8000,
        "climbs" => [
            { "sd" => 1000, "ed" => 3000, "len" => 2000, "eg" => 80, "ag" => 40,
              "segs" => [500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1],
              "calib" => [0, 5150000, 510000, 1000, 5151000, 510500, 2000, 5152000, 511000] }
        ]
    });
    // distanceToDestination 6500 -> navDist 1500; location near calib point 0 (51.50, 5.10).
    var info = new FakeInfo(1500, 60000, 6500, new FakeLoc(51.50f, 5.10f));
    new ClimbProView().compute(info as Activity.Info);
    Test.assertEqual(d.lastElapsedDistance, 1500);   // odometer axis recorded
    return true;
}

(:test)
function compute_entersThenLeavesClimb_setsSummary_thenRenders(logger) {
    var d = viewData();
    noCalibClimb(d);
    var v = new ClimbProView();
    // Odometer inside the climb -> activates (no calib means odometer fallback).
    v.compute(new FakeInfo(1400, 10000, null, null) as Activity.Info);
    Test.assertEqual(d.activeClimbIndex, 0);
    // Odometer well past the end -> climb deactivates, summary captured.
    v.compute(new FakeInfo(2200, 90000, null, null) as Activity.Info);
    Test.assertEqual(d.activeClimbIndex, -1);
    // The summary branch of onUpdate should now run without throwing.
    v.onUpdate(makeDc());
    return true;
}

// =========================== App lifecycle smoke ============================

(:test)
function app_lifecycle_startMessageStop(logger) {
    var app = App.getApp() as ClimbProApp;
    app.onStart(null);                                  // builds climbData, registers listener
    app.onPhoneMessage(new FakeMsg(fullRoutePayload()) as Comm.PhoneAppMessage); // routes through msgCallback
    Test.assertEqual(app.climbData.payloadReceived, true);
    Test.assertEqual(app.climbData.climbCount, 1);

    var views = app.getInitialView();
    Test.assert(views != null);
    Test.assert(views.size() == 1);

    app.onPhoneMessage(new FakeMsg(null) as Comm.PhoneAppMessage); // null data -> ignored, no throw
    app.onStop(null);
    return true;
}
