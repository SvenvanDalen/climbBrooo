package nl.paree.climbpro.data.route;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Persists a user-picked attempt photo (issue #46) under {@code getFilesDir()/attempt_photos/},
 * so it survives like the rest of the app's persisted data — unlike
 * {@code ui.climbs.ClimbShareHandoff}, which deliberately uses the cache dir for transient
 * share images that the OS may clear. Only the resulting filename is stored on
 * {@link StoredClimbAttempt#photoFileName}; callers resolve the full path via {@link #fileFor}.
 */
public final class AttemptPhotoStore {

    public static final String SUBDIR = "attempt_photos";

    private AttemptPhotoStore() {}

    /** Copies the picked photo's content into a new UUID-named file, returns its filename. */
    public static String savePickedPhoto(Context ctx, Uri source) throws IOException {
        File dir = dir(ctx);
        dir.mkdirs();
        String filename = UUID.randomUUID() + guessExtension(ctx, source);
        File dest = new File(dir, filename);
        ContentResolver resolver = ctx.getContentResolver();
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) throw new IOException("Could not open picked photo: " + source);
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return filename;
    }

    /** Resolves a stored {@link StoredClimbAttempt#photoFileName} to its on-disk file. */
    public static File fileFor(Context ctx, String filename) {
        return new File(dir(ctx), filename);
    }

    /** Best-effort delete, e.g. when replacing an attempt's photo with a new one. */
    public static void delete(Context ctx, String filename) {
        if (filename == null || filename.isEmpty()) return;
        fileFor(ctx, filename).delete();
    }

    private static File dir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), SUBDIR);
    }

    private static String guessExtension(Context ctx, Uri source) {
        String type = ctx.getContentResolver().getType(source);
        if (type != null && type.contains("png")) return ".png";
        return ".jpg";
    }
}
