package nl.paree.climbpro.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;

/** Completion of the update download: only our own, successful, existing APK is installed. */
@RunWith(RobolectricTestRunner.class)
public class DownloadCompleteReceiverTest {

    private static final long ID = 4242L;

    private Context app;
    private DownloadManager dm;
    private Intent started;
    private Context ctx;
    private boolean cursorClosed;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        dm = mock(DownloadManager.class);
        started = null;
        ctx = new ContextWrapper(app) {
            @Override public Object getSystemService(String name) {
                return Context.DOWNLOAD_SERVICE.equals(name) ? dm : super.getSystemService(name);
            }

            @Override public void startActivity(Intent intent) {
                started = intent;
            }
        };
        app.getSharedPreferences("update_checker", Context.MODE_PRIVATE).edit()
                .putLong("pending_download_id", ID).commit();
    }

    private void cursor(String[] columns, Object... row) {
        MatrixCursor c = new MatrixCursor(columns) {
            @Override public void close() {
                cursorClosed = true;
                super.close();
            }
        };
        if (row.length > 0) c.addRow(row);
        when(dm.query(any(DownloadManager.Query.class))).thenReturn(c);
    }

    private static Intent complete(long id) {
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id);
    }

    private File apk() throws Exception {
        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        assertNotNull(dir);
        dir.mkdirs();
        File f = new File(dir, "climbpro-v1.2.0.apk");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{1, 2, 3});
        }
        return f;
    }

    private static final String[] COLS = {DownloadManager.COLUMN_STATUS,
            DownloadManager.COLUMN_LOCAL_URI};

    @Test
    public void successfulOwnDownloadLaunchesTheInstallerViaFileProvider() throws Exception {
        File f = apk();
        cursor(COLS, DownloadManager.STATUS_SUCCESSFUL, Uri.fromFile(f).toString());
        // Robolectric resolves external-files roots differently from a device, so the
        // FileProvider lookup itself is stubbed; the receiver's wiring is what is under test.
        Uri content = Uri.parse("content://" + app.getPackageName()
                + ".fileprovider/downloads/climbpro-v1.2.0.apk");
        try (MockedStatic<FileProvider> fp = mockStatic(FileProvider.class)) {
            fp.when(() -> FileProvider.getUriForFile(any(Context.class),
                    eq(app.getPackageName() + ".fileprovider"), eq(f))).thenReturn(content);
            new DownloadCompleteReceiver().onReceive(ctx, complete(ID));
        }

        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("application/vnd.android.package-archive", started.getType());
        assertEquals("content", started.getData().getScheme());
        assertEquals(app.getPackageName() + ".fileprovider", started.getData().getAuthority());
        assertTrue((started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue(cursorClosed);
    }

    @Test
    public void foreignDownloadIsIgnoredWithoutQuerying() {
        new DownloadCompleteReceiver().onReceive(ctx, complete(ID + 1));
        org.mockito.Mockito.verifyNoInteractions(dm);
        assertNull(started);
    }

    @Test
    public void failedDownloadDoesNotInstall() throws Exception {
        cursor(COLS, DownloadManager.STATUS_FAILED, Uri.fromFile(apk()).toString());
        new DownloadCompleteReceiver().onReceive(ctx, complete(ID));
        assertNull(started);
        assertTrue(cursorClosed);
    }

    @Test
    public void missingRowColumnsOrFileDoNotInstall() throws Exception {
        DownloadCompleteReceiver receiver = new DownloadCompleteReceiver();

        cursor(COLS); // no row
        receiver.onReceive(ctx, complete(ID));

        cursor(new String[]{DownloadManager.COLUMN_LOCAL_URI}, "file:///x.apk"); // no status
        receiver.onReceive(ctx, complete(ID));

        cursor(new String[]{DownloadManager.COLUMN_STATUS}, DownloadManager.STATUS_SUCCESSFUL);
        receiver.onReceive(ctx, complete(ID)); // no uri column

        cursor(COLS, DownloadManager.STATUS_SUCCESSFUL, null);
        receiver.onReceive(ctx, complete(ID)); // null uri

        File gone = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "gone.apk");
        cursor(COLS, DownloadManager.STATUS_SUCCESSFUL, Uri.fromFile(gone).toString());
        receiver.onReceive(ctx, complete(ID)); // deleted before the broadcast

        when(dm.query(any(DownloadManager.Query.class))).thenReturn(null);
        receiver.onReceive(ctx, complete(ID)); // provider returned no cursor

        assertNull(started);
    }
}
