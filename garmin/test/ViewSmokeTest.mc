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

// Same shape as noCalibClimb but with a PR reference (refsec) instead of tsec.
function noCalibClimbWithRef(d) {
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "nr", "name" => "NoCalibRef",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 3],
              "surf" => [1, 0],
              "refsec" => [75, 85] }
        ]
    });
}

// A single odometer-activated climb (no calib geometry) with no pacing targets, so the
// datafield's bottom line falls back to the ETA-to-summit display.
function noTargetsClimb(d) {
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "noTargets", "name" => "NoTargets",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800,
              "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 3],
              "surf" => [1, 0] }
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

(:test)
function targetSecondsAt_atSegmentBoundary_returnsSegmentSum(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 400;          // exactly at the end of seg0 -> full 80, none of seg1
    Test.assertEqual(d.targetSecondsAt().toNumber(), 80);
    return true;
}

(:test)
function targetSecondsAt_pastLastSegment_clampsToTotal(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 5000;         // way past the 800 m climb -> clamps to 80 + 90
    Test.assertEqual(d.targetSecondsAt().toNumber(), 170);
    return true;
}

(:test)
function targetSecondsAt_noTargets_returnsNegative(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "nt", "name" => "NoTargets",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 3] }   // no tsec
        ]
    });
    d.activeClimbIndex = 0;
    d.progressInClimb = 200;
    Test.assertEqual(d.targetSecondsAt(), -1);   // hasTargets false -> -1
    return true;
}

// =========================== refSecondsAt() ==================================
// Mirrors the targetSecondsAt() coverage above: same interpolation logic, driven
// by refsec/segRefSec/hasRefTargets instead of tsec/segTargetSec/hasTargets.

(:test)
function refSecondsAt_noActiveClimb_returnsNegative(logger) {
    var d = viewData();
    noCalibClimbWithRef(d);
    d.activeClimbIndex = -1;
    Test.assertEqual(d.refSecondsAt(), -1);
    return true;
}

