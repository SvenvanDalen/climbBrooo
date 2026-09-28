package nl.paree.climbpro.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BackupRetentionTest {

    @Test
    public void fileName_isLocalTimestamp() {
        ZoneId ams = ZoneId.of("Europe/Amsterdam");
        long ms = ZonedDateTime.of(2026, 9, 24, 7, 30, 0, 0, ams).toInstant().toEpochMilli();
        assertEquals("climbpro-backup-2026-09-24-0730.zip", BackupRetention.fileName(ms, ams));
        assertTrue(BackupRetention.isBackupName(BackupRetention.fileName(ms, ams)));
    }

    @Test
    public void toDelete_keepsNewestAndIgnoresForeignFiles() {
        List<String> names = Arrays.asList(
                "climbpro-backup-2026-09-20-0700.zip",
                "climbpro-backup-2026-09-24-0700.zip",
                "vakantie.jpg",
                "climbpro-backup-2026-09-22-0700.zip",
                "climbpro-backup-2026-09-21-0700.zip",
                "climbpro-backup-2026-09-23-0700.zip",
                "climbpro-backup-2026-09-19-0700.zip",
                "climbpro-backup-kopie.zip");

        assertEquals(Arrays.asList(
                        "climbpro-backup-2026-09-20-0700.zip",
                        "climbpro-backup-2026-09-19-0700.zip"),
                BackupRetention.toDelete(names, 4));
    }

    @Test
    public void toDelete_nothingWhenWithinLimit() {
        assertEquals(Collections.emptyList(), BackupRetention.toDelete(
                Collections.singletonList("climbpro-backup-2026-09-20-0700.zip"), 5));
        assertFalse(BackupRetention.isBackupName(null));
    }
}
