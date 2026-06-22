package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteDetailViewModelStarredTest {

    private static StoredStarredSegment starred(int startDist, int surface) {
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = startDist;          // unique-enough for the test
        s.startDistance = startDist;
        s.endDistance = startDist + 300;
        s.length = 300;
        s.surfaceType = surface;
        s.name = "S" + startDist;
        return s;
    }

    @Test
    public void buildRouteItems_includesSpecializedAndNonSpecialized_orderedByDistance() {
        StoredRoute r = new StoredRoute();
        StoredClimb c = new StoredClimb();
        c.startDistance = 1000; c.endDistance = 2000; c.length = 1000;
        r.climbs = new ArrayList<>(Arrays.asList(c));
        r.starredSegments = new ArrayList<>(Arrays.asList(
                starred(200, SurfaceType.UNKNOWN),   // non-specialized, before climb
                starred(2500, SurfaceType.GRAVEL))); // specialized, after climb

        List<Object> items = RouteDetailViewModel.buildRouteItems(r);

        assertEquals(3, items.size());
        assertTrue("first item is the early starred segment",
                items.get(0) instanceof StoredStarredSegment);
        assertEquals(200, ((StoredStarredSegment) items.get(0)).startDistance);
        assertTrue("middle item is the climb", items.get(1) instanceof StoredClimb);
        assertTrue("last item is the later starred segment",
                items.get(2) instanceof StoredStarredSegment);
        assertEquals(2500, ((StoredStarredSegment) items.get(2)).startDistance);
    }
}
