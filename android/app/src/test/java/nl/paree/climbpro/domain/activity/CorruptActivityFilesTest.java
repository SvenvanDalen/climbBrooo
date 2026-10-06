package nl.paree.climbpro.domain.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Corrupted FIT/GPX/zip activity files (CLAUDE.md edge case: corrupted GPX/FIT). */
@RunWith(RobolectricTestRunner.class)
public class CorruptActivityFilesTest {

    private static final long FIT_TS = 1_100_000_000L;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] timedFit() {
        FitTrackDecoderTest.Fit f = new FitTrackDecoderTest.Fit();
        f.def(0, false, 20, 253, 4, 0x86, 0, 4, 0x85, 1, 4, 0x85);
        f.u8(0).u32(FIT_TS).u32(FitTrackDecoderTest.semicircles(45.0))
                .u32(FitTrackDecoderTest.semicircles(6.0));
        f.u8(0).u32(FIT_TS + 10).u32(FitTrackDecoderTest.semicircles(45.001))
                .u32(FitTrackDecoderTest.semicircles(6.0));
        return f.build();
    }

    private static String gpx(String... times) {
        StringBuilder sb = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < times.length; i++) {
            sb.append("<trkpt lat=\"").append(45 + i * 0.001).append("\" lon=\"6\"><time>")
                    .append(times[i]).append("</time></trkpt>");
        }
        return sb.append("</trkseg></trk></gpx>").toString();
    }

    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("map/"));
            z.closeEntry();
            for (int i = 0; i < names.length; i++) {
                z.putNextEntry(new ZipEntry(names[i]));
                z.write(contents[i]);
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---- FIT ----

    @Test
    public void fitTruncatedInsideADefinition_isReportedAsDamaged() {
        FitTrackDecoderTest.Fit f = new FitTrackDecoderTest.Fit();
        f.u8(0x40); // definition header, then nothing
        byte[] full = f.build();
        byte[] noCrc = Arrays.copyOf(full, full.length - 2);
        try {
            FitTrackDecoder.decodeRecords(noCrc);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Beschadigd FIT-bestand", e.getMessage());
            assertTrue(e.getCause() instanceof IndexOutOfBoundsException);
        }
    }

    @Test
    public void fitWithTooSmallHeader_isRejected() {
        byte[] data = timedFit();
        data[0] = 8;
        try {
            FitTrackDecoder.decode(data);
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Ongeldige FIT-header", e.getMessage());
        }
    }

    @Test
    public void notAFit_isRejected() {
        try {
            FitTrackDecoder.decodeRecords(bytes("hello, world"));
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Geen FIT-bestand", e.getMessage());
        }
    }

    @Test
    public void activityReader_readsTimedFit() throws Exception {
        List<ActivityFileReader.Activity> acts = ActivityFileReader.read("rit.fit", timedFit());
        assertEquals(1, acts.size());
        assertEquals(2, acts.get(0).track.size());
        assertEquals(FIT_TS + FitTrackDecoder.FIT_EPOCH_OFFSET, acts.get(0).startEpochSec());
    }

    // ---- GPX ----

    @Test
    public void gpx_offsetTimesParse_badTimesAndCoordinatesAreDropped() throws Exception {
        String doc = "<gpx><trk><trkseg>"
                + "<trkpt lat=\"45.0\" lon=\"6\"><time>2026-09-20T10:00:00+02:00</time></trkpt>"
                + "<trkpt lat=\"45.1\" lon=\"6\"><time>gisteren</time></trkpt>"
                + "<trkpt lat=\"x\" lon=\"6\"><time>2026-09-20T08:00:05Z</time></trkpt>"
                + "<trkpt lon=\"6\"><time>2026-09-20T08:00:06Z</time></trkpt>"
                + "<trkpt lat=\"45.2\" lon=\"6\"><time>2026-09-20T08:00:10Z</time></trkpt>"
                + "</trkseg></trk></gpx>";

        List<ActivityFileReader.Activity> acts = ActivityFileReader.read("rit.gpx", bytes(doc));

        assertEquals(1, acts.size());
        assertEquals(2, acts.get(0).track.size());
        assertEquals(java.time.Instant.parse("2026-09-20T08:00:00Z").getEpochSecond(),
                acts.get(0).startEpochSec());
        assertNull(ActivityFileReader.parseTime("2026-13-99"));
    }

    @Test
    public void gpx_notXml_isRejected() {
        try {
            ActivityFileReader.read("rit.gpx", bytes("<gpx><trk><trkpt lat="));
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Geen geldig GPX-bestand", e.getMessage());
        }
    }

    @Test
    public void gpx_singleTimedPoint_isNoActivity() throws Exception {
        assertTrue(ActivityFileReader.read("rit.gpx", bytes(gpx("2026-09-20T08:00:00Z"))).isEmpty());
    }

    // ---- zip export ----

    @Test
    public void zip_oneBrokenFileDoesNotSinkTheOthers() throws Exception {
        byte[] data = zip(
                new String[]{"kapot.gpx", "goed.GPX", "notities.txt", "rit.fit"},
                new byte[][]{bytes("<gpx><broken"),
                        bytes(gpx("2026-09-20T08:00:00Z", "2026-09-20T08:00:10Z")),
                        bytes("geen activiteit"),
                        timedFit()});

        List<ActivityFileReader.Activity> acts = ActivityFileReader.read("export.zip", data);

        assertEquals(2, acts.size());
        assertEquals("goed.GPX", acts.get(0).name);
        assertEquals("rit.fit", acts.get(1).name);
    }

    // ---- MyWhoosh reader ----

    @Test(expected = IOException.class)
    public void myWhoosh_streamsWithoutAltitude_throw() throws Exception {
        MyWhooshRouteReader.fromStreams(null, null, null, new double[]{0, 1});
    }

    @Test(expected = IOException.class)
    public void myWhoosh_streamsWithoutDistance_throw() throws Exception {
        MyWhooshRouteReader.fromStreams(null, null, new double[]{0, 1}, null);
    }

    @Test
    public void myWhoosh_garbageFile_isNeitherFitNorGpx() {
        try {
            MyWhooshRouteReader.read(bytes("<html>nope</html>"));
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Geen FIT- of GPX-bestand"));
        }
    }
}
