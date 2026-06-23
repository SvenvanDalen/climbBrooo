using Toybox.Communications as Comm;
using Toybox.Application as App;
using Toybox.System as Sys;

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

class PhoneMessageCallback {

    function initialize() {
    }

    function onMessage(msg) {
        if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) {
            Sys.println("CommListener: null or invalid message");
            return;
        }

        // Dispatch on optional type field
        var msgType = msg.get("type");
        if (msgType instanceof Toybox.Lang.String) {
            if (msgType.equals("ROUTE_LIST")) {
                handleRouteList(msg);
            } else if (msgType.equals("ACTIVE_SET")) {
                App.getApp().activeAck = msg;
            } else if (msgType.equals("HELLO")) {
                handleHello();
            } else {
                Sys.println("CommListener: unknown type: " + msgType);
            }
            return;
        }

        // No type → v3 route payload
        var version = msg.get("v");
        if (version == null || version != 3) {
            Sys.println("CommListener: unsupported version " + version);
            return;
        }

        // Save raw payload so views can persist it to storage
        App.getApp().lastReceivedPayload = msg;

        var data = App.getApp().climbData;
        if (data == null) { return; }

        data.mode      = msg.get("mode");
        data.routeId   = msg.get("routeId");
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

        // Parse fss (flat starred segments). Resets internally so a resync without
        // fss clears stale entries — mirrors the surf reset discipline above.
        data.parseFlatStarred(msg.get("fss"));

        data.payloadReceived = true;
        for (var i = 0; i < data.climbCount; i++) { data.calibIdx[i] = 0; }
        Sys.println("CommListener: v3 parsed, " + data.climbCount + " climbs, " + data.flatStarredCount + " starred");
    }

    // Phone (re)connected — possibly a fresh install. Re-request the route list so the
    // widget's "Telefoon" section refreshes without the user reopening the sync screen.
    hidden function handleHello() {
        Sys.println("CommListener: HELLO from phone, re-requesting route list");
        App.getApp().phoneRouteIndex.received = false;
        Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
    }

    hidden function handleRouteList(msg) {
        var index = App.getApp().phoneRouteIndex;
        if (index == null) { return; }
        index.populate(msg.get("routes"));
    }

    hidden function parseClimb(data, idx, climbDict) {
        if (climbDict == null || !(climbDict instanceof Toybox.Lang.Dictionary)) {
            return;
        }

        data.climbStartDist[idx] = getInt(climbDict, "sd",  0);
        data.climbEndDist[idx]   = getInt(climbDict, "ed",  0);
        data.climbLength[idx]    = getInt(climbDict, "len", 0);
        data.climbElevGain[idx]  = getInt(climbDict, "eg",  0);
        data.climbAvgGrad[idx]   = getInt(climbDict, "ag",  0);
        data.climbName[idx]      = climbDict.get("n");

        var slatInt = climbDict.get("slat");
        var slonInt = climbDict.get("slon");
        data.climbStartLat[idx] = (slatInt != null && slatInt instanceof Toybox.Lang.Number)
            ? (slatInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;
        data.climbStartLon[idx] = (slonInt != null && slonInt instanceof Toybox.Lang.Number)
            ? (slonInt as Toybox.Lang.Number).toFloat() / 100000.0f : 0.0f;

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
    }

    hidden function getInt(dict, key, defaultVal) {
        var val = dict.get(key);
        if (val != null && val instanceof Toybox.Lang.Number) {
            return val;
        }
        return defaultVal;
    }
}
