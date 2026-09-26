package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class ClimbWorkoutExportHandoffTest {

    @Test
    public void writesReadableNameInUniqueDirectory() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        File a = ClimbWorkoutExportHandoff.writeFile(app, "<x/>", "stelvio.zwo");
        File b = ClimbWorkoutExportHandoff.writeFile(app, "<y/>", "stelvio.zwo");

        assertEquals("stelvio.zwo", a.getName());
        assertNotEquals(a.getParentFile(), b.getParentFile());
        assertEquals("<x/>", new String(Files.readAllBytes(a.toPath()), StandardCharsets.UTF_8));
        assertTrue(a.getPath().contains(ClimbWorkoutExportHandoff.CACHE_SUBDIR));
    }

    @Test
    public void shareIntentCarriesStreamAndGrant() {
        Uri uri = Uri.parse("content://x/stelvio.erg");
        Intent i = ClimbWorkoutExportHandoff.buildShareIntent(uri, ClimbWorkoutExportHandoff.ERG_MIME);
        assertEquals(Intent.ACTION_SEND, i.getAction());
        assertEquals("text/plain", i.getType());
        assertEquals(uri, i.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }
}
