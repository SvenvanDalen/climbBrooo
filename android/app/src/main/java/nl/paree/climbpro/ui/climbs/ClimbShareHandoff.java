package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Turns a composed climb-share {@link Bitmap} (see {@link ClimbShareImageComposer}) into a
 * shareable PNG file and the {@code ACTION_SEND} intent that hands it to the standard Android
 * share sheet, so the user can post it to any social app.
 *
 * <p>Mirrors {@code ui.routes.GarminHandoff}'s split into pure, testable steps: {@link
 * #writeShareImage} serializes the bitmap to the app cache, {@link #buildShareIntent} assembles
 * the intent carrying it. Phone-only; no wire-format or watch impact (issue #33).
 */
public final class ClimbShareHandoff {

    public static final String IMAGE_MIME = "image/png";
    private static final String CACHE_SUBDIR = "shared_images";

    private ClimbShareHandoff() {}

    /**
     * Writes {@code bitmap} as a PNG under the app cache and returns the file. Each call uses
     * a fresh, uniquely-named file so sharing two climbs back-to-back (before the first share
     * target has finished reading its file) can't have one overwrite the other's image.
     */
    public static File writeShareImage(Context ctx, Bitmap bitmap) throws IOException {
        File dir = new File(ctx.getCacheDir(), CACHE_SUBDIR);
        dir.mkdirs();
        File file = new File(dir, "climb_share_" + UUID.randomUUID() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return file;
    }

    /** Builds the ACTION_SEND intent carrying {@code imageUri} to the system share sheet. */
    public static Intent buildShareIntent(Uri imageUri) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(IMAGE_MIME);
        share.putExtra(Intent.EXTRA_STREAM, imageUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }
}
