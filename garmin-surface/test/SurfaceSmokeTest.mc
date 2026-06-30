using Toybox.Test;
using Toybox.Application as App;
using Toybox.Graphics as Gfx;
using Toybox.Activity as Activity;
using Toybox.Communications as Comm;

// Completes SurfaceData logic coverage (refineSubPieceByGPS + remaining branches)
// and smoke-runs SurfaceFieldView rendering + SurfaceFieldApp lifecycle against a
// real off-screen Dc, so a crash in the draw path is caught here instead of on the
// bike.

function s_makeDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

class SLoc {
    var lat; var lon;
    function initialize(la, lo) { lat = la; lon = lo; }
    function toDegrees() { return [lat, lon]; }
}

class SInfo {
    var elapsedDistance;
    var currentLocation;
    function initialize(elapsed, loc) {
        elapsedDistance = elapsed;
        currentLocation = loc;
    }
}

class SMsg {
    var data;
    function initialize(d) { data = d; }
}

function surfData() {
    var app = App.getApp() as SurfaceFieldApp;
    app.surfaceData = new SurfaceData();
    return app.surfaceData;
}

// One 1000 m section with a single checkpoint at distance 800 (lat 52.0, lon 5.0).
function oneSectionWithCp(d) {
    d.parse({
        "v" => 3, "mode" => "route", "routeId" => "sr", "name" => "Surf",
        "surfSec" => [
            { "s" => 0, "e" => 1000, "t" => 1, "n" => "Gravel",
              "cp" => [800, 5200000, 500000] },
            { "s" => 2000, "e" => 2600, "t" => 3 }
        ]
    });
}

// =========================== refineSubPieceByGPS ============================

(:test)
function refine_checkpointNear_overridesSubPiece(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(100);                 // distance-based subpiece = floor(100/80) = 1
    Test.assertEqual(d.currentSubPiece, 1);
    d.refineSubPieceByGPS([52.0, 5.0]);    // checkpoint at dist 800 -> subpiece floor(800/80) = 10
    Test.assertEqual(d.currentSubPiece, 10);
    return true;
}

(:test)
function refine_gpsFarFromCheckpoint_noChange(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(100);
    d.refineSubPieceByGPS([53.0, 5.0]);    // ~111 km north -> no checkpoint within snap
    Test.assertEqual(d.currentSubPiece, 1);
    return true;
}

(:test)
function refine_nullPos_noChange(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(100);
    d.refineSubPieceByGPS(null);
    Test.assertEqual(d.currentSubPiece, 1);
    return true;
}

(:test)
function refine_notInSection_returnsEarly(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(1500);                // between section 0 and 1 -> currentIdx -1
    Test.assertEqual(d.currentIdx, -1);
    d.refineSubPieceByGPS([52.0, 5.0]);    // must no-op without throwing
    Test.assertEqual(d.currentSubPiece, -1);
    return true;
}

// =========================== remaining SurfaceData branches =================

(:test)
function correctElapsed_nullPos_appliesStoredOffsetOnly(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    Test.assertEqual(d.correctElapsed(480, null), 480);   // offset still 0
    return true;
}

(:test)
function updateProgress_pastAllSections_clearsCurrentAndNext(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(5000);
    Test.assertEqual(d.currentIdx, -1);
    Test.assertEqual(d.nextIdx, -1);
    return true;
}

// =========================== SurfaceFieldView smoke ========================

(:test)
function view_noData_drawsPlaceholder(logger) {
    surfData();                            // payloadReceived false
    new SurfaceFieldView().onUpdate(s_makeDc());
    return true;
}

(:test)
function view_currentSection_drawsBarAndNext(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(300);                 // inside section 0, next = section 1
    new SurfaceFieldView().onUpdate(s_makeDc());
    return true;
}

(:test)
function view_nextOnly_drawsPreview(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(1500);                // between sections -> nextIdx set
    new SurfaceFieldView().onUpdate(s_makeDc());
    return true;
}

(:test)
function view_pastAll_drawsNoMoreSections(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    d.updateProgress(5000);                // currentIdx -1, nextIdx -1, count > 0
    new SurfaceFieldView().onUpdate(s_makeDc());
    return true;
}

(:test)
function view_compute_drivesMatchingAndAlert(logger) {
    var d = surfData();
    oneSectionWithCp(d);
    var v = new SurfaceFieldView();
    v.compute(null as Activity.Info);                              // null path
    v.compute(new SInfo(20, new SLoc(52.0, 5.0)) as Activity.Info); // into section 0 near start -> alert
    Test.assertEqual(d.currentIdx, 0);
    return true;
}

// =========================== SurfaceFieldApp lifecycle =====================

(:test)
function app_lifecycle_startMessageStop(logger) {
    var app = App.getApp() as SurfaceFieldApp;
    app.onStart(null);
    app.onPhoneMessage(new SMsg({
        "v" => 3, "mode" => "route", "routeId" => "x", "name" => "X",
        "surfSec" => [ { "s" => 0, "e" => 500, "t" => 2 } ]
    }) as Comm.PhoneAppMessage);
    Test.assertEqual(app.surfaceData.payloadReceived, true);
    Test.assertEqual(app.surfaceData.count, 1);

    var views = app.getInitialView();
    Test.assert(views != null && views.size() == 1);

    app.onPhoneMessage(new SMsg(null) as Comm.PhoneAppMessage);   // null data ignored
    app.onStop(null);
    return true;
}
