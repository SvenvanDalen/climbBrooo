package nl.paree.climbpro.data.route;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import nl.paree.climbpro.domain.climb.ClimbConstants;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.service.ClimbPayloadBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #203: stored OSM tunnels, resync behaviour and the 'hz' wire markers. */
@RunWith(RobolectricTestRunner.class)
public class RouteRepositoryTunnelTest {

    private RouteRepository repo;

    @Before
    public void setUp() {
        Application app = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();
        repo = new RouteRepository(app, (lat, lon) -> null);
    }

    private static List<RoutePoint> flat() {
        List<RoutePoint> pts = new ArrayList<>();
        for (int i = 0; i <= 40; i++) pts.add(new RoutePoint(51.0 + i * 0.0009, 5.0, 10, i * 100.0));
        return pts;
    }

    private void save(String id, String hash) throws Exception {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.name = "Tunnelroute";
        r.sourceHash = hash;
        repo.saveRoute(r, flat(), Collections.emptyList());
    }

    @Test
    public void tunnelsAreStoredAndSurviveResyncOfTheSameGeometry() throws Exception {
        save("t1", "h1");
        assertNull(repo.loadRoute("t1").tunnels);
        List<StoredTunnel> t = new ArrayList<>();
        t.add(new StoredTunnel(1200, 1450));
        repo.setTunnels("t1", t);
        assertEquals(1, repo.loadRoute("t1").tunnels.size());

        save("t1", "h1");
        assertEquals(1200, repo.loadRoute("t1").tunnels.get(0).startDistance);
    }

    @Test
    public void changedGeometryDropsTunnels() throws Exception {
        save("t2", "h1");
        repo.setTunnels("t2", Collections.singletonList(new StoredTunnel(100, 200)));
        save("t2", "h2");
        assertNull(repo.loadRoute("t2").tunnels);
    }

    @Test
    public void emptyLookupIsRemembered() throws Exception {
        save("t3", "h1");
        repo.setTunnels("t3", new ArrayList<>());
        assertTrue(repo.loadRoute("t3").tunnels.isEmpty());
    }

    @Test
    public void payloadCarriesTunnelsAsHz() throws Exception {
        save("t4", "h1");
        repo.setTunnels("t4", Collections.singletonList(new StoredTunnel(1200, 1450)));
        ObjectMapper m = new ObjectMapper();
        JsonNode p = m.readTree(new ClimbPayloadBuilder(m).buildRoutePayload(repo.loadRoute("t4")));
        assertEquals(3, p.get("hz").size());
        assertEquals(1200, p.get("hz").get(0).asInt());
        assertEquals(0, p.get("hz").get(2).asInt());
    }

    @Test
    public void flatRouteWithoutTunnelsHasNoHz() throws Exception {
        save("t5", "h1");
        ObjectMapper m = new ObjectMapper();
        JsonNode p = m.readTree(new ClimbPayloadBuilder(m).buildRoutePayload(repo.loadRoute("t5")));
        assertFalse(p.has("hz"));
    }
}
