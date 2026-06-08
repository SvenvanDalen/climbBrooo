using Toybox.Application.Storage as Storage;

module StorageManager {

    function getSavedRouteIds() {
        var ids = Storage.getValue("saved_route_ids");
        return (ids instanceof Toybox.Lang.Array) ? ids : [];
    }

    function getSavedClimbKeys() {
        var keys = Storage.getValue("saved_climb_ids");
        return (keys instanceof Toybox.Lang.Array) ? keys : [];
    }

    function isRouteSaved(routeId) {
        var ids = getSavedRouteIds();
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i].equals(routeId)) { return true; }
        }
        return false;
    }

    function saveRoute(routeId, payloadDict) {
        var ids = getSavedRouteIds();
        if (!isRouteSaved(routeId)) { ids.add(routeId); }
        Storage.setValue("saved_route_ids", ids);
        Storage.setValue("route_" + routeId, payloadDict);
    }

    function deleteRoute(routeId) {
        var ids = getSavedRouteIds();
        var newIds = [];
        for (var i = 0; i < ids.size(); i++) {
            if (!ids[i].equals(routeId)) { newIds.add(ids[i]); }
        }
        Storage.setValue("saved_route_ids", newIds);
        Storage.deleteValue("route_" + routeId);
    }

    function loadRoute(routeId) {
        return Storage.getValue("route_" + routeId);
    }

    function isClimbSaved(routeId, climbIdx) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        for (var i = 0; i < keys.size(); i++) {
            if (keys[i].equals(key)) { return true; }
        }
        return false;
    }

    function saveClimb(routeId, climbIdx, climbDict) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        var found = false;
        for (var i = 0; i < keys.size(); i++) {
            if (keys[i].equals(key)) { found = true; break; }
        }
        if (!found) { keys.add(key); }
        Storage.setValue("saved_climb_ids", keys);
        Storage.setValue("climb_" + key, climbDict);
    }

    function deleteClimb(routeId, climbIdx) {
        var key = routeId + "_" + climbIdx;
        var keys = getSavedClimbKeys();
        var newKeys = [];
        for (var i = 0; i < keys.size(); i++) {
            if (!keys[i].equals(key)) { newKeys.add(keys[i]); }
        }
        Storage.setValue("saved_climb_ids", newKeys);
        Storage.deleteValue("climb_" + key);
    }

    function loadClimb(routeId, climbIdx) {
        return Storage.getValue("climb_" + routeId + "_" + climbIdx);
    }
}
