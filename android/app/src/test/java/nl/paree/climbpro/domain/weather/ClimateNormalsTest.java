package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class ClimateNormalsTest {

    /** Two June days, local times (timezone=auto): morning hours plus a night hour that is ignored. */
    private static final String FIXTURE = "{\"latitude\":50.85,\"longitude\":5.7,"
            + "\"utc_offset_seconds\":7200,\"hourly\":{"
            + "\"time\":[\"2024-06-01T03:00\",\"2024-06-01T07:00\",\"2024-06-01T08:00\","
            + "\"2024-06-02T07:00\",\"2024-06-02T08:00\",\"2024-06-02T12:00\"],"
            + "\"temperature_2m\":[5.0,14.0,16.0,18.0,null,24.0],"
            + "\"wind_speed_10m\":[30.0,10.0,10.0,10.0,10.0,20.0],"
            + "\"wind_direction_10m\":[0,90,90,90,90,270],"
            + "\"precipitation\":[9.0,0.0,0.0,0.4,0.3,0.0]}}";

    @Test public void dayPartsCoverRideableHours() {
        assertEquals(ClimateNormals.DayPart.OCHTEND, ClimateNormals.DayPart.ofHour(7));
        assertEquals(ClimateNormals.DayPart.OCHTEND, ClimateNormals.DayPart.ofHour(10));
        assertEquals(ClimateNormals.DayPart.MIDDAG, ClimateNormals.DayPart.ofHour(11));
        assertEquals(ClimateNormals.DayPart.NAMIDDAG, ClimateNormals.DayPart.ofHour(18));
        assertEquals(ClimateNormals.DayPart.AVOND, ClimateNormals.DayPart.ofHour(21));
        assertEquals(null, ClimateNormals.DayPart.ofHour(22));
        assertEquals(null, ClimateNormals.DayPart.ofHour(3));
    }

    @Test public void meanTemperaturePerMonthAndDayPartSkipsNullsAndNight() throws IOException {
        ClimateNormals n = ClimateNormals.fromArchive(FIXTURE);
        ClimateNormals.Cell june = n.cell(6, ClimateNormals.DayPart.OCHTEND);
        assertEquals((14.0 + 16.0 + 18.0) / 3, june.meanTempC, 1e-9);
        assertEquals(10.0, june.meanWindKmh, 1e-9);
        assertEquals(4, june.hours); // the null temperature hour still counts for wind
        assertEquals(24.0, n.cell(6, ClimateNormals.DayPart.MIDDAG).meanTempC, 1e-9);
    }

    @Test public void rainChanceCountsWetDayPartsNotHours() throws IOException {
        ClimateNormals n = ClimateNormals.fromArchive(FIXTURE);
        // June 1st morning dry, June 2nd morning 0.7 mm in total -> 1 of 2 mornings wet.
        assertEquals(0.5, n.cell(6, ClimateNormals.DayPart.OCHTEND).rainChance, 1e-9);
        assertEquals(0.0, n.cell(6, ClimateNormals.DayPart.MIDDAG).rainChance, 1e-9);
    }

    @Test public void headwindFollowsClimbBearing() throws IOException {
        ClimateNormals.Cell c = ClimateNormals.fromArchive(FIXTURE)
                .cell(6, ClimateNormals.DayPart.OCHTEND);
        // Wind from the east (90°) at 10 km/h: full headwind riding east, tailwind riding west.
        assertEquals(10.0, c.headwindKmh(90), 1e-6);
        assertEquals(-10.0, c.headwindKmh(270), 1e-6);
        assertEquals(0.0, c.headwindKmh(0), 1e-6);
        assertEquals(0.0, c.headwindKmh(Double.NaN), 1e-9);
    }

    @Test public void monthsWithoutDataAreEmpty() throws IOException {
        ClimateNormals n = ClimateNormals.fromArchive(FIXTURE);
        assertFalse(n.cell(1, ClimateNormals.DayPart.OCHTEND).hasData());
        assertTrue(n.cell(6, ClimateNormals.DayPart.OCHTEND).hasData());
        assertTrue(Double.isNaN(n.cell(1, ClimateNormals.DayPart.OCHTEND).meanTempC));
    }

    @Test(expected = IOException.class)
    public void missingHourlyBlockIsAnError() throws IOException {
        ClimateNormals.fromArchive("{\"error\":true,\"reason\":\"nope\"}");
    }

    @Test public void jsonRoundTripKeepsEveryCell() throws IOException {
        ClimateNormals n = ClimateNormals.fromArchive(FIXTURE);
        ClimateNormals back = ClimateNormals.fromJson(n.toJson());
        for (int m = 1; m <= 12; m++) {
            for (ClimateNormals.DayPart p : ClimateNormals.DayPart.values()) {
                ClimateNormals.Cell a = n.cell(m, p);
                ClimateNormals.Cell b = back.cell(m, p);
                assertEquals(a.hours, b.hours);
                if (!a.hasData()) continue;
                // The cache keeps two decimals: plenty for a climatology.
                assertEquals(a.meanTempC, b.meanTempC, 0.005);
                assertEquals(a.meanWindKmh, b.meanWindKmh, 0.005);
                assertEquals(a.rainChance, b.rainChance, 0.005);
                assertEquals(a.headwindKmh(90), b.headwindKmh(90), 0.01);
            }
        }
    }

    @Test(expected = IOException.class)
    public void corruptCacheIsAnError() throws IOException {
        ClimateNormals.fromJson("{\"v\":1,\"cells\":[1,2]}");
    }

    @Test public void bearingFromFootToTop() {
        assertEquals(90.0, ClimateNormals.bearingDeg(50.0, 5.0, 50.0, 5.1), 0.1);
        assertEquals(0.0, ClimateNormals.bearingDeg(50.0, 5.0, 50.1, 5.0), 0.1);
        assertEquals(180.0, ClimateNormals.bearingDeg(50.1, 5.0, 50.0, 5.0), 0.1);
        assertTrue(Double.isNaN(ClimateNormals.bearingDeg(50.0, 5.0, 50.0, 5.0)));
    }
}
