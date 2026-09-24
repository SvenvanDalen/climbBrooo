package nl.paree.climbpro.domain.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ActivityFileReaderTest {

    private static final String TIMED_GPX = "<?xml version=\"1.0\"?>"
            + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
            + "<trkpt lat=\"50.85\" lon=\"5.69\"><ele>50</ele><time>2026-07-04T06:30:00Z</time></trkpt>"
            + "<trkpt lat=\"50.86\" lon=\"5.70\"><time>2026-07-04T08:30:05+02:00</time></trkpt>"
            + "<trkpt lat=\"50.87\" lon=\"5.71\"></trkpt>"
            + "</trkseg></trk></gpx>";

    @Test
    public void gpxWithTimesBecomesOneActivity() throws IOException {
        List<ActivityFileReader.Activity> acts = ActivityFileReader.read("rit.gpx",
                TIMED_GPX.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, acts.size());
        assertEquals(2, acts.get(0).track.size()); // untimed point dropped
        assertEquals(1_783_146_600L, acts.get(0).startEpochSec());
        assertEquals(1_783_146_605L, acts.get(0).track.get(1).timeSec);
    }

    @Test
    public void untimedGpxIsNotAnActivity() throws IOException {
        String route = "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1.1\" lon=\"2\"/>"
                + "</trkseg></trk></gpx>";
        assertTrue(ActivityFileReader.read("route.gpx",
                route.getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    public void garminExportZipReadsEveryActivityAndIgnoresOtherFiles() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (String name : Arrays.asList("a.gpx", "b.GPX", "readme.txt", "broken.gpx")) {
                zip.putNextEntry(new ZipEntry(name));
                String content = name.equals("readme.txt") ? "hallo"
                        : name.equals("broken.gpx") ? "<gpx><trk" : TIMED_GPX;
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        assertEquals(2, ActivityFileReader.read("export.zip", buf.toByteArray()).size());
    }

    @Test(expected = IOException.class)
    public void garbageIsRejected() throws IOException {
        ActivityFileReader.read("x.gpx", "geen xml".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void parseTime_utcAndOffsets() {
        assertEquals(Long.valueOf(0L), ActivityFileReader.parseTime("1970-01-01T00:00:00Z"));
        assertEquals(Long.valueOf(0L), ActivityFileReader.parseTime("1970-01-01T01:00:00+01:00"));
        assertEquals(Long.valueOf(1L), ActivityFileReader.parseTime("1970-01-01T00:00:01.500Z"));
        assertNull(ActivityFileReader.parseTime("gisteren"));
    }

    // --- dedupe --------------------------------------------------------------------------

    private static StoredClimbAttempt attempt(String climb, long activity, long date) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climb;
        a.activityId = activity;
        a.dateEpochSec = date;
        return a;
    }

    @Test
    public void dedupe_skipsSameRideFromStravaButKeepsOtherRides() {
        long start = 1_783_146_600L;
        long id = ImportedActivityDedupe.activityIdFor(start);
        List<StoredClimbAttempt> existing = Collections.singletonList(
                attempt("cauberg", 987654321L, start + 60)); // Strava, 1 min later start

        List<StoredClimbAttempt> kept = ImportedActivityDedupe.withoutKnownRides(Arrays.asList(
                attempt("cauberg", id, start),            // same ride: skipped
                attempt("keutenberg", id, start),         // other climb: kept
                attempt("cauberg", id, start + 86_400)),  // next day: kept
                existing);

        assertEquals(2, kept.size());
        assertEquals("keutenberg", kept.get(0).climbId);
        assertTrue(id < 0);
        assertEquals(id, ImportedActivityDedupe.activityIdFor(start));
    }
}
