package nl.paree.climbpro.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-backup into a Storage Access Framework folder, with the document provider replaced by a
 * mocked {@link ContentResolver}.
 */
@RunWith(RobolectricTestRunner.class)
public class LocalBackupServiceAutoBackupTest {

    private static final Uri TREE =
            Uri.parse("content://com.example.docs/tree/primary%3ABackups");
    private static final Uri CREATED =
            Uri.parse("content://com.example.docs/tree/primary%3ABackups/document/new");

    /** Hidden DocumentsContract method names. */
    private static final String CREATE = "android:createDocument";
    private static final String DELETE = "android:deleteDocument";

    private Application app;
    private ContentResolver resolver;
    private Context ctx;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app,
                new androidx.work.Configuration.Builder().setExecutor(r -> { }).build());
        resolver = mock(ContentResolver.class);
        ctx = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().commit();
    }

    @After
    public void tearDown() {
        androidx.work.impl.WorkManagerImpl.setDelegate(null);
    }

    private static Bundle withUri(Uri uri) {
        Bundle b = new Bundle();
        if (uri != null) b.putParcelable("uri", uri);
        return b;
    }

    /** Both call() overloads, whichever DocumentsContract uses on this SDK. */
    private void stubProviderCall(Bundle result) throws Exception {
        when(resolver.call(anyString(), anyString(), isNull(), any(Bundle.class)))
                .thenReturn(result);
        when(resolver.call(any(Uri.class), anyString(), isNull(), any(Bundle.class)))
                .thenReturn(result);
    }

    private int providerCalls(String method) throws Exception {
        int n = 0;
        for (org.mockito.invocation.Invocation inv :
                org.mockito.Mockito.mockingDetails(resolver).getInvocations()) {
            if (inv.getMethod().getName().equals("call") && method.equals(inv.getArgument(1))) n++;
        }
        return n;
    }

    private static MatrixCursor listing(String... names) {
        MatrixCursor c = new MatrixCursor(new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME});
        for (String n : names) c.addRow(new Object[]{"id-" + n, n});
        return c;
    }

    @Test
    public void writeAutoBackup_writesZip_prunesOldBackups_andRecordsSuccess() throws Exception {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString())
                .putString(LocalBackupService.PREF_LAST_ERROR, "oud").commit();
        stubProviderCall(withUri(CREATED));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        when(resolver.openOutputStream(CREATED, "wt")).thenReturn(zip);
        when(resolver.query(any(Uri.class), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(listing(
                        "climbpro-backup-2026-09-01-0700.zip",
                        "climbpro-backup-2026-09-02-0700.zip",
                        "climbpro-backup-2026-09-03-0700.zip",
                        "climbpro-backup-2026-09-04-0700.zip",
                        "climbpro-backup-2026-09-05-0700.zip",
                        "climbpro-backup-2026-09-06-0700.zip",
                        "climbpro-backup-2026-09-07-0700.zip",
                        "vakantiefoto.jpg"));

        new LocalBackupService(ctx).writeAutoBackup();

        assertTrue("a zip was written", zip.size() > 0);
        assertTrue(prefs.getLong(LocalBackupService.PREF_LAST_MS, 0) > 0);
        assertNull(prefs.getString(LocalBackupService.PREF_LAST_ERROR, null));
        assertEquals("two oldest backups pruned, foreign file untouched",
                2, providerCalls(DELETE));
    }

    @Test
    public void writeAutoBackup_pruneFailures_areIgnored() throws Exception {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString()).commit();
        when(resolver.call(anyString(), eq(CREATE), isNull(),
                any(Bundle.class))).thenReturn(withUri(CREATED));
        when(resolver.call(anyString(), eq(DELETE), isNull(),
                any(Bundle.class))).thenThrow(new IllegalStateException("provider gone"));
        when(resolver.openOutputStream(CREATED, "wt")).thenReturn(new ByteArrayOutputStream());
        when(resolver.query(any(Uri.class), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(listing("climbpro-backup-2026-09-01-0700.zip",
                        "climbpro-backup-2026-09-02-0700.zip",
                        "climbpro-backup-2026-09-03-0700.zip",
                        "climbpro-backup-2026-09-04-0700.zip",
                        "climbpro-backup-2026-09-05-0700.zip",
                        "climbpro-backup-2026-09-06-0700.zip"));

        new LocalBackupService(ctx).writeAutoBackup();

        assertTrue(prefs.getLong(LocalBackupService.PREF_LAST_MS, 0) > 0);
    }

    @Test
    public void writeAutoBackup_nullListing_skipsPrune() throws Exception {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString()).commit();
        stubProviderCall(withUri(CREATED));
        when(resolver.openOutputStream(CREATED, "wt")).thenReturn(new ByteArrayOutputStream());

        new LocalBackupService(ctx).writeAutoBackup();

        assertEquals(0, providerCalls(DELETE));
        assertTrue(prefs.getLong(LocalBackupService.PREF_LAST_MS, 0) > 0);
    }

    @Test
    public void writeAutoBackup_folderNotWritable_recordsError() throws Exception {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString()).commit();
        stubProviderCall(withUri(null));

        try {
            new LocalBackupService(ctx).writeAutoBackup();
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals("Map niet beschrijfbaar", e.getMessage());
        }
        assertEquals("Map niet beschrijfbaar",
                prefs.getString(LocalBackupService.PREF_LAST_ERROR, null));
        assertEquals(0, prefs.getLong(LocalBackupService.PREF_LAST_MS, 0));
        assertEquals(0, providerCalls(DELETE));
    }

    @Test
    public void writeAutoBackup_writeFails_deletesHalfWrittenFileAndWrapsRuntimeError()
            throws Exception {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString()).commit();
        stubProviderCall(withUri(CREATED));
        when(resolver.openOutputStream(CREATED, "wt"))
                .thenThrow(new SecurityException("permission revoked"));

        try {
            new LocalBackupService(ctx).writeAutoBackup();
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getCause() instanceof SecurityException);
        }
        assertEquals(1, providerCalls(DELETE));
        assertEquals("permission revoked",
                prefs.getString(LocalBackupService.PREF_LAST_ERROR, null));
    }

    @Test
    public void writeTo_unopenableTarget_throws() throws Exception {
        when(resolver.openOutputStream(CREATED, "wt")).thenReturn(null);
        try {
            new LocalBackupService(ctx).writeTo(CREATED);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Kan "));
        }
    }

    @Test
    public void restoreFrom_unopenableSource_throws() throws Exception {
        when(resolver.openInputStream(CREATED)).thenReturn(null);
        try {
            new LocalBackupService(ctx).restoreFrom(CREATED);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Kan "));
        }
    }

    @Test
    public void roundTrip_restoresEveryPreferenceType_butNotDeviceKeys() throws Exception {
        Set<String> tags = new HashSet<>(Arrays.asList("klim", "gravel"));
        prefs.edit()
                .putBoolean("b", true)
                .putInt("i", 7)
                .putLong("l", 8L)
                .putFloat("f", 1.5f)
                .putString("s", "tekst")
                .putStringSet("set", tags)
                .putString(LocalBackupService.PREF_TREE_URI, "content://device-a")
                .commit();
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        when(resolver.openOutputStream(CREATED, "wt")).thenReturn(zip);
        LocalBackupService service = new LocalBackupService(ctx);
        service.writeTo(CREATED);

        prefs.edit().clear().putString(LocalBackupService.PREF_TREE_URI, "content://device-b")
                .commit();
        when(resolver.openInputStream(CREATED))
                .thenReturn(new ByteArrayInputStream(zip.toByteArray()));
        service.restoreFrom(CREATED);

        assertTrue(prefs.getBoolean("b", false));
        assertEquals(7, prefs.getInt("i", 0));
        assertEquals(8L, prefs.getLong("l", 0));
        assertEquals(1.5f, prefs.getFloat("f", 0), 0f);
        assertEquals("tekst", prefs.getString("s", null));
        assertEquals(tags, prefs.getStringSet("set", null));
        assertEquals("device keys stay local",
                "content://device-b", prefs.getString(LocalBackupService.PREF_TREE_URI, null));
    }

    @Test
    public void status_reportsEnabledLastBackupAndError() {
        prefs.edit().putString(LocalBackupService.PREF_TREE_URI, TREE.toString())
                .putLong(LocalBackupService.PREF_LAST_MS, 1_790_000_000_000L)
                .putString(LocalBackupService.PREF_LAST_ERROR, "schijf vol")
                .commit();

        List<String> lines = new LocalBackupService(ctx).status();

        assertEquals(3, lines.size());
        assertEquals("Automatische back-up: aan (dagelijks)", lines.get(0));
        assertTrue(lines.get(1).startsWith("Laatste back-up: "));
        assertEquals("Laatste poging mislukt: schijf vol", lines.get(2));
    }

    @Test
    public void status_disabled_singleLine() {
        List<String> lines = new LocalBackupService(ctx).status();
        assertEquals(1, lines.size());
        assertEquals("Automatische back-up: uit", lines.get(0));
        assertNotNull(lines);
        assertFalse(new LocalBackupService(ctx).autoBackupEnabled());
    }
}
