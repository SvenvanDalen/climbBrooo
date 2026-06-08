using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;
using Toybox.System as Sys;

class ClimbWidgetApp extends App.AppBase {

    var climbData;
    var phoneRouteIndex;
    var lastReceivedPayload;
    hidden var msgCallback;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        climbData        = new ClimbData();
        climbData.initialize();
        phoneRouteIndex  = new PhoneRouteIndex();
        msgCallback      = new PhoneMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));
        Sys.println("ClimbWidget: started");
    }

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    function processMessage(msg) {
        msgCallback.onMessage(msg);
        Ui.requestUpdate();
    }

    function onStop(state) {}

    function getInitialView() {
        return [new SyncView(), new SyncDelegate()];
    }
}
