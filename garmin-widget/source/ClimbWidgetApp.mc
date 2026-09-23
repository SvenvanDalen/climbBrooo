using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;
using Toybox.Timer as Timer;

(:glance)
class ClimbWidgetApp extends App.AppBase {

    var climbData;
    var phoneRouteIndex;
    var lastReceivedPayload;
    var activeAck;            // last ACTIVE_SET ack Dictionary from the phone, or null
    hidden var msgCallback;
    hidden var fastPathRetryTimer;
    hidden var fastPathRetryTick = 0;

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
            //
            // Issue #131 review: this one-shot transmit had no retry, unlike the SyncView wait
            // loop it partially replaces. Reuse SyncRetryPolicy's tick 3/6 retransmit schedule
            // in the background so a transient BT hiccup doesn't strand the user on stale data
            // until an unrelated HELLO arrives — without delaying the cached RouteListView.
            Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
            startFastPathRetry();
            return [new RouteListView(), new RouteListDelegate()];
        }
        return [new SyncView(), new SyncDelegate()];
    }

    hidden function startFastPathRetry() as Void {
        fastPathRetryTick = 0;
        if (fastPathRetryTimer != null) { fastPathRetryTimer.stop(); }
        fastPathRetryTimer = new Timer.Timer();
        fastPathRetryTimer.start(method(:onFastPathRetryTick), 1000, true);
    }

    // 1 Hz background tick driving the fast-path retry above. Never touches the
    // current view — RouteListView is already shown; this only makes sure the
    // background LIST_ROUTES merge attempt survives a transient BT hiccup.
    function onFastPathRetryTick() as Void {
        fastPathRetryTick++;
        var index = phoneRouteIndex;
        var received = (index != null && index.received);
        var action = SyncRetryPolicy.fastPathActionForTick(fastPathRetryTick, received);
        if (action == :retransmit) {
            Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
        } else if (action == :stop || received) {
            if (fastPathRetryTimer != null) { fastPathRetryTimer.stop(); fastPathRetryTimer = null; }
        }
    }
}
