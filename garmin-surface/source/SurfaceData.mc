using Toybox.System as Sys;

// Parallel-array store for the user-defined surface sections of the active
// route. Filled from the phone payload's packed "surfSec" triples:
// [startDistance, endDistance, surfaceType, ...].
class SurfaceData {

    const MAX_SECTIONS = 32;

    var payloadReceived = false;
    var routeId = null;
    var routeName = null;

    var count = 0;
    var secStart;   // metres from route start
    var secEnd;     // metres from route start
    var secType;    // SurfaceType constant 0..5

    // Runtime state, refreshed by updateProgress()
    var currentIdx = -1;        // section the rider is in (-1 = none)
    var nextIdx = -1;           // first section ahead (-1 = none)
    var remainingInSection = 0; // metres left in current section
    var distToNext = -1;        // metres to next section start

    function initialize() {
        secStart = new [MAX_SECTIONS];
        secEnd   = new [MAX_SECTIONS];
        secType  = new [MAX_SECTIONS];
    }

    // Parses a phone payload dictionary. Returns true when it carried surfSec.
    function parse(msg) {
        if (!(msg instanceof Toybox.Lang.Dictionary)) { return false; }
        var version = msg.get("v");
        if (version == null || version != 3) {
            Sys.println("SurfaceData: unsupported version " + version);
            return false;
        }
        var surfSec = msg.get("surfSec");
        if (!(surfSec instanceof Toybox.Lang.Array)) { return false; }

        routeId   = msg.get("routeId");
        routeName = msg.get("name");

        var n = surfSec.size() / 3;
        if (n > MAX_SECTIONS) { n = MAX_SECTIONS; }
        count = n;
        for (var i = 0; i < n; i++) {
            secStart[i] = surfSec[i * 3];
            secEnd[i]   = surfSec[i * 3 + 1];
            var t = surfSec[i * 3 + 2];
            secType[i]  = (t instanceof Toybox.Lang.Number && t >= 0 && t <= 5) ? t : 5;
        }
        payloadReceived = true;
        currentIdx = -1;
        nextIdx = -1;
        Sys.println("SurfaceData: " + count + " sections");
        return true;
    }

    // elapsed = activity elapsedDistance in metres. Sections are sorted by
    // startDistance (the phone keeps them sorted), so the first section that
    // starts beyond the rider is "next".
    function updateProgress(elapsed) {
        currentIdx = -1;
        nextIdx = -1;
        remainingInSection = 0;
        distToNext = -1;
        for (var i = 0; i < count; i++) {
            if (elapsed >= secStart[i] && elapsed < secEnd[i]) {
                currentIdx = i;
                remainingInSection = secEnd[i] - elapsed;
            } else if (secStart[i] > elapsed) {
                nextIdx = i;
                distToNext = secStart[i] - elapsed;
                break;
            }
        }
    }
}
