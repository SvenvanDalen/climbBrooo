package nl.paree.climbpro.domain.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

/** Less common report lines: weather errors, unknown temperature, overflowing POI lists. */
public class OfflinePackageReportEdgeTest {

    private static final long NOW = Instant.parse("2026-09-24T10:30:00Z").toEpochMilli();

    private static OfflinePackage pkg() {
        OfflinePackage p = new OfflinePackage();
        p.routeId = "r1";
        p.createdAtMs = NOW;
        return p;
    }

    @Test
    public void weatherErrorAndNoWeather_areReported() {
        OfflinePackage failed = pkg();
        failed.weatherError = "geen bereik";
        assertTrue(OfflinePackageReport.build(failed, NOW).contains("\nNiet opgehaald: geen bereik"));

        OfflinePackage empty = pkg();
        empty.weather = null;
        assertTrue(OfflinePackageReport.build(empty, NOW).contains("\nGeen weerpunten."));
    }

    @Test
    public void forecastStartingLater_usesItsFirstHours_andUnknownTemperature() {
        String json = "{\"hourly\":{\"time\":[\"2026-09-24T14:00\",\"2026-09-24T15:00\"],"
                + "\"temperature_2m\":[null,null],\"wind_speed_10m\":[10,20],"
                + "\"precipitation_probability\":[null,null]}}";
        String line = OfflinePackageReport.weatherLine(
                new OfflinePackage.WeatherPoint(12_000, 51, 5, json), NOW);
        assertEquals("km 12: temperatuur onbekend, wind tot 20 km/u (2 u vanaf begin voorspelling)",
                line);
    }

    @Test
    public void poiListPerType_isCappedWithACount() {
        OfflinePackage p = pkg();
        for (int i = 0; i < OfflinePackageReport.MAX_POIS_PER_TYPE + 3; i++) {
            OfflinePackage.Poi poi = new OfflinePackage.Poi();
            poi.type = OfflinePackage.Poi.WATER;
            poi.distanceM = i * 1000;
            p.pois.add(poi);
        }

        String report = OfflinePackageReport.build(p, NOW);

        assertTrue(report, report.contains("Water (18)"));
        assertTrue(report, report.contains("\n  … en 3 meer"));
    }

    @Test
    public void ageLine_unitsAndStaleness() {
        assertEquals("Opgeslagen 0 min geleden.", OfflinePackageReport.ageLine(NOW + 5_000, NOW));
        assertEquals("Opgeslagen 3 uur geleden.", OfflinePackageReport.ageLine(NOW - 3 * 3_600_000L, NOW));
        assertTrue(OfflinePackageReport.ageLine(NOW - 3 * 86_400_000L, NOW)
                .startsWith("Opgeslagen 3 dagen geleden. Verouderd"));
    }
}
