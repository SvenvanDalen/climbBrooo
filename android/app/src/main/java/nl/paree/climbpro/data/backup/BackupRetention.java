package nl.paree.climbpro.data.backup;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Naming and pruning of automatic backups in the user's chosen folder (issue #257). Names
 * sort chronologically ({@code climbpro-backup-2026-09-24-0730.zip}), so retention is a plain
 * sort. Files that don't match the pattern are never touched — the folder may hold other data.
 */
public final class BackupRetention {

    public static final int KEEP = 5;
    public static final String MIME = "application/zip";

    private static final Pattern NAME = Pattern.compile(
            "climbpro-backup-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US);

    private BackupRetention() {}

    public static String fileName(long nowMs, ZoneId zone) {
        return "climbpro-backup-" + STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + ".zip";
    }

    public static boolean isBackupName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Backups to delete so that only the newest {@code keep} remain. */
    public static List<String> toDelete(List<String> names, int keep) {
        List<String> backups = new ArrayList<>();
        for (String n : names) if (isBackupName(n)) backups.add(n);
        Collections.sort(backups, Collections.reverseOrder());
        return backups.size() <= keep ? new ArrayList<>()
                : new ArrayList<>(backups.subList(keep, backups.size()));
    }
}
