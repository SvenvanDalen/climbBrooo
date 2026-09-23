package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns a {@link nl.paree.climbpro.domain.climb.ClimbGpxWriter}-produced GPX string into a
 * shareable file and the {@code ACTION_SEND} intent that hands it to the standard Android
 * share sheet (issue #79). Mirrors {@link ClimbShareHandoff}'s split into pure, testable
 * steps: {@link #writeGpxFile} serializes the string to the app cache, {@link
 * #buildShareIntent} assembles the intent carrying it. Phone-only; no wire-format or watch
 * impact — this is a distinct export format from the Connect IQ payload.
 */
public final class ClimbGpxExportHandoff {

    public static final String GPX_MIME = "application/gpx+xml";
    private static final String CACHE_SUBDIR = "shared_gpx";

    private ClimbGpxExportHandoff() {}

    /**
     * Writes {@code gpx} under the app cache and returns the file. Each call uses a fresh,
     * uniquely-named file so exporting two climbs back-to-back can't have one overwrite the
     * other before the first share target has finished reading it.
     */
    public static File writeGpxFile(Context ctx, String gpx) throws IOException {
        File dir = new File(ctx.getCacheDir(), CACHE_SUBDIR);
        dir.mkdirs();
        File file = new File(dir, "climb_" + UUID.randomUUID() + ".gpx");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(gpx.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /** Builds the ACTION_SEND intent carrying {@code gpxUri} to the system share sheet. */
    public static Intent buildShareIntent(Uri gpxUri) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(GPX_MIME);
        share.putExtra(Intent.EXTRA_STREAM, gpxUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }
}
