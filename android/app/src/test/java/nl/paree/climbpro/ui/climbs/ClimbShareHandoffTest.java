package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

/**
 * TDD for issue #33: sharing a composed climb image must write a real file to the app cache
 * and build an ACTION_SEND intent that actually carries it (mirrors {@code GarminHandoffTest}).
 */
@RunWith(RobolectricTestRunner.class)
public class ClimbShareHandoffTest {

    private Application ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void writeShareImage_writesFileToCache() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

        File f = ClimbShareHandoff.writeShareImage(ctx(), bitmap);

        assertNotNull(f);
        assertTrue(f.exists());
        assertTrue(f.getAbsolutePath().contains("shared_images"));
    }

    @Test
    public void buildShareIntent_usesSendActionAndImageMime() {
        Intent i = ClimbShareHandoff.buildShareIntent(Uri.parse("content://x/climb_share.png"));

        assertEquals(Intent.ACTION_SEND, i.getAction());
        assertEquals("image/png", i.getType());
    }

    @Test
    public void buildShareIntent_attachesImageUriAsExtraStream() {
        Uri uri = Uri.parse("content://nl.paree.climbpro.fileprovider/shared_images/climb_share.png");

        Intent i = ClimbShareHandoff.buildShareIntent(uri);

        assertEquals(uri, i.getParcelableExtra(Intent.EXTRA_STREAM));
    }

    @Test
    public void buildShareIntent_grantsReadUriPermission() {
        Intent i = ClimbShareHandoff.buildShareIntent(Uri.parse("content://x/climb_share.png"));

        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }
}
