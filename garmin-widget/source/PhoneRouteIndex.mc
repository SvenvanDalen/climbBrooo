using Toybox.System as Sys;

class PhoneRouteIndex {

    var routes;
    var received;

    function initialize() {
        routes   = [];
        received = false;
    }

    function populate(routeList) {
        if (routeList instanceof Toybox.Lang.Array) {
            routes = routeList;
        } else {
            routes = [];
        }
        received = true;
        Sys.println("PhoneRouteIndex: " + routes.size() + " routes");
    }

    function getCount() { return routes.size(); }

    function getId(i) {
        var r = routes[i];
        return (r instanceof Toybox.Lang.Dictionary) ? r.get("id") : null;
    }

    function getName(i) {
        var r = routes[i];
        if (!(r instanceof Toybox.Lang.Dictionary)) { return "Route"; }
        var n = r.get("name");
        return (n instanceof Toybox.Lang.String) ? n : "Route";
    }

    function getClimbCount(i) {
        var r = routes[i];
        if (!(r instanceof Toybox.Lang.Dictionary)) { return 0; }
        var c = r.get("climbCount");
        return (c instanceof Toybox.Lang.Number) ? c.toNumber() : 0;
    }
}
