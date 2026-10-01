package nl.paree.climbpro.domain.poi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared test data for issue #208: a recorded Overpass response and a matching route. */
final class PoiFixtures {

    private PoiFixtures() {}

    static String overpassResponse() throws IOException {
        try (InputStream in = PoiFixtures.class.getResourceAsStream(
                "/overpass/route_pois_response.json")) {
            if (in == null) throw new IOException("fixture missing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Straight line due north along lon 5.70, from lat 50.80 to 50.85 (~5.6 km), 51 points. */
    static double[][] northboundRoute() {
        int n = 51;
        double[] lats = new double[n];
        double[] lons = new double[n];
        for (int i = 0; i < n; i++) {
            lats[i] = 50.80 + 0.001 * i;
            lons[i] = 5.70;
        }
        return new double[][]{lats, lons};
    }
}
