package nl.paree.climbpro.data.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import nl.paree.climbpro.domain.weather.ClimateNormals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class ClimateCacheTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String ARCHIVE = "{\"hourly\":{\"time\":[\"2024-07-01T08:00\"],"
            + "\"temperature_2m\":[17.5],\"wind_speed_10m\":[9.0],"
            + "\"wind_direction_10m\":[180],\"precipitation\":[0.0]}}";

    @Test public void nearbyClimbsShareOneGridCell() {
        assertEquals("climate/50.85_5.70.json", ClimateCache.relativePath(50.851, 5.699));
        assertEquals(ClimateCache.relativePath(50.86, 5.71), ClimateCache.relativePath(50.851, 5.699));
        assertEquals("climate/-33.90_18.40.json", ClimateCache.relativePath(-33.91, 18.41));
    }

    @Test public void savedNormalsLoadBack() throws Exception {
        ClimateCache cache = new ClimateCache(tmp.getRoot());
        assertNull(cache.load(50.85, 5.7));
        cache.save(50.85, 5.7, ClimateNormals.fromArchive(ARCHIVE));
        ClimateNormals back = cache.load(50.851, 5.701);
        assertNotNull(back);
        assertEquals(17.5, back.cell(7, ClimateNormals.DayPart.OCHTEND).meanTempC, 1e-6);
        assertEquals(0, new File(tmp.getRoot(), "climate").listFiles((d, n) -> n.endsWith(".tmp")).length);
    }

    @Test public void corruptFileIsTreatedAsMissing() throws Exception {
        File f = new File(tmp.getRoot(), ClimateCache.relativePath(50.85, 5.7));
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("not json".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(new ClimateCache(tmp.getRoot()).load(50.85, 5.7));
    }

    @Test public void archiveUrlAsksThreeFullYearsInLocalTime() {
        assertEquals("https://archive-api.open-meteo.com/v1/archive?latitude=50.85000"
                        + "&longitude=5.69000&start_date=2023-01-01&end_date=2025-12-31"
                        + "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation"
                        + "&wind_speed_unit=kmh&timezone=auto&elevation=250",
                OpenMeteoClient.archiveUrl(50.85, 5.69, 250.4, 2026));
        assertEquals("https://archive-api.open-meteo.com/v1/archive?latitude=50.85000"
                        + "&longitude=5.69000&start_date=2023-01-01&end_date=2025-12-31"
                        + "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation"
                        + "&wind_speed_unit=kmh&timezone=auto",
                OpenMeteoClient.archiveUrl(50.85, 5.69, Double.NaN, 2026));
    }
}
