using Toybox.Application as App;
using Toybox.System as Sys;

/**
 * Push-only protocol: the watch never transmits, it only receives RAW_HDR/
 * RAW_CHUNK from the phone (triggered by the user tapping "Verstuur naar
 * horloge" on the phone's route-detail screen). See
 * docs/superpowers/specs/2026-07-13-onboard-push-and-terrain-window-design.md.
 */
class OnboardMessageCallback {

    function initialize() {
    }

    function onMessage(msg) {
        if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) { return; }
        var t = msg.get("type");
        if (!(t instanceof Toybox.Lang.String)) { return; }
        var app = App.getApp() as OnboardApp;
        if (app.store == null || app.climbData == null) { return; }

        if (t.equals("RAW_HDR")) {
            app.climbData.reset();
            app.store.beginRoute(msg.get("id"), msg.get("name"),
                                 msg.get("n"), msg.get("tot"));
        } else if (t.equals("RAW_CHUNK")) {
            var ok = app.store.addChunk(msg.get("seq"), msg.get("lat"),
                                        msg.get("lon"), msg.get("ele"));
            if (ok && app.store.complete) {
                RouteParser.parse(app.store, app.climbData);
                app.store.saveToStorage();
                Sys.println("OnboardComm: route parsed, "
                            + app.climbData.climbCount + " climbs");
            }
        } else {
            Sys.println("OnboardComm: unknown type " + t);
        }
    }
}
