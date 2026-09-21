package nl.paree.climbpro.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Fires on {@link DownloadManager#ACTION_DOWNLOAD_COMPLETE} for the APK download
 * started by {@link UpdateChecker#downloadAndInstall}, then launches the package
 * installer via a {@link FileProvider} URI.
 */
public final class DownloadCompleteReceiver extends BroadcastReceiver {

    private static final String TAG = "DownloadCompleteRcvr";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (downloadId < 0) return;
        // DownloadManager broadcasts ACTION_DOWNLOAD_COMPLETE for every download on the
        // device, not just ones this app started — ignore anything that isn't our update APK.
        if (!UpdateChecker.isOwnDownload(context, downloadId)) return;

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(downloadId));
        try {
            if (cursor == null || !cursor.moveToFirst()) return;

            int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusCol < 0 || cursor.getInt(statusCol) != DownloadManager.STATUS_SUCCESSFUL) {
                Log.w(TAG, "Update download " + downloadId + " did not finish successfully");
                return;
            }

            int uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            if (uriCol < 0) return;
            String localUriString = cursor.getString(uriCol);
            if (localUriString == null) return;

            File apkFile = new File(Uri.parse(localUriString).getPath());
            if (!apkFile.exists()) {
                Log.w(TAG, "Downloaded APK not found at " + apkFile);
                return;
            }

            Uri contentUri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", apkFile);

            Intent installIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(installIntent);
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
