package nl.paree.climbpro.data;

import android.util.Log;

import java.io.File;

/**
 * Best-effort file removal for cleanup paths (an orphaned {@code .tmp} after a failed atomic
 * write, stale cache entries, a migration wipe). A failed delete is logged instead of silently
 * dropped, but never thrown: callers are already on an error or housekeeping path.
 */
public final class FileCleanup {

    private static final String TAG = "FileCleanup";

    private FileCleanup() {}

    /** @return true when {@code f} no longer exists afterwards */
    public static boolean deleteQuietly(File f) {
        if (f == null || !f.exists()) return true;
        if (f.delete()) return true;
        Log.w(TAG, "Could not delete " + f);
        return false;
    }
}
