using Toybox.Application.Storage as Storage;
using Toybox.Math as Math;
using Toybox.System as Sys;

/**
 * Assembles the raw route point stream from the phone (RAW_HDR + RAW_CHUNK
 * messages) into parallel arrays, computes cumulative distance on the watch,
 * and persists the decoded route so the app is offline-first after one sync.
 * Wire format: lat/lon degrees x 100000, ele decimeters (see protocol/raw-route.md).
 */
class RawRouteStore {

    const MAX_POINTS    = 6000;  // must match RawRoutePayloadBuilder.MAX_RAW_POINTS (~25 m spacing on 150 km)
    const CHUNK_POINTS  = 250;   // must match RawRoutePayloadBuilder.CHUNK_POINTS
    const STORAGE_SLICE = 2000;  // points per Storage value — one big value would exceed CIQ per-value limits

    var routeId = null;
    var routeName = null;
    var pointCount = 0;
    var chunkTotal = 0;
    var chunksReceived = 0;
    var complete = false;

    var lat = null;   // Float degrees
    var lon = null;   // Float degrees
    var ele = null;   // Float meters
    var dist = null;  // Float meters, cumulative from route start

    var chunksSeen = null;

    function initialize() {
    }

    function beginRoute(id, name, n, tot) {
        complete = false;
        chunksReceived = 0;
        routeId = null;
        routeName = null;
        pointCount = 0;
        chunkTotal = 0;
        if (!(n instanceof Toybox.Lang.Number) || n <= 0) { return false; }
        if (!(tot instanceof Toybox.Lang.Number) || tot <= 0) { return false; }
        if (n > MAX_POINTS) { n = MAX_POINTS; }
        routeId = id;
        routeName = name;
        pointCount = n;
        chunkTotal = tot;
        lat = new [n];
        lon = new [n];
        ele = new [n];
        dist = new [n];
        chunksSeen = new [tot];
        for (var i = 0; i < tot; i++) { chunksSeen[i] = false; }
        return true;
    }

    function addChunk(seq, latArr, lonArr, eleArr) {
        if (pointCount <= 0 || !(seq instanceof Toybox.Lang.Number)) { return false; }
        if (seq < 0 || seq >= chunkTotal || chunksSeen[seq]) { return false; }
        if (!(latArr instanceof Toybox.Lang.Array)
                || !(lonArr instanceof Toybox.Lang.Array)
                || !(eleArr instanceof Toybox.Lang.Array)) {
            return false;
        }
        var cnt = latArr.size();
        if (lonArr.size() < cnt) { cnt = lonArr.size(); }
        if (eleArr.size() < cnt) { cnt = eleArr.size(); }
        var base = seq * CHUNK_POINTS;
        for (var i = 0; i < cnt; i++) {
            var p = base + i;
            if (p >= pointCount) { break; }
            lat[p] = latArr[i].toFloat() / 100000.0;
            lon[p] = lonArr[i].toFloat() / 100000.0;
            ele[p] = eleArr[i].toFloat() / 10.0;
        }
        chunksSeen[seq] = true;
        chunksReceived++;
        if (chunksReceived >= chunkTotal) {
            finalizeRoute();
        }
        return true;
    }

    // Computes cumulative distance from the decoded coordinates and marks the
    // route complete. Any point a dropped chunk left null would crash the math,
    // so this only runs when every chunk has arrived (or from tests/restore).
    function finalizeRoute() {
        if (pointCount <= 0) { return; }
        dist[0] = 0.0;
        var d = 0.0;
        for (var i = 1; i < pointCount; i++) {
            d += distM(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            dist[i] = d;
        }
        complete = true;
    }

    // Flat-Earth approximation in meters — same formula the datafield uses.
    function distM(lat1, lon1, lat2, lon2) {
        var dlat = lat1 - lat2;
        var dlon = lon1 - lon2;
        var cosLat = Math.cos(lat1 * Math.PI / 180.0);
        return Math.sqrt((dlat * 111111.0) * (dlat * 111111.0)
                       + (dlon * 111111.0 * cosLat) * (dlon * 111111.0 * cosLat));
    }

    function totalLen() {
        if (!complete || pointCount <= 0) { return 0; }
        return dist[pointCount - 1];
    }

    // Persist in slices of STORAGE_SLICE points per key: 6000-point arrays as a
    // single value (~90 KB) would blow the per-value Storage limit; a 2000-point
    // Float slice is ~10 KB.
    function saveToStorage() {
        if (!complete) { return; }
        Storage.setValue("onb_raw_meta", {
            "id"   => routeId,
            "name" => routeName,
            "n"    => pointCount
        });
        var slices = sliceCount(pointCount);
        for (var k = 0; k < slices; k++) {
            var from = k * STORAGE_SLICE;
            var to = from + STORAGE_SLICE;
            if (to > pointCount) { to = pointCount; }
            Storage.setValue("onb_raw_lat_" + k, lat.slice(from, to));
            Storage.setValue("onb_raw_lon_" + k, lon.slice(from, to));
            Storage.setValue("onb_raw_ele_" + k, ele.slice(from, to));
        }
    }

    function restoreFromStorage() {
        var meta = Storage.getValue("onb_raw_meta");
        if (!(meta instanceof Toybox.Lang.Dictionary)) { return false; }
        var n = meta.get("n");
        if (!(n instanceof Toybox.Lang.Number) || n <= 0 || n > MAX_POINTS) { return false; }
        var la = new [n];
        var lo = new [n];
        var el = new [n];
        var slices = sliceCount(n);
        var pos = 0;
        for (var k = 0; k < slices; k++) {
            var sLat = Storage.getValue("onb_raw_lat_" + k);
            var sLon = Storage.getValue("onb_raw_lon_" + k);
            var sEle = Storage.getValue("onb_raw_ele_" + k);
            if (!(sLat instanceof Toybox.Lang.Array) || !(sLon instanceof Toybox.Lang.Array)
                    || !(sEle instanceof Toybox.Lang.Array)) {
                return false;
            }
            if (sLon.size() < sLat.size() || sEle.size() < sLat.size()) { return false; }
            for (var i = 0; i < sLat.size() && pos < n; i++) {
                la[pos] = sLat[i];
                lo[pos] = sLon[i];
                el[pos] = sEle[i];
                pos++;
            }
        }
        if (pos < n) { return false; }
        routeId = meta.get("id");
        routeName = meta.get("name");
        pointCount = n;
        chunkTotal = 1;
        chunksReceived = 1;
        lat = la;
        lon = lo;
        ele = el;
        dist = new [n];
        finalizeRoute();
        return true;
    }

    hidden function sliceCount(n) {
        return (n + STORAGE_SLICE - 1) / STORAGE_SLICE;
    }
}
