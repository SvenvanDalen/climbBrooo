package nl.paree.climbpro.domain.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Corrupted and hostile GPX input (CLAUDE.md edge case: corrupted GPX). */
@RunWith(RobolectricTestRunner.class)
public class GpxParserCorruptTest {

    private static List<RoutePoint> parse(String gpx) throws GpxParseException {
        return GpxParser.parse(new ByteArrayInputStream(gpx.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void truncatedInsideAnAttribute_isMalformed() {
        try {
            parse("<gpx><trk><trkseg><trkpt lat=\"51.0\" lon=\"5.0\"></trkpt><trkpt lat=\"51.");
            fail("expected GpxParseException");
        } catch (GpxParseException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Malformed GPX XML"));
            assertNotNull(e.getCause());
        }
    }

    @Test
    public void mismatchedTags_areMalformed() {
        try {
            parse("<gpx><trk><trkpt lat=\"51\" lon=\"5\"></trk></trkpt></gpx>");
            fail("expected GpxParseException");
        } catch (GpxParseException e) {
            assertTrue(e.getMessage().startsWith("Malformed GPX XML"));
        }
    }

    @Test
    public void streamFailingMidway_isReportedAsIoError() {
        InputStream broken = new InputStream() {
            private final byte[] head = "<gpx><trk><trkseg><trkpt lat=\"51\" lon=\"5\">"
                    .getBytes(StandardCharsets.UTF_8);
            private int pos;

            @Override public int read() throws IOException {
                if (pos < head.length) return head[pos++];
                throw new IOException("connection reset");
            }
        };
        try {
            GpxParser.parse(broken);
            fail("expected GpxParseException");
        } catch (GpxParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("connection reset")
                    || e.getMessage().startsWith("I/O error"));
        }
    }

    @Test
    public void nonNumericCoordinates_areSkipped() throws Exception {
        List<RoutePoint> pts = parse("<gpx><trk><trkseg>"
                + "<trkpt lat=\"abc\" lon=\"5.0\"><ele>1</ele></trkpt>"
                + "<trkpt lat=\" 51.5 \" lon=\" 5.5 \"><ele> 12.5 </ele></trkpt>"
                + "</trkseg></trk></gpx>");
        assertEquals(1, pts.size());
        assertEquals(51.5, pts.get(0).lat, 1e-9);
        assertEquals(12.5, pts.get(0).elevation, 1e-9);
    }

    @Test
    public void elevationOutsideAPoint_isIgnored() throws Exception {
        List<RoutePoint> pts = parse("<gpx><metadata><ele>999</ele></metadata><trk><trkseg>"
                + "<trkpt lat=\"51\" lon=\"5\"/></trkseg></trk></gpx>");
        assertEquals(1, pts.size());
        assertTrue(Double.isNaN(pts.get(0).elevation));
    }

    @Test(expected = GpxParseException.class)
    public void emptyDocument_throws() throws Exception {
        parse("");
    }

    @Test
    public void largeTrack_isParsedCompletely() throws Exception {
        StringBuilder sb = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < 50_000; i++) {
            sb.append("<trkpt lat=\"").append(45 + i * 1e-5).append("\" lon=\"6\"><ele>")
                    .append(i % 1000).append("</ele></trkpt>");
        }
        sb.append("</trkseg></trk></gpx>");
        assertEquals(50_000, parse(sb.toString()).size());
    }
}
