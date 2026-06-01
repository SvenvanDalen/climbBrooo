using Toybox.Communications as Comm;
using Toybox.Application as App;
using Toybox.System as Sys;

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
        if (version == null || version != 1) {
            Sys.println("CommListener: unsupported version " + version);
            return;
        }

        data.mode = msg.get("mode");
        data.routeId = msg.get("routeId");
        data.routeName = msg.get("name");

        var climbs = msg.get("climbs");
        if (climbs != null && climbs instanceof Toybox.Lang.Array) {
            data.climbCount = climbs.size();
            // Store up to MAX_CLIMBS
            var max = data.MAX_CLIMBS < climbs.size() ? data.MAX_CLIMBS : climbs.size();
            for (var i = 0; i < max; i++) {
                parseClimb(data, i, climbs[i]);
            }
        } else {
            data.climbCount = 0;
        }

        data.payloadReceived = true;
        Sys.println("CommListener: payload parsed, " + data.climbCount + " climbs");
    }

    hidden function parseClimb(data, idx, climbDict) {
        if (climbDict == null || !(climbDict instanceof Toybox.Lang.Dictionary)) {
            return;
        }

        data.climbStartDist[idx] = getInt(climbDict, "startDistance", 0);
        data.climbEndDist[idx] = getInt(climbDict, "endDistance", 0);
        data.climbLength[idx] = getInt(climbDict, "length", 0);
        data.climbElevGain[idx] = getInt(climbDict, "elevationGain", 0);
        data.climbAvgGrad[idx] = getInt(climbDict, "avgGradient", 0);
        data.climbName[idx] = climbDict.get("name");

        // Radius mode coordinates
        var lat = climbDict.get("startLat");
        var lon = climbDict.get("startLon");
        if (lat != null && lat instanceof Toybox.Lang.Float) {
            data.climbStartLat[idx] = lat;
        } else if (lat != null && lat instanceof Toybox.Lang.Number) {
            data.climbStartLat[idx] = (lat as Toybox.Lang.Number).toFloat();
        } else {
            data.climbStartLat[idx] = 0.0;
        }
        if (lon != null && lon instanceof Toybox.Lang.Float) {
            data.climbStartLon[idx] = lon;
        } else if (lon != null && lon instanceof Toybox.Lang.Number) {
            data.climbStartLon[idx] = (lon as Toybox.Lang.Number).toFloat();
        } else {
            data.climbStartLon[idx] = 0.0;
        }

        // Parse segments
        var segs = climbDict.get("segments");
        if (segs != null && segs instanceof Toybox.Lang.Array) {
            var segCount = segs.size();
            if (segCount > data.MAX_SEGMENTS) {
                segCount = data.MAX_SEGMENTS;
            }
            data.segCount[idx] = segCount;
            for (var s = 0; s < segCount; s++) {
                var seg = segs[s];
                if (seg != null && seg instanceof Toybox.Lang.Dictionary) {
                    data.segDist[idx][s] = getInt(seg, "distance", 0);
                    data.segElevGain[idx][s] = getInt(seg, "elevationGain", 0);
                    data.segGradient[idx][s] = getInt(seg, "gradient", 0);
                    data.segColor[idx][s] = getInt(seg, "colorIndex", 0);
                }
            }
        } else {
            data.segCount[idx] = 0;
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
