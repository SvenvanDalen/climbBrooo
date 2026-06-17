using Toybox.System as Sys;
using Toybox.Math as Math;

// Module-level so SurfaceFieldView can size alertedSec[] to match without
// duplicating the magic number.
const SURFACE_MAX_SECTIONS = 32;

// Parallel-array store for the user-defined surface sections of the active
// route. Filled from the phone payload's 'surfSec' object array:
// [{s:startDistance, e:endDistance, t:surfaceType, n:name?, cp:[dist,latInt,lonInt,...]}, ...].
// Distance is the primary matching axis; GPS checkpoints in 'cp' are used to
// apply a smoothed distanceOffset that corrects for sensor drift.
class SurfaceData {

    const MAX_SECTIONS = SURFACE_MAX_SECTIONS;
    const MAX_CP = 256;
    const SNAP_M = 40;               // only snap when a checkpoint is within this many metres
    const GPS_SUBPIECE_SNAP_M = 75;  // radius voor GPS-gebaseerde deel-stuk detectie
    const SUBPIECE_FRACTION_PCT = 8; // elk deel-stuk = 8% van de stuk-lengte (zoals klimmen)
    const MAX_SUBPIECES = 16;        // 13 nominaal (ceil 100/8) + marge voor floor-afronding op korte stukken

    var payloadReceived = false;
    var routeId = null;
    var routeName = null;

    var count = 0;
    var secStart;   // metres from route start
    var secEnd;     // metres from route start
    var secType;    // SurfaceType constant 0..5
    var secName;    // String or null per section

    // Flat checkpoint store, shared across all sections.
    var cpDist;     // metres from route start
    var cpLat;      // degrees * 100000
    var cpLon;      // degrees * 100000
    var secCpOff;   // first cp index for section i
    var secCpCnt;   // cp count for section i
    var totalCp = 0;
    var distanceOffset = 0;  // smoothed GPS correction, metres

    // Runtime state, refreshed by updateProgress()
    var currentIdx = -1;        // section the rider is in (-1 = none)
    var nextIdx = -1;           // first section ahead (-1 = none)
    var remainingInSection = 0; // metres left in current section
    var distToNext = -1;        // metres to next section start
    var subPieceCount = 0;      // deel-stukken in het huidige stuk (0 = niet in een stuk)
    var currentSubPiece = -1;   // 0-based index van het deel-stuk waarin de rijder zit
    var subPieceLen = 0;        // meters per deel-stuk in het huidige stuk

    function initialize() {
        secStart = new [MAX_SECTIONS];
        secEnd   = new [MAX_SECTIONS];
        secType  = new [MAX_SECTIONS];
        secName  = new [MAX_SECTIONS];
        secCpOff = new [MAX_SECTIONS];
        secCpCnt = new [MAX_SECTIONS];
        cpDist   = new [MAX_CP];
        cpLat    = new [MAX_CP];
        cpLon    = new [MAX_CP];
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

        var n = surfSec.size();
        if (n > MAX_SECTIONS) { n = MAX_SECTIONS; }
        count = n;
        totalCp = 0;
        distanceOffset = 0;
        for (var i = 0; i < n; i++) {
            var sec = surfSec[i];
            if (!(sec instanceof Toybox.Lang.Dictionary)) {
                secStart[i] = 0; secEnd[i] = 0; secType[i] = 5;
                secName[i] = null; secCpOff[i] = totalCp; secCpCnt[i] = 0;
                continue;
            }
            secStart[i] = numOr(sec.get("s"), 0);
            secEnd[i]   = numOr(sec.get("e"), 0);
            var t = sec.get("t");
            secType[i]  = (t instanceof Toybox.Lang.Number && t >= 0 && t <= 5) ? t : 5;
            secName[i]  = sec.get("n");   // String or null

            secCpOff[i] = totalCp;
            secCpCnt[i] = 0;
            var cp = sec.get("cp");
            if (cp instanceof Toybox.Lang.Array) {
                var triples = cp.size() / 3;
                for (var k = 0; k < triples && totalCp < MAX_CP; k++) {
                    cpDist[totalCp] = numOr(cp[k * 3], 0);
                    cpLat[totalCp]  = numOr(cp[k * 3 + 1], 0);
                    cpLon[totalCp]  = numOr(cp[k * 3 + 2], 0);
                    totalCp++;
                    secCpCnt[i]++;
                }
            }
        }
        payloadReceived = true;
        currentIdx = -1;
        nextIdx = -1;
        Sys.println("SurfaceData: " + count + " sections, " + totalCp + " checkpoints");
        return true;
    }

