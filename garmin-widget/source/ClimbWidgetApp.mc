using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

(:glance)
class ClimbWidgetApp extends App.AppBase {

    var climbData;
    var phoneRouteIndex;
    var lastReceivedPayload;
    var activeAck;            // last ACTIVE_SET ack Dictionary from the phone, or null
    hidden var msgCallback;

    function initialize() {
        AppBase.initialize();
    }

    // Keep onStart minimal: it also runs in the glance scope, where the memory
    // budget is tiny. Full initialisation happens in getInitialView, which only
    // runs when the full app is launched.
    function onStart(state) {}

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null && msgCallback != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    function processMessage(msg) {
        if (msgCallback != null) {
            msgCallback.onMessage(msg);
            Ui.requestUpdate();
        }
    }

    function onStop(state) {}

    function getGlanceView() {
        return [ new ClimbGlanceView() ];
    }

    function getInitialView() {
        climbData        = new ClimbData();
        phoneRouteIndex  = new PhoneRouteIndex();
        msgCallback      = new PhoneMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        Sys.println("ClimbApp: started");

        // Issue #88: give a quick glance at already-synced climbs without waiting through
        // SyncView's phone-connect timer when the watch already has saved data locally.
        var decision = SyncRetryPolicy.initialViewForSavedData(
            StorageManager.getSavedRouteIds().size() > 0,
            StorageManager.getSavedClimbKeys().size() > 0);
        if (decision == :routeList) {
            // Still kick off a background refresh so a live phone list can merge in while the
            // user browses the cached list — RouteListView already re-reads phoneRouteIndex on
            // every onUpdate(), so no extra wiring is needed for that to show up.
            Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
            return [new RouteListView(), new RouteListDelegate()];
        }
        return [new SyncView(), new SyncDelegate()];
    }
}
