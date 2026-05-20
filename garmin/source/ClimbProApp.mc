using Toybox.Application as App;
using Toybox.WatchUi as Ui;

// Entry point for the ClimbPro datafield.
// Phase 0 scaffold — returns an empty view. Real views land in Phase 6.
class ClimbProApp extends App.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
    }

    function onStop(state) {
    }

    function getInitialView() {
        return [ new ClimbProView() ];
    }
}
