package nl.paree.climbpro.domain.border;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline country lookup from coarse, bundled boundary polygons (issue #209). Pure.
 *
 * <p>Text format (see {@code tools/gen_country_borders.py}): one ring per line,
 * {@code CC lon,lat lon,lat ...}; {@code #} starts a comment. All rings of a country are
 * evaluated together with the even-odd rule, so holes (e.g. San Marino inside Italy) are simply
 * extra rings. When simplified borders overlap, the country with the smallest bounding box wins,
 * which lets enclaves and micro-states take precedence over their neighbour.
 */
public final class CountryPolygons {

    /** Path of the bundled boundary file inside the APK's assets. */
    public static final String ASSET_PATH = "borders/europe_countries.txt";

    private static final class Country {
        final String code;
        final List<float[]> rings = new ArrayList<>(); // interleaved lon,lat
        final List<float[]> ringBoxes = new ArrayList<>(); // minLon,minLat,maxLon,maxLat
        float minLon = Float.MAX_VALUE, minLat = Float.MAX_VALUE;
        float maxLon = -Float.MAX_VALUE, maxLat = -Float.MAX_VALUE;

        Country(String code) { this.code = code; }

        double boxArea() { return (double) (maxLon - minLon) * (maxLat - minLat); }

        boolean contains(double lat, double lon) {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false;
            boolean inside = false;
            for (int r = 0; r < rings.size(); r++) {
                float[] box = ringBoxes.get(r);
                if (lon < box[0] || lon > box[2] || lat < box[1] || lat > box[3]) continue;
                if (ringContains(rings.get(r), lat, lon)) inside = !inside;
            }
            return inside;
        }
    }

    private final List<Country> countries;

    private CountryPolygons(List<Country> countries) {
        this.countries = countries;
    }

    public static CountryPolygons parse(InputStream in) throws IOException {
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public static CountryPolygons parse(Reader reader) throws IOException {
        Map<String, Country> byCode = new LinkedHashMap<>();
        BufferedReader br = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            float[] ring = parseRing(parts);
            if (ring == null) continue;
            String code = parts[0];
            Country c = byCode.get(code);
            if (c == null) {
                c = new Country(code);
                byCode.put(code, c);
            }
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < ring.length; i += 2) {
                box[0] = Math.min(box[0], ring[i]);
                box[1] = Math.min(box[1], ring[i + 1]);
                box[2] = Math.max(box[2], ring[i]);
                box[3] = Math.max(box[3], ring[i + 1]);
            }
            c.rings.add(ring);
            c.ringBoxes.add(box);
            c.minLon = Math.min(c.minLon, box[0]);
            c.minLat = Math.min(c.minLat, box[1]);
            c.maxLon = Math.max(c.maxLon, box[2]);
            c.maxLat = Math.max(c.maxLat, box[3]);
        }
        List<Country> list = new ArrayList<>(byCode.values());
        Collections.sort(list, (a, b) -> Double.compare(a.boxArea(), b.boxArea()));
        return new CountryPolygons(list);
    }

    /** ISO alpha-2 code of the country containing the point, or null (sea / outside coverage). */
    public String countryAt(double lat, double lon) {
        for (Country c : countries) {
            if (c.contains(lat, lon)) return c.code;
        }
        return null;
    }

    public int countryCount() {
        return countries.size();
    }

    /** Null when any coordinate is malformed. */
    private static float[] parseRing(String[] parts) {
        float[] ring = new float[(parts.length - 1) * 2];
        try {
            for (int i = 1; i < parts.length; i++) {
                int comma = parts[i].indexOf(',');
                if (comma < 0) return null;
                ring[(i - 1) * 2] = Float.parseFloat(parts[i].substring(0, comma));
                ring[(i - 1) * 2 + 1] = Float.parseFloat(parts[i].substring(comma + 1));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return ring;
    }

    /** Ray casting; ring is implicitly closed. */
    private static boolean ringContains(float[] ring, double lat, double lon) {
        boolean inside = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[i * 2], yi = ring[i * 2 + 1];
            double xj = ring[j * 2], yj = ring[j * 2 + 1];
            if ((yi > lat) != (yj > lat)
                    && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
