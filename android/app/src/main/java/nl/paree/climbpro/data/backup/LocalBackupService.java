package nl.paree.climbpro.data.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;
import nl.paree.climbpro.service.PlannedClimbWorkScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android side of the zip backup (issue #257): feeds {@link BackupArchive} from the app's
 * files and default preferences, writes it to a user-picked file or the auto-backup folder
 * (Storage Access Framework, so it can be a local folder or a cloud provider such as Drive),
 * and applies a restore. Call everything off the main thread.
 */
public final class LocalBackupService {

    public static final String PREF_TREE_URI = "auto_backup_tree_uri";
    public static final String PREF_LAST_MS = "auto_backup_last_ms";
    public static final String PREF_LAST_ERROR = "auto_backup_last_error";

    /** Device-specific keys that must not travel to another phone. */
    private static final Set<String> NOT_BACKED_UP = new java.util.HashSet<>(
            java.util.Arrays.asList(PREF_TREE_URI, PREF_LAST_MS, PREF_LAST_ERROR));

    private final Context ctx;
    private final SharedPreferences prefs;

    public LocalBackupService(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public BackupArchive.Summary writeTo(Uri target) throws IOException {
        try (OutputStream out = ctx.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("Kan " + target + " niet openen");
            return BackupArchive.write(ctx.getFilesDir(), backedUpPrefs(),
                    System.currentTimeMillis(), out);
        }
    }

    /**
     * Restores {@code source}, applies its preferences and re-arms reminders of upcoming
     * planned climbs (WorkManager jobs are not part of the backup).
     */
    public BackupArchive.Summary restoreFrom(Uri source) throws IOException {
        Map<String, Object> restored = new HashMap<>();
        BackupArchive.Summary summary;
        try (InputStream in = ctx.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Kan " + source + " niet openen");
            summary = BackupArchive.restore(in, ctx.getFilesDir(), restored);
        }
        applyPrefs(restored);
        long nowSec = System.currentTimeMillis() / 1000L;
        for (PlannedClimb p : PlannedClimbScheduler.upcoming(
                new PlannedClimbRepository(ctx).loadAll(), nowSec, ZoneId.systemDefault())) {
            if (!p.reminderSent) PlannedClimbWorkScheduler.schedule(ctx, p);
        }
        return summary;
    }

    /** Writes a dated backup into the auto-backup folder and prunes old ones. */
    public void writeAutoBackup() throws IOException {
        String tree = prefs.getString(PREF_TREE_URI, null);
        if (tree == null) return;
        Uri treeUri = Uri.parse(tree);
        ContentResolver resolver = ctx.getContentResolver();
        Uri dirDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try {
            Uri file = DocumentsContract.createDocument(resolver, dirDoc, BackupRetention.MIME,
                    BackupRetention.fileName(System.currentTimeMillis(), ZoneId.systemDefault()));
            if (file == null) throw new IOException("Map niet beschrijfbaar");
            writeTo(file);
            prune(resolver, treeUri);
            prefs.edit().putLong(PREF_LAST_MS, System.currentTimeMillis())
                    .remove(PREF_LAST_ERROR).apply();
        } catch (IOException | RuntimeException e) {
            prefs.edit().putString(PREF_LAST_ERROR, String.valueOf(e.getMessage())).apply();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private void prune(ContentResolver resolver, Uri treeUri) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        Map<String, String> idByName = new HashMap<>();
        try (Cursor c = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) idByName.put(c.getString(1), c.getString(0));
        }
        for (String name : BackupRetention.toDelete(new ArrayList<>(idByName.keySet()),
                BackupRetention.KEEP)) {
            try {
                DocumentsContract.deleteDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, idByName.get(name)));
            } catch (Exception ignored) {
                // Pruning is best effort; a leftover old backup is harmless.
            }
        }
    }

    private Map<String, ?> backedUpPrefs() {
        Map<String, Object> out = new HashMap<>(prefs.getAll());
        for (String k : NOT_BACKED_UP) out.remove(k);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyPrefs(Map<String, Object> restored) {
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, Object> e : restored.entrySet()) {
            if (NOT_BACKED_UP.contains(e.getKey())) continue;
            Object v = e.getValue();
            if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
            else if (v instanceof String) ed.putString(e.getKey(), (String) v);
            else if (v instanceof Set) ed.putStringSet(e.getKey(), (Set<String>) v);
        }
        ed.apply();
    }

    public boolean autoBackupEnabled() {
        return prefs.getString(PREF_TREE_URI, null) != null;
    }

    public List<String> status() {
        List<String> lines = new ArrayList<>();
        long last = prefs.getLong(PREF_LAST_MS, 0);
        String err = prefs.getString(PREF_LAST_ERROR, null);
        lines.add(autoBackupEnabled() ? "Automatische back-up: aan (dagelijks)" : "Automatische back-up: uit");
        if (last > 0) {
            lines.add("Laatste back-up: " + new java.text.SimpleDateFormat("d MMM yyyy HH:mm",
                    new java.util.Locale("nl")).format(new java.util.Date(last)));
        }
        if (err != null) lines.add("Laatste poging mislukt: " + err);
        return lines;
    }
}
