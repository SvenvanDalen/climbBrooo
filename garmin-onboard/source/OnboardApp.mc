using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

class OnboardApp extends App.AppBase {

    // Assigned in getInitialView; later tasks replace the nulls with real objects.
    var store = null;        // RawRouteStore (Task 2)
    var climbData = null;    // OnboardClimbData (Task 4)
    hidden var msgCallback = null;

    // In-progress incoming transfer, not yet live (see OnboardCommListener).
    var pendingStore = null;
    var pendingClimbData = null;

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

    function getInitialView() {
        store = new RawRouteStore();
        climbData = new OnboardClimbData();
        msgCallback = new OnboardMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        // Offline-first: re-parse the last synced route from storage.
        if (store.restoreFromStorage()) {
            RouteParser.parse(store, climbData);
        }
        Sys.println("OnboardApp: started");
        return [ new OnboardView() ];
    }
}
