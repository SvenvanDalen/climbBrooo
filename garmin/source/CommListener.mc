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
        var rtl = msg.get("rtl");
        data.routeTotalLen = (rtl != null && rtl instanceof Toybox.Lang.Number) ? rtl : 0;
        // Optional route-level hazard markers "hz" (issue #203): tunnels + technical descents.
        // Replaced (or cleared) on every payload; anything malformed is dropped entirely.
        data.hazards = parseHazards(msg.get("hz"));
        // Optional display units (issue #262); absent/invalid = metric, reset on every payload.
        data.units = Units.parseFlags(msg.get("un"));

        // Everesting attempt (issue #217): cleared on every payload, set by the first climb
        // that carries a valid "ev".
        data.everest = null;
        data.everestClimb = -1;

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
        data.resetNavTrust();
        for (var i = 0; i < data.climbCount; i++) {
            data.calibIdx[i] = 0;
            data.climbEntered[i] = false;
            data.climbSkipped[i] = false;
        }
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
        data.setAnchors(idx, getInt(climbDict, "sd", 0), getInt(climbDict, "ed", 0));
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

        // Optional refsec array: [prSeconds, ...] one int per segment (parallel to segs).
        // Per-segment PR reference time — distinct from tsec, may be present alongside it.
        var refsec = climbDict.get("refsec");
        if (refsec != null && refsec instanceof Toybox.Lang.Array && refsec.size() >= data.segCount[idx]
                && data.segCount[idx] > 0) {
            data.hasRefTargets[idx] = true;
            for (var s = 0; s < data.segCount[idx]; s++) {
                var rv = refsec[s];
                data.segRefSec[idx][s] =
                    (rv instanceof Toybox.Lang.Number) ? rv.toNumber() : 0;
            }
        } else {
            data.hasRefTargets[idx] = false;
        }

        // Optional vam array: [avgVamMPerH, peakVamMPerH, ...] 2 ints per segment (parallel to segs)
        var vam = climbDict.get("vam");
        if (vam != null && vam instanceof Toybox.Lang.Array && vam.size() >= data.segCount[idx] * 2
                && data.segCount[idx] > 0) {
            data.hasVam[idx] = true;
            for (var s = 0; s < data.segCount[idx]; s++) {
                var av = vam[s * 2];
                var pv = vam[s * 2 + 1];
                data.segVamAvg[idx][s]  = (av instanceof Toybox.Lang.Number) ? av.toNumber() : 0;
                data.segVamPeak[idx][s] = (pv instanceof Toybox.Lang.Number) ? pv.toNumber() : 0;
            }
        } else {
            data.hasVam[idx] = false;
        }

        // Optional interval block "ib" (issue #180): [targetWatts, lowWatts, highWatts],
        // computed on the phone from % FTP. Anything malformed disables the block.
        data.hasBlock[idx] = false;
        var ib = climbDict.get("ib");
        if (ib != null && ib instanceof Toybox.Lang.Array && ib.size() >= 3
                && ib[0] instanceof Toybox.Lang.Number && ib[1] instanceof Toybox.Lang.Number
                && ib[2] instanceof Toybox.Lang.Number
                && ib[1] > 0 && ib[1] <= ib[2]) {
            data.hasBlock[idx]    = true;
            data.blockTarget[idx] = ib[0];
            data.blockLow[idx]    = ib[1];
            data.blockHigh[idx]   = ib[2];
        }

        // Optional Everesting attempt "ev" (issue #217): [targetM, repeats, startLatInt,
        // startLonInt, topLatInt, topLonInt]. Only the first climb carrying one is used.
        if (data.everest == null) {
            var ev = parseEverest(climbDict.get("ev"));
            if (ev != null) {
                data.everest = ev;
                data.everestClimb = idx;
            }
        }

        // Optional zc array (issue #66): FTP intensity-zone color index per segment, parallel
        // to segs. Replaced (or cleared to null) on every payload so a resync without zc
        // can't leave stale zones behind.
        data.segZone[idx] = parseZoneColors(climbDict.get("zc"), data.segColor[idx], data.segCount[idx]);
    }

    // Returns a segCnt-long Array of color indices from zc, or null when zc is absent,
    // not an Array, or shorter than the segment count (then the climb has no zones and the
    // renderers use the gradient colors). An entry outside 0-5 falls back to that
    // segment's gradient color instead of discarding the whole array.
    hidden function parseZoneColors(zc, gradColors, segCnt) {
        if (zc == null || !(zc instanceof Toybox.Lang.Array) || segCnt <= 0 || zc.size() < segCnt) {
            return null;
        }
        var out = new [segCnt];
        for (var s = 0; s < segCnt; s++) {
            var zv = zc[s];
            if (zv instanceof Toybox.Lang.Number && zv.toNumber() >= 0 && zv.toNumber() <= 5) {
                out[s] = zv.toNumber();
            } else {
                out[s] = gradColors[s];
            }
        }
        return out;
    }

    // "ev" -> [targetM, repeats, startLat, startLon, topLat, topLon] (degrees as Float), or
    // null when absent or malformed (not 6 Numbers, target/repeats <= 0, or a (0,0) coordinate).
    function parseEverest(ev) {
        if (ev == null || !(ev instanceof Toybox.Lang.Array) || ev.size() < 6) {
            return null;
        }
        for (var i = 0; i < 6; i++) {
            if (!(ev[i] instanceof Toybox.Lang.Number)) { return null; }
        }
        if (ev[0] <= 0 || ev[1] <= 0) { return null; }
        if ((ev[2] == 0 && ev[3] == 0) || (ev[4] == 0 && ev[5] == 0)) { return null; }
        return [ev[0], ev[1],
                ev[2].toFloat() / 100000.0f, ev[3].toFloat() / 100000.0f,
                ev[4].toFloat() / 100000.0f, ev[5].toFloat() / 100000.0f];
    }

    hidden function getInt(dict, key, defaultVal) {
        var val = dict.get(key);
        if (val != null && val instanceof Toybox.Lang.Number) {
            return val;
        }
        return defaultVal;
    }
}
