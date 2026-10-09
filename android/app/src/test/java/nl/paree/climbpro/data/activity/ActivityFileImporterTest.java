package nl.paree.climbpro.data.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Garmin Connect file import into the climb logbook (issue #253). */
@RunWith(RobolectricTestRunner.class)
public class ActivityFileImporterTest {

    /** The test route ridden north at 5 m/s: a GPX track over its climb. */
    private static byte[] gpxOverTestRoute() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<gpx version=\"1.1\" creator=\"test\"><trk><trkseg>");
        long t0 = 1_760_000_000L;
        for (int m = 0; m <= 25_000; m += 25) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "<trkpt lat=\"%.7f\" lon=\"5.8\"><ele>100</ele><time>%s</time></trkpt>",
                    50.40 + m / 111_195.0, Instant.ofEpochSecond(t0 + m / 5)));
        }
        sb.append("</trkseg></trk></gpx>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void importsAttemptsOnceAndSkipsRepeats() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
        ActivityFileImporter importer = new ActivityFileImporter(app);

        ActivityFileImporter.Result first = importer.importFile("rit.gpx", gpxOverTestRoute());
        assertEquals(1, first.activities);
        assertTrue("new attempts " + first.newAttempts, first.newAttempts >= 1);

        ActivityFileImporter.Result again = importer.importFile("rit.gpx", gpxOverTestRoute());
        assertEquals(0, again.newAttempts);
        assertEquals(first.newAttempts, again.alreadyKnown);
    }

    @Test
    public void fileWithoutTimedTrackIsRefused() {
        Context app = ApplicationProvider.getApplicationContext();
        byte[] empty = ("<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><trkseg>"
                + "</trkseg></trk></gpx>").getBytes(StandardCharsets.UTF_8);
        try {
            new ActivityFileImporter(app).importFile("leeg.gpx", empty);
            fail("expected an IOException");
        } catch (IOException expected) {
            // "Geen rit met GPS-tijden gevonden"
        }
    }
}