    // posDegrees = [lat, lon] in decimal degrees, or null when no GPS fix.
    // Returns elapsed corrected by a smoothed offset snapped to the nearest
    // checkpoint within SNAP_M. Distance stays the primary matching axis.
    function correctElapsed(elapsed, posDegrees) {
        if (posDegrees == null || totalCp == 0) { return elapsed + distanceOffset; }
        var lat = (posDegrees[0] * 100000).toNumber();
        var lon = (posDegrees[1] * 100000).toNumber();
        // Hoist the cosine term: it depends only on the rider's latitude, which
        // is constant across all checkpoint comparisons in this call.
        var meanLatRad = (lat / 100000.0) * 0.0174533;
        var cosLat = Math.cos(meanLatRad);
        var bestM = SNAP_M + 1;
        var bestDist = -1;
        for (var i = 0; i < totalCp; i++) {
            var m = approxMeters(lat, lon, cpLat[i], cpLon[i], cosLat);
            if (m < bestM) { bestM = m; bestDist = cpDist[i]; }
        }
        if (bestDist >= 0) {
            var raw = bestDist - elapsed;
            // Float arithmetic so sub-metre corrections accumulate instead of
            // being truncated by integer division.
            distanceOffset = (((distanceOffset * 3.0) + raw) / 4.0).toNumber();
        }
        return elapsed + distanceOffset;
    }

    // Equirectangular approximation. Inputs are degrees * 100000.
    // cosLat must be Math.cos(latA_in_radians) — precomputed by the caller.
    hidden function approxMeters(latA, lonA, latB, lonB, cosLat) {
        var dLat = (latA - latB) * 0.011132;                  // 1.1132 m per 1e-5 deg
        var dLon = (lonA - lonB) * 0.011132 * cosLat;
        return Math.sqrt((dLat * dLat) + (dLon * dLon));
    }

    hidden function numOr(v, fallback) {
        if (v instanceof Toybox.Lang.Number) { return v; }
        if (v instanceof Toybox.Lang.Float)  { return v.toNumber(); }
        if (v instanceof Toybox.Lang.Long)   { return v.toNumber(); }
        return fallback;
    }

    // elapsed = activity elapsedDistance in metres (already corrected by correctElapsed).
    // Sections are sorted by startDistance (phone keeps them sorted).
    function updateProgress(elapsed) {
        currentIdx = -1;
        nextIdx = -1;
        remainingInSection = 0;
        distToNext = -1;
        subPieceCount = 0;
        currentSubPiece = -1;
        subPieceLen = 0;
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

        // Deel-stuk-voortgang: splits het huidige stuk in deel-stukken van 8% van de
        // stuk-lengte (zoals klimmen) en bepaal in welk deel-stuk de rijder zit.
        if (currentIdx >= 0) {
            var secLen = secEnd[currentIdx] - secStart[currentIdx];
            if (secLen > 0) {
                subPieceLen = (secLen * SUBPIECE_FRACTION_PCT) / 100;  // integer floor van 8%
                if (subPieceLen < 1) { subPieceLen = 1; }
                subPieceCount = (secLen + subPieceLen - 1) / subPieceLen;  // ceil
                if (subPieceCount > MAX_SUBPIECES) { subPieceCount = MAX_SUBPIECES; }
                var into = elapsed - secStart[currentIdx];
                var idx = into / subPieceLen;
                if (idx > subPieceCount - 1) { idx = subPieceCount - 1; }
                currentSubPiece = idx;
            }
        }
    }

    // Verfijnt currentSubPiece op basis van GPS zodra een checkpoint binnen de actieve
    // sectie binnen GPS_SUBPIECE_SNAP_M meter ligt. Overschrijft de afstandsgebaseerde
    // waarde van updateProgress() — alleen aanroepen ná updateProgress().
    //
    // Waarom alleen binnen de eigen sectie zoeken: checkpoints van aangrenzende secties
    // liggen vlak bij de stuk-grenzen; die zouden het deel-stuk prematuur laten omslaan.
    function refineSubPieceByGPS(posDegrees) {
        if (currentIdx < 0 || subPieceCount <= 0 || subPieceLen <= 0) { return; }
        if (posDegrees == null) { return; }

        var lat = (posDegrees[0] * 100000).toNumber();
        var lon = (posDegrees[1] * 100000).toNumber();
        var cosLat = Math.cos((lat / 100000.0) * 0.0174533);

        var off = secCpOff[currentIdx];
        var cnt = secCpCnt[currentIdx];
        if (cnt <= 0) { return; }

        // Dichtstbijzijnde checkpoint binnen de actieve sectie.
        var bestM    = GPS_SUBPIECE_SNAP_M + 1;
        var bestDist = -1;
        for (var k = 0; k < cnt; k++) {
            var i = off + k;
            var m = approxMeters(lat, lon, cpLat[i], cpLon[i], cosLat);
            if (m < bestM) { bestM = m; bestDist = cpDist[i]; }
        }

        if (bestDist < 0) { return; }  // geen checkpoint dichtbij genoeg

        var into = bestDist - secStart[currentIdx];
        if (into < 0) { into = 0; }
        var gpsIdx = into / subPieceLen;
        if (gpsIdx >= subPieceCount) { gpsIdx = subPieceCount - 1; }
        currentSubPiece = gpsIdx;
    }
}
