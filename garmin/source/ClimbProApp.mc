using Toybox.Application as App;
using Toybox.Communications as Comm;
using Toybox.System as Sys;
using Toybox.WatchUi as Ui;

/**
 * Entry point for the ClimbPro datafield.
 * Initializes the data store and registers the phone message listener.
 */
class ClimbProApp extends App.AppBase {

    var climbData;
    hidden var msgCallback;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        climbData = new ClimbData();
        climbData.initialize(); // belangrijk!

        msgCallback = new PhoneMessageCallback();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));

        Sys.println("ClimbPro: started, listening for phone messages");
    }

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg != null && msg.data != null) {
            msgCallback.onMessage(msg.data);
            Ui.requestUpdate();
        }
    }

    function onStop(state) {
        Sys.println("ClimbPro: stopped");
    }

    function getInitialView() {
        return [ new ClimbProView() ];
    }
}
