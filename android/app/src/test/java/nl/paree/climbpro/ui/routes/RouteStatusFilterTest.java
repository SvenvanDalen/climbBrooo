package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRideStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteStatusFilterTest {

    private RouteCatalogEntry entry(String id, String status) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.rideStatus = status;
        return e;
    }

    private List<String> ids(List<RouteCatalogEntry> in) {
        List<String> out = new ArrayList<>();
        for (RouteCatalogEntry e : in) out.add(e.routeId);
        return out;
    }

    private List<RouteCatalogEntry> sample() {
        return new ArrayList<>(Arrays.asList(
                entry("none", null),
                entry("want", RouteRideStatus.WANT_TO_RIDE),
                entry("ridden", RouteRideStatus.RIDDEN),
                entry("want2", RouteRideStatus.WANT_TO_RIDE),
                entry("garbage", "SOMETHING_ELSE")));
    }

    @Test
    public void allKeepsEveryRouteInOrder() {
        assertEquals(Arrays.asList("none", "want", "ridden", "want2", "garbage"),
                ids(RouteStatusFilter.apply(sample(), RouteStatusFilter.FILTER_ALL)));
    }

    @Test
    public void wantToRideKeepsOnlyWantToRide() {
        assertEquals(Arrays.asList("want", "want2"),
                ids(RouteStatusFilter.apply(sample(), RouteStatusFilter.FILTER_WANT_TO_RIDE)));
    }

    @Test
    public void riddenKeepsOnlyRidden() {
        assertEquals(Arrays.asList("ridden"),
                ids(RouteStatusFilter.apply(sample(), RouteStatusFilter.FILTER_RIDDEN)));
    }

    @Test
    public void unknownFilterFallsBackToAll() {
        assertEquals(5, RouteStatusFilter.apply(sample(), 42).size());
    }

    @Test
    public void inputListIsNotModified() {
        List<RouteCatalogEntry> in = sample();
        RouteStatusFilter.apply(in, RouteStatusFilter.FILTER_RIDDEN);
        assertEquals(5, in.size());
    }

    @Test
    public void emptyListStaysEmpty() {
        assertEquals(0, RouteStatusFilter.apply(new ArrayList<>(),
                RouteStatusFilter.FILTER_WANT_TO_RIDE).size());
    }
}
