using Toybox.Application as App;
using Toybox.Application.Storage as Storage;
using Toybox.Communications as Comm;
using Toybox.System as Sys;
using Toybox.WatchUi as Ui;

class SurfaceFieldApp extends App.AppBase {

    var surfaceData;

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        surfaceData = new SurfaceData();
        Comm.registerForPhoneAppMessages(method(:onPhoneMessage));

        var saved = Storage.getValue("surface_payload");
        if (saved != null) {
            surfaceData.parse(saved);
        }
        Sys.println("SurfaceField: started");
    }

    function onPhoneMessage(msg as Comm.PhoneAppMessage) as Void {
        if (msg == null || msg.data == null) { return; }
        if (surfaceData.parse(msg.data)) {
            try {
                Storage.setValue("surface_payload", msg.data);
            } catch (e) {
                Sys.println("SurfaceField: payload too large to persist");
            }
            Ui.requestUpdate();
        }
    }

    function onStop(state) {}

    function getInitialView() {
        return [ new SurfaceFieldView() ];
    }
}
