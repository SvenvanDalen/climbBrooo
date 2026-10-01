package nl.paree.climbpro.domain.offline;

import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Issue #200: offline package assembly (weather + POIs) and its offline report. */
public class OfflinePackageTest {

    private static final double M_PER_DEG = 111_195.0;
    /** 2026-10-01 00:00 UTC. */
    private static final long DAY0 = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();

    /** Straight 50 km route north from (51, 5), a point every 500 m. */
    private static StoredRoute route() {
        int n = 101;
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.lats = new double[n];
        r.lons = new double[n];
        r.distances = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 51.0 + i * 500 / M_PER_DEG;
            r.lons[i] = 5.0;
            r.distances[i] = i * 500.0;
        }
        return r;
    }

    /** Open-Meteo-like hourly JSON for 48 h from DAY0: temp 10 + hour/2, wind 10 + hour. */
    static String forecastJson() {
        StringBuilder t = new StringBuilder(), temp = new StringBuilder(),
                wind = new StringBuilder(), rain = new StringBuilder(), zero = new StringBuilder();
        for (int h = 0; h < 48; h++) {
            String sep = h == 0 ? "" : ",";
            t.append(sep).append('"').append(Instant.ofEpochMilli(DAY0).plusSeconds(h * 3600L)
                    .toString(), 0, 16).append('"');
            temp.append(sep).append(10 + h / 2.0);
            wind.append(sep).append(10 + h);
            rain.append(sep).append(h * 2);
            zero.append(sep).append(0);
        }
        return "{\"hourly\":{\"time\":[" + t + "],\"temperature_2m\":[" + temp
                + "],\"apparent_temperature\":[" + temp + "],\"wind_speed_10m\":[" + wind
                + "],\"wind_direction_10m\":[" + zero + "],\"precipitation_probability\":["
                + rain + "],\"uv_index\":[" + zero + "]}}";
    }

    private static OfflinePackage.Poi poi(String type, String name, double alongM, double offsetM) {
        return new OfflinePackage.Poi(type, name, 51.0 + alongM / M_PER_DEG,
                5.0 + offsetM / (M_PER_DEG * Math.cos(Math.toRadians(51.0))));
    }

    @Test
    public void buildsWeatherAlongTheRouteAndPlacesPois() {
        List<double[]> asked = new ArrayList<>();
        OfflinePackageBuilder b = new OfflinePackageBuilder(
                (lat, lon) -> {
                    asked.add(new double[]{lat, lon});
                    return forecastJson();
                },
                (lats, lons, d) -> Arrays.asList(
                        poi(OfflinePackage.Poi.WATER, "Kraan", 30_000, 20),
                        poi(OfflinePackage.Poi.FOOD, "Café", 12_000, 120),
                        poi(OfflinePackage.Poi.FOOD, "Café", 12_010, 125),   // duplicate
                        poi(OfflinePackage.Poi.TOILET, null, 5_000, 900)));  // too far
        OfflinePackage pkg = b.build(route(), DAY0);

        assertEquals("r1", pkg.routeId);
        assertEquals(DAY0, pkg.createdAtMs);
        assertEquals(4, pkg.weather.size());                 // 0, ~16.7, ~33.3, 50 km
        assertEquals(asked.size(), pkg.weather.size());
        assertEquals(50_000, pkg.weather.get(3).distanceM, 1);
        assertNull(pkg.weatherError);

        assertEquals(2, pkg.pois.size());
        assertEquals("Café", pkg.pois.get(0).name);          // sorted along the route
        assertEquals(12_000, pkg.pois.get(0).distanceM, 5);
        assertEquals(120, pkg.pois.get(0).offsetM, 5);
        assertEquals(30_000, pkg.pois.get(1).distanceM, 5);
    }

    @Test
    public void failingSourceIsRecordedAndTheOtherKept() {
        OfflinePackage pkg = new OfflinePackageBuilder(
                (lat, lon) -> { throw new IOException("geen bereik"); },
                (lats, lons, d) -> Arrays.asList(poi(OfflinePackage.Poi.WATER, null, 1000, 0)))
                .build(route(), DAY0);
        assertEquals("geen bereik", pkg.weatherError);
        assertTrue(pkg.weather.isEmpty());
        assertEquals(1, pkg.pois.size());
        assertNull(pkg.poiError);
    }

    @Test
    public void reportSummarisesWeatherAndPoisOffline() {
        OfflinePackage pkg = new OfflinePackageBuilder((lat, lon) -> forecastJson(),
                (lats, lons, d) -> Arrays.asList(
                        poi(OfflinePackage.Poi.WATER, "Kraan", 30_000, 80),
                        poi(OfflinePackage.Poi.BIKE, null, 2_000, 0)))
                .build(route(), DAY0);
        // Viewed two hours later, at 02:00: window 02:00-09:00 -> 11-14.5 °C, wind to 19.
        String text = OfflinePackageReport.build(pkg, DAY0 + 2 * 3_600_000L);

        assertTrue(text, text.startsWith("Opgeslagen 2 uur geleden."));
        assertTrue(text, text.contains("km 0: 11–15 °C, wind tot 19 km/u, regen tot 18 % (8 u)"));
        assertTrue(text, text.contains("Water (1)\n  km 30.0 · Kraan (80 m van route)"));
        assertTrue(text, text.contains("Fietsenmaker / reparatiepunt (1)\n  km 2.0"));
        assertTrue(!text.contains("Toiletten"));
    }

    @Test
    public void reportFlagsStaleAndExpiredForecasts() {
        OfflinePackage pkg = new OfflinePackage();
        pkg.createdAtMs = DAY0;
        pkg.weather.add(new OfflinePackage.WeatherPoint(0, 51, 5, forecastJson()));
        String text = OfflinePackageReport.build(pkg, DAY0 + 3L * 24 * 3_600_000L);
        assertTrue(text, text.contains("3 dagen geleden"));
        assertTrue(text, text.contains("Verouderd"));
        assertTrue(text, text.contains("km 0: voorspelling verlopen"));
    }

    @Test
    public void reportShowsErrorsAndBrokenForecasts() {
        OfflinePackage pkg = new OfflinePackage();
        pkg.createdAtMs = DAY0;
        pkg.poiError = "Overpass HTTP 429";
        pkg.weather.add(new OfflinePackage.WeatherPoint(10_000, 51, 5, "{kapot"));
        String text = OfflinePackageReport.build(pkg, DAY0);
        assertTrue(text, text.contains("km 10: onleesbare voorspelling"));
        assertTrue(text, text.contains("Niet opgehaald: Overpass HTTP 429"));
        assertNotNull(OfflinePackageReport.ageLine(DAY0, DAY0 - 1000)); // clock skew → 0 min
    }
}
