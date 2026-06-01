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

        // =========================
        // 🧪 DEV FAKE DATA (REMOVE LATER)
        // =========================

        var fakeMsg = {
            "v" => 1,
            "mode" => "route",
            "routeId" => "dev",
            "name" => "Dev Route",
            "climbs" => [
                {
                    "startDistance" => 500,
                    "endDistance" => 16500,
                    "length" => 16000,
                    "elevationGain" => 720,
                    "avgGradient" => 72,
                    "name" => "Mega Alpine Test Climb",

                    "segments" => [

                        {
                            "distance" => 500,
                            "elevationGain" => 10,
                            "gradient" => 20,
                            "colorIndex" => 0
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 20,
                            "gradient" => 50,
                            "colorIndex" => 1
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 50,
                            "gradient" => 83,
                            "colorIndex" => 3
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 60,
                            "gradient" => 133,
                            "colorIndex" => 5
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 30,
                            "gradient" => 43,
                            "colorIndex" => 1
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 55,
                            "gradient" => 110,
                            "colorIndex" => 4
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 5,
                            "gradient" => 17,
                            "colorIndex" => 0
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 70,
                            "gradient" => 108,
                            "colorIndex" => 4
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 80,
                            "gradient" => 145,
                            "colorIndex" => 5
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 25,
                            "gradient" => 56,
                            "colorIndex" => 2
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 15,
                            "gradient" => 30,
                            "colorIndex" => 0
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 90,
                            "gradient" => 129,
                            "colorIndex" => 5
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 40,
                            "gradient" => 73,
                            "colorIndex" => 3
                        },


                        {
                            "distance" => 500,
                            "elevationGain" => 50,
                            "gradient" => 91,
                            "colorIndex" => 3
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 22,
                            "gradient" => 34,
                            "colorIndex" => 0
                        },

                        {
                            "distance" => 500,
                            "elevationGain" => 120,
                            "gradient" => 133,
                            "colorIndex" => 5
                        }
                    ]
                }
            ]
        };

        msgCallback.onMessage(fakeMsg);
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
