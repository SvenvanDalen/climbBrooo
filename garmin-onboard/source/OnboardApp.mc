using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

class OnboardApp extends App.AppBase {

    // Assigned in getInitialView; later tasks replace the nulls with real objects.
    var store = null;        // RawRouteStore (Task 2)
    var climbData = null;    // OnboardClimbData (Task 4)
    var routeIndex = null;   // OnboardRouteIndex (Task 7)
    hidden var msgCallback = null;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {}
    function onStop(state) {}

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null && msgCallback != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    // Test seam: same dispatch as onPhoneMessage but takes a plain Dictionary.
    function processMessage(msg) {
        if (msgCallback != null) {
            msgCallback.onMessage(msg);
        }
    }

    // Later tasks extend this: Task 7 assigns store/climbData/routeIndex/msgCallback
    // and restores the persisted route.
    function getInitialView() {
        Sys.println("OnboardApp: started");
        return [new OnboardView(), new OnboardDelegate()];
    }
}