(:test)
function refSecondsAt_midFirstSegment_interpolates(logger) {
    var d = viewData();
    noCalibClimbWithRef(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 200;          // 200/400 into seg0 (ref 75) -> 37.5
    Test.assertEqual(d.refSecondsAt().toNumber(), 37);
    return true;
}

(:test)
function refSecondsAt_pastLastSegment_clampsToTotal(logger) {
    var d = viewData();
    noCalibClimbWithRef(d);
    d.activeClimbIndex = 0;
    d.progressInClimb = 5000;         // way past the 800 m climb -> clamps to 75 + 85
    Test.assertEqual(d.refSecondsAt().toNumber(), 160);
    return true;
}

(:test)
function refSecondsAt_noRefTargets_returnsNegative(logger) {
    var d = viewData();
    noCalibClimb(d);   // has tsec, no refsec
    d.activeClimbIndex = 0;
    d.progressInClimb = 200;
    Test.assertEqual(d.refSecondsAt(), -1);   // hasRefTargets false -> -1
    Test.assertEqual(d.targetSecondsAt().toNumber(), 40); // tsec is unaffected
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

// noTargetsClimb (below) has no tsec, so drawActiveClimb's bottom line falls through
// to the ETA branch instead of the pacing ghost.
(:test)
function onUpdate_activeClimb_noTargets_drawsEta(logger) {
    var d = viewData();
    noTargetsClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 200;
    d.currentSpeedMps = 4.0;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

// Near-zero speed on a targetless climb hits the "--:--" placeholder path.
(:test)
function onUpdate_activeClimb_noTargetsNoSpeed_drawsPlaceholder(logger) {
    var d = viewData();
    noTargetsClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 200;
    d.currentSpeedMps = 0.0;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

(:test)
function onUpdate_activeClimb_withRef_drawsPrGhost(logger) {
    var d = viewData();
    noCalibClimbWithRef(d);
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

// ===================== battery-vs-climb-time warning (#49) ===================
// batteryInsufficientForClimb()'s actual true/false logic is covered exhaustively
// as a pure function in ClimbDataTest.mc. These tests cover the view-level wiring:
// the persistent-banner state resets, and rendering doesn't crash. They deliberately
// don't assert on the outcome of the check itself, since Sys.getSystemStats().battery
// reads the real (simulator) battery level and isn't injectable from a test.

(:test)
function onUpdate_batteryWarningActive_drawsBanner(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 200;
    d.batteryWarningActive = true;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

// Off-route is the more urgent state; both banners fire but only the off-route one
// should occupy the top strip. Smoke-only (renders without throwing).
(:test)
function onUpdate_offRouteAndBatteryWarning_bothSet_noThrow(logger) {
    var d = viewData();
    noCalibClimb(d);
    d.activeClimbIndex = 0;
    d.activeSegmentIndex = 0;
    d.progressInClimb = 200;
    d.offRoute = true;
    d.batteryWarningActive = true;
    new ClimbProView().onUpdate(makeDc());
    return true;
}

// Leaving the climb clears a battery warning that was latched during it -- the banner
// must not persist into the next climb (or the no-active-climb state) by itself.
(:test)
function compute_leavingClimb_clearsBatteryWarning(logger) {
    var d = viewData();
    noCalibClimb(d);
    var v = new ClimbProView();
    v.compute(new FakeInfo(1400, 10000, null, null) as Activity.Info);
    Test.assertEqual(d.activeClimbIndex, 0);

    d.batteryWarningActive = true;   // simulate an earlier low-battery warning firing
    v.compute(new FakeInfo(2200, 90000, null, null) as Activity.Info); // odometer past the climb
    Test.assertEqual(d.activeClimbIndex, -1);
    Test.assertEqual(d.batteryWarningActive, false);
    return true;
}

// A route change (new payload/routeId) must also clear a latched battery warning,
// mirroring the reset the climb-start alert already gets on route change.
(:test)
function compute_routeChange_resetsBatteryWarning(logger) {
    var d = viewData();
    noCalibClimb(d);
    var v = new ClimbProView();
    v.compute(new FakeInfo(1400, 10000, null, null) as Activity.Info);
    d.batteryWarningActive = true;

    new PhoneMessageCallback().onMessage({
        "v" => 3, "mode" => "route", "routeId" => "different", "name" => "Different",
        "climbs" => [
            { "sd" => 1000, "ed" => 1800, "len" => 800, "eg" => 60, "ag" => 75,
              "segs" => [400, 30, 75, 3, 400, 30, 75, 3] }
        ]
    });
    v.compute(new FakeInfo(1400, 10000, null, null) as Activity.Info);
    Test.assertEqual(d.batteryWarningActive, false);
    return true;
}

// The battery check itself runs every tick on an active climb (until latched) -- this
// exercises that code path (including the real Sys.getSystemStats() call) end to end
// without throwing. The simulator's battery is typically near-full so this is not
// expected to latch, but either outcome is acceptable; only a crash would fail this.
(:test)
function compute_activeClimb_batteryCheckRuns_noThrow(logger) {
    var d = viewData();
    noCalibClimb(d);
    var v = new ClimbProView();
    v.compute(new FakeInfo(1400, 10000, null, null) as Activity.Info);
    Test.assertEqual(d.activeClimbIndex, 0);
    v.compute(new FakeInfo(1500, 20000, null, null) as Activity.Info); // second tick, still on climb
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

// =========================== navigation check (nav axis) ====================
// End-to-end proof that when the handed-off route is loaded as a Garmin course, the
// datafield trusts the along-course distance and matches climbs on it. The payload's
// rtl and the shared GPX come from the same route geometry, so the course length lines
// up with rtl and the length-gate engages.

// A single odometer-activated climb (no calib) on an 8 km route.
function navRoutePayload() {
    return {
        "v" => 3, "mode" => "route", "routeId" => "nav", "name" => "NavRoute", "rtl" => 8000,
        "climbs" => [
            { "sd" => 1000, "ed" => 3000, "len" => 2000, "eg" => 80, "ag" => 40,
              "segs" => [500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1, 500, 20, 40, 1] }
        ]
    };
}

(:test)
function compute_navCourseMatchesRoute_trustsNavAxis(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage(navRoutePayload());
    var v = new ClimbProView();

    // Tick near the start: distanceToDestination ~ full route length → length gate passes.
    v.compute(new FakeInfo(50, 10000, 7900, null) as Activity.Info);
    Test.assertEqual(d.navTrust, d.NAV_TRUSTED);

    // Later tick: course says 2000 m done. Even with a stale odometer (50), progress
    // follows the trusted nav axis → we are on the climb at [1000,3000].
    v.compute(new FakeInfo(50, 20000, 6000, null) as Activity.Info);
    Test.assertEqual(d.lastElapsedDistance, 2000);
    Test.assertEqual(d.activeClimbIndex, 0);
    return true;
}

(:test)
function compute_navCourseWrongLength_staysOnOdometer(logger) {
    var d = viewData();
    new PhoneMessageCallback().onMessage(navRoutePayload());   // our route is 8 km
    var v = new ClimbProView();

    // A different/shorter course is loaded: distanceToDestination never approaches 8 km,
    // so the length gate never trusts it and progress stays on the odometer.
    v.compute(new FakeInfo(1500, 10000, 4000, null) as Activity.Info);
    Test.assertEqual(d.navTrust, d.NAV_UNKNOWN);
    Test.assertEqual(d.lastElapsedDistance, 1500);   // odometer axis, not nav
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
