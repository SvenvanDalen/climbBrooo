package nl.paree.climbpro.domain.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class CsvExporterTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static StoredClimb climb(String name, int length, int gain) {
        StoredClimb c = new StoredClimb();
        c.name = name;
        c.startLat = 50.8;
        c.startLon = 5.9;
        c.length = length;
        c.elevationGain = gain;
        return c;
    }

    private static StoredRoute route(String id, String name, StoredClimb... climbs) {
        StoredRoute r = new StoredRoute();
        r.routeId = id;
        r.name = name;
        r.distances = new double[]{0, 12_345};
        r.climbs = new ArrayList<>(Arrays.asList(climbs));
        r.importedAtMs = 1_700_000_000_000L; // 2023-11-14 22:13 UTC
        return r;
    }

    private static StoredClimbAttempt attempt(String climbId, long dateSec, int elapsed) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.activityId = 42;
        a.dateEpochSec = dateSec;
        a.elapsedSec = elapsed;
        return a;
    }

    @Test
    public void escape_quotesOnlyWhenNeeded() {
        assertEquals("plain", CsvExporter.escape("plain"));
        assertEquals("", CsvExporter.escape(null));
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"));
        assertEquals("\"zei \"\"hoi\"\"\"", CsvExporter.escape("zei \"hoi\""));
        assertEquals("\"regel1\nregel2\"", CsvExporter.escape("regel1\nregel2"));
    }

    @Test
    public void routesCsv_headerAndRowWithUserNameAndTotals() {
        StoredRoute r = route("r1", "strava name", climb("A", 1000, 60), climb("B", 900, 40));
        r.userDisplayName = "Limburg, heuvels";
        r.notes = "mooi";

        String[] lines = CsvExporter.routesCsv(Collections.singletonList(r), UTC).split("\r\n");

        assertEquals("route_id,naam,afstand_km,hoogtemeters_klimmen,aantal_klimmen,geimporteerd,notities",
                lines[0]);
        assertEquals("r1,\"Limburg, heuvels\",12.35,100,2,2023-11-14 22:13,mooi", lines[1]);
    }

    @Test
    public void attemptsCsv_joinsClimbInfoAndDerivesSpeedAndVam() {
        StoredClimb c = climb("Cauberg", 1200, 60);
        c.userDisplayName = "Cauberg (eigen)";
        StoredRoute r = route("r1", "Rondje", c);
        StoredClimbAttempt a = attempt(ClimbIdentity.of(c), 1_700_000_000L, 240);
        a.routeDeviation = true;
        a.note = "wind tegen";

        String[] lines = CsvExporter.attemptsCsv(
                Collections.singletonList(a), Collections.singletonList(r), UTC).split("\r\n");

        // 1200 m in 240 s = 18.0 km/h; 60 m in 240 s = 900 m/h.
        assertEquals("2023-11-14 22:13,Cauberg (eigen),Rondje," + ClimbIdentity.of(c)
                + ",240,0:04:00,1200,60,18.0,900,1,ja,42,wind tegen", lines[1]);
    }

    @Test
    public void attemptsCsv_unknownClimbStillExportedWithEmptyClimbColumns() {
        StoredClimbAttempt a = attempt("gone", 1_700_000_000L, 300);

        String[] lines = CsvExporter.attemptsCsv(
                Collections.singletonList(a), Collections.emptyList(), UTC).split("\r\n");

        assertEquals("2023-11-14 22:13,,,gone,300,0:05:00,,,,,1,nee,42,", lines[1]);
    }

    @Test
    public void attemptsCsv_sortedOldestFirstAndZeroTimeHasNoDerivedValues() {
        StoredClimb c = climb("X", 1000, 50);
        String id = ClimbIdentity.of(c);
        StoredClimbAttempt late = attempt(id, 2_000_000_000L, 100);
        StoredClimbAttempt early = attempt(id, 1_000_000_000L, 0);

        String csv = CsvExporter.attemptsCsv(Arrays.asList(late, early),
                Collections.singletonList(route("r", "R", c)), UTC);
        String[] lines = csv.split("\r\n");

        assertTrue(lines[1].startsWith("2001-09-09"));
        assertTrue(lines[1].contains(",0,,1000,50,,,1,"));
        assertTrue(lines[2].startsWith("2033-05-18"));
    }
    @Test
    public void bomIsTheUtf8ByteOrderMark() {
        assertEquals("\uFEFF", CsvExporter.BOM); // Excel needs it to read accents as UTF-8
    }
}
