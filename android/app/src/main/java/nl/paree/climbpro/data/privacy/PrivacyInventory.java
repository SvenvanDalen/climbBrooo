package nl.paree.climbpro.data.privacy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File-level view of the app's stored data for the privacy dashboard (issue #264): measures
 * and wipes the files behind each {@link PrivacyCategory}. Pure {@link java.io.File} code so
 * it runs in JVM tests against a temp folder; preference-backed categories report zero here
 * and are handled by the dashboard itself.
 */
public final class PrivacyInventory {

    /** Size of one category on disk. */
    public static final class Usage {
        public final PrivacyCategory category;
        public final int fileCount;
        public final long bytes;

        public Usage(PrivacyCategory category, int fileCount, long bytes) {
            this.category = category;
            this.fileCount = fileCount;
            this.bytes = bytes;
        }
    }

    private final File filesDir;
    private final File cacheDir;

    public PrivacyInventory(File filesDir, File cacheDir) {
        this.filesDir = filesDir;
        this.cacheDir = cacheDir;
    }

    public Usage usage(PrivacyCategory category) {
        List<File> files = filesOf(category);
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return new Usage(category, files.size(), bytes);
    }

    /** Every regular file behind {@code category}, recursing into its directories. */
    public List<File> filesOf(PrivacyCategory category) {
        List<File> out = new ArrayList<>();
        if (category == PrivacyCategory.CACHE) {
            collect(cacheDir, out);
            return out;
        }
        for (String path : category.paths()) {
            collect(new File(filesDir, path), out);
        }
        return out;
    }

    /**
     * Deletes every file behind {@code category} (directories are emptied, then removed).
     * Returns the number of files that could not be deleted.
     */
    public int deleteFiles(PrivacyCategory category) {
        int failed = 0;
        if (category == PrivacyCategory.CACHE) {
            File[] children = cacheDir.listFiles();
            if (children != null) {
                for (File c : children) failed += deleteRecursively(c);
            }
            return failed;
        }
        for (String path : category.paths()) {
            failed += deleteRecursively(new File(filesDir, path));
        }
        return failed;
    }

    /** "1,2 MB" / "340 kB" / "12 B" — Dutch decimal comma, base 1000 like Android's own UI. */
    public static String formatBytes(long bytes) {
        if (bytes < 1000) return bytes + " B";
        if (bytes < 1_000_000) return Math.round(bytes / 1000.0) + " kB";
        return String.format(Locale.GERMANY, "%.1f MB", bytes / 1_000_000.0);
    }

    private static void collect(File f, List<File> out) {
        if (!f.exists()) return;
        if (f.isFile()) {
            out.add(f);
            return;
        }
        File[] children = f.listFiles();
        if (children == null) return;
        for (File c : children) collect(c, out);
    }

    private static int deleteRecursively(File f) {
        if (!f.exists()) return 0;
        int failed = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) failed += deleteRecursively(c);
            }
            f.delete();
            return failed;
        }
        return f.delete() ? 0 : 1;
    }
}
