package nl.paree.climbpro.data.bike;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import nl.paree.climbpro.data.FileCleanup;

/**
 * Persists bike-passport photos (issue #190) under {@code getFilesDir()/bike_passport_photos/},
 * same approach as {@code AttemptPhotoStore}: the picked image is copied into a UUID-named
 * file and only the filename is stored on the {@link BikePassport}.
 */
public final class BikePassportPhotoStore {

    public static final String SUBDIR = "bike_passport_photos";

    private BikePassportPhotoStore() {}

    /** Copies the picked photo's content into a new file, returns its filename. */
    public static String savePickedPhoto(Context ctx, Uri source) throws IOException {
        File dir = dir(ctx);
        dir.mkdirs();
        String type = ctx.getContentResolver().getType(source);
        String ext = ".jpg";
        if (type != null && type.contains("png")) ext = ".png";
        else if (type != null && type.contains("pdf")) ext = ".pdf";
        String filename = UUID.randomUUID() + ext;
        ContentResolver resolver = ctx.getContentResolver();
        try (InputStream in = resolver.openInputStream(source)) {
            if (in == null) throw new IOException("Could not open picked photo: " + source);
            try (OutputStream out = new FileOutputStream(new File(dir, filename))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return filename;
    }

    public static File fileFor(Context ctx, String filename) {
        return new File(dir(ctx), filename);
    }

    public static void delete(Context ctx, String filename) {
        if (filename == null || filename.isEmpty()) return;
        FileCleanup.deleteQuietly(fileFor(ctx, filename));
    }

    /** Deletes files no passport references (e.g. left behind by an aborted save). */
    public static void cleanupOrphans(Context ctx, List<BikePassport> passports) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        Set<String> referenced = new HashSet<>();
        for (BikePassport p : passports) referenced.addAll(p.allFileNames());
        for (File f : files) {
            if (!referenced.contains(f.getName())) FileCleanup.deleteQuietly(f);
        }
    }

    private static File dir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), SUBDIR);
    }
}
