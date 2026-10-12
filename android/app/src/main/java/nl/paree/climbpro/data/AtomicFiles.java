package nl.paree.climbpro.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe replacement of a small JSON store file, shared by the JSON-file repositories.
 * The bytes go to a synced sibling {@code <name>.tmp} first and are then renamed over the
 * target, so a reader sees either the old or the new file, never a half-written one.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    /**
     * Writes {@code data} to {@code target} via a synced {@code .tmp} sibling and a rename.
     * The rename is atomic where the filesystem supports it and a plain replace otherwise
     * ({@link File#renameTo} does not overwrite on every filesystem, so {@link Files#move}
     * is used). On failure the {@code .tmp} is removed again and the exception rethrown.
     */
    public static void write(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.getFD().sync();
            }
            moveIntoPlace(tmp, target);
        } catch (IOException failed) {
            FileCleanup.deleteQuietly(tmp);
            throw failed;
        }
    }

    private static void moveIntoPlace(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
