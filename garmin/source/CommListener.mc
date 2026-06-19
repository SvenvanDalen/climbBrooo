using Toybox.Communications as Comm;
using Toybox.Application as App;
using Toybox.System as Sys;
using Toybox.Application.Storage as Storage;

/**
 * Listens for payloads from the Android companion app via the
 * Connect IQ Communications API. Parses the incoming dictionary
 * and updates the shared ClimbData store.
 */
class CommListener extends Comm.ConnectionListener {

    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() {
        Sys.println("CommListener: connection complete");
    }

    function onError() {
        Sys.println("CommListener: connection error");
    }
}

/**
 * Handles incoming messages (phone → watch).
 * The phone sends a Dictionary matching protocol/schema.json.
 */
class PhoneMessageCallback {

    function initialize() {
    }

    /**
     * Called by the system when a message arrives from the companion app.
     * msg is a Dictionary with keys: "v", "mode", "routeId", "name", "climbs".
     */
    function onMessage(msg) {
        if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) {
            Sys.println("CommListener: null or invalid message");
            return;
        }

        var data = App.getApp().climbData;
        if (data == null) {
            return;
        }

        var version = msg.get("v");
        if (version == null || version != 3) {
            Sys.println("CommListener: unsupported version " + version);
            return;
        }

        data.mode = msg.get("mode");
        data.routeId = msg.get("routeId");
        data.routeName = msg.get("name");

        var climbs = msg.get("climbs");
        if (climbs != null && climbs instanceof Toybox.Lang.Array) {
            var max = data.MAX_CLIMBS < climbs.size() ? data.MAX_CLIMBS : climbs.size();
            data.climbCount = max;
            for (var i = 0; i < max; i++) {
                parseClimb(data, i, climbs[i]);
            }
        } else {
            data.climbCount = 0;
        }

        data.payloadReceived = true;
        for (var i = 0; i < data.climbCount; i++) { data.calibIdx[i] = 0; }
        Sys.println("CommListener: payload parsed, " + data.climbCount + " climbs");
        try {
            Storage.setValue("active_payload", msg);
        } catch (e) {
            Sys.println("CommListener: payload too large to persist");
        }
    }

    hidden function parseClimb(data, idx, climbDict) {
        if (climbDict == null || !(climbDict instanceof Toybox.Lang.Dictionary)) {
            return;
        }

        // Short keys (v3 format)
        data.climbStartDist[idx] = getInt(climbDict, "sd",  0);
        data.climbEndDist[idx]   = getInt(climbDict, "ed",  0);
        data.climbLength[idx]    = getInt(climbDict, "len", 0);
        data.climbElevGain[idx]  = getInt(climbDict, "eg",  0);
        data.climbAvgGrad[idx]   = getInt(climbDict, "ag",  0);
        data.climbName[idx]      = climbDict.get("n");

        // Radius mode: lat/lon as ints (degrees × 100000)
        var slatInt = climbDict.get("slat");
        var slonInt = climbDict.get("slon");
        data.climbStartLat[idx] = (slatInt != null && slatInt instanceof Toybox.Lang.Number)
            ? (slatInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;
        data.climbStartLon[idx] = (slonInt != null && slonInt instanceof Toybox.Lang.Number)
            ? (slonInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;

        // Flat segs array: [dist, elevGain, gradient, colorIndex, ...] 4 ints × segCount
        var segs = climbDict.get("segs");
        if (segs != null && segs instanceof Toybox.Lang.Array && segs.size() >= 4) {
            var segCount = segs.size() / 4;
            if (segCount > data.MAX_SEGMENTS) { segCount = data.MAX_SEGMENTS; }
            data.segCount[idx] = segCount;
            for (var s = 0; s < segCount; s++) {
                data.segDist[idx][s]     = segs[s * 4];
                data.segElevGain[idx][s] = segs[s * 4 + 1];
                data.segGradient[idx][s] = segs[s * 4 + 2];
                data.segColor[idx][s]    = segs[s * 4 + 3];
            }
        } else {
            data.segCount[idx] = 0;
        }

        // Flat calib array: [distFromStart, latInt, lonInt, ...] 3 ints × calibCount
        var calib = climbDict.get("calib");
        if (calib != null && calib instanceof Toybox.Lang.Array && calib.size() >= 3) {
            var calibCount = calib.size() / 3;
            if (calibCount > data.MAX_CALIB) { calibCount = data.MAX_CALIB; }
            data.calibCount[idx] = calibCount;
            for (var k = 0; k < calibCount; k++) {
                data.calibDist[idx][k] = calib[k * 3];
                data.calibLat[idx][k]  = (calib[k * 3 + 1] as Toybox.Lang.Number).toFloat() / 100000.0f;
                data.calibLon[idx][k]  = (calib[k * 3 + 2] as Toybox.Lang.Number).toFloat() / 100000.0f;
            }
        } else {
            data.calibCount[idx] = 0;
        }

        // Optional surf array: [surfaceType, ...] one int per segment (parallel to segs)
        // Reset every segment to UNKNOWN first so a re-sync with a shorter surf
        // array can't leave stale values from the previous payload.
        var segCnt = data.segCount[idx];
        for (var s = 0; s < segCnt; s++) { data.segSurf[idx][s] = 5; }
        var surf = climbDict.get("surf");
        if (surf != null && surf instanceof Toybox.Lang.Array) {
            var surfSize = surf.size();
            for (var s = 0; s < segCnt && s < surfSize; s++) {
                var sv = surf[s];
                if (sv instanceof Toybox.Lang.Number && sv.toNumber() >= 0 && sv.toNumber() <= 5) {
                    data.segSurf[idx][s] = sv.toNumber();
                }
            }
        }

        // Optional tsec array: [targetSeconds, ...] one int per segment (parallel to segs)
        var tsec = climbDict.get("tsec");
        if (tsec != null && tsec instanceof Toybox.Lang.Array && tsec.size() >= data.segCount[idx]
                && data.segCount[idx] > 0) {
            data.hasTargets[idx] = true;
            for (var s = 0; s < data.segCount[idx]; s++) {
                var tv = tsec[s];
                data.segTargetSec[idx][s] =
                    (tv instanceof Toybox.Lang.Number) ? tv.toNumber() : 0;
            }
        } else {
            data.hasTargets[idx] = false;
        }
    }

    hidden function getInt(dict, key, defaultVal) {
        var val = dict.get(key);
        if (val != null && val instanceof Toybox.Lang.Number) {
            return val;
        }
        return defaultVal;
    }
}
