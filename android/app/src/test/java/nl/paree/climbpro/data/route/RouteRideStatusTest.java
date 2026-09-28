package nl.paree.climbpro.data.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

public class RouteRideStatusTest {

    @Test
    public void normalizeKeepsKnownValues() {
        assertEquals(RouteRideStatus.WANT_TO_RIDE, RouteRideStatus.normalize("WANT_TO_RIDE"));
        assertEquals(RouteRideStatus.RIDDEN, RouteRideStatus.normalize("RIDDEN"));
    }

    @Test
    public void normalizeMapsUnknownAndBlankToNull() {
        assertNull(RouteRideStatus.normalize(null));
        assertNull(RouteRideStatus.normalize(""));
        assertNull(RouteRideStatus.normalize("ridden")); // case-sensitive: stored values are canonical
        assertNull(RouteRideStatus.normalize("NONE"));
    }

    @Test
    public void labelsAreDutch() {
        assertEquals("Wil ik rijden", RouteRideStatus.label(RouteRideStatus.WANT_TO_RIDE));
        assertEquals("Gereden", RouteRideStatus.label(RouteRideStatus.RIDDEN));
        assertEquals("Geen status", RouteRideStatus.label(null));
    }

    @Test
    public void oldJsonWithoutFieldDeserializesToNoStatus() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StoredRoute route = mapper.readValue(
                "{\"routeId\":\"r1\",\"name\":\"Oud\",\"notes\":\"n\"}", StoredRoute.class);
        RouteCatalogEntry entry = mapper.readValue(
                "{\"routeId\":\"r1\",\"name\":\"Oud\",\"climbCount\":2}", RouteCatalogEntry.class);
        assertNull(route.rideStatus);
        assertNull(entry.rideStatus);
    }
}
