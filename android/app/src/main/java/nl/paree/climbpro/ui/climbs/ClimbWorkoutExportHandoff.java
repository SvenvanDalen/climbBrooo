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
 * Writes a climb workout (issue #223, {@link nl.paree.climbpro.domain.export.ClimbWorkoutWriter})
 * to the app cache and builds the share intent for it, like {@link ClimbGpxExportHandoff}.
 * The file keeps its readable name (trainer apps show it) inside a unique subdirectory, so two
 * exports of the same climb can't overwrite each other while a share target still reads one.
 */
public final class ClimbWorkoutExportHandoff {

    public static final String ZWO_MIME = "application/xml";
    public static final String ERG_MIME = "text/plain";
    static final String CACHE_SUBDIR = "shared_workouts";

    private ClimbWorkoutExportHandoff() {}

    public static File writeFile(Context ctx, String content, String fileName) throws IOException {
        File dir = new File(new File(ctx.getCacheDir(), CACHE_SUBDIR), UUID.randomUUID().toString());
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Kan map niet aanmaken");
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    public static Intent buildShareIntent(Uri uri, String mime) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return share;
    }
}
