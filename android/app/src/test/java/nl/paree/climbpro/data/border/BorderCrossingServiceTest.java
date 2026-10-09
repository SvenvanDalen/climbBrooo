package nl.paree.climbpro.data.border;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/** Uses the bundled border polygons asset. */
@RunWith(RobolectricTestRunner.class)
public class BorderCrossingServiceTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    /** Straight line sampled every ~1 km between two points. */
    private static StoredRoute line(double lat0, double lon0, double lat1, double lon1) {
        int n = 160;
        StoredRoute r = new StoredRoute();
        r.lats = new double[n + 1];
        r.lons = new double[n + 1];
        r.distances = new double[n + 1];
        r.elevations = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            r.lats[i] = lat0 + (lat1 - lat0) * t;
            r.lons[i] = lon0 + (lon1 - lon0) * t;
            r.distances[i] = i * 1000.0;
        }
        return r;
    }

    @Test
    public void nullRoute_isEmpty() {
        assertTrue(new BorderCrossingService(app).describe(null).isEmpty());
    }

    @Test
    public void routeWithinOneCountry_isEmpty() {
        // Utrecht -> Zwolle, entirely in the Netherlands.
        assertTrue(new BorderCrossingService(app).describe(line(52.09, 5.12, 52.51, 6.09)).isEmpty());
    }

    @Test
    public void routeIntoBelgium_listsStartCountryAndCrossing() {
        // Utrecht -> Brussels.
        List<String> lines = new BorderCrossingService(app).describe(line(52.09, 5.12, 50.85, 4.35));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).length() > 0);
        assertTrue(lines.get(1), lines.get(1).length() > 0);
    }
}
