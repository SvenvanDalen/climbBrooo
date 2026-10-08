package nl.paree.climbpro.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.DownloadManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.widget.WeekWidgetProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

/** In-app update download bookkeeping and the home-screen week widget. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateAndWidgetTest {

    private final Context app = ApplicationProvider.getApplicationContext();

    @Test
    public void downloadIsRememberedAsOursAndOthersAreIgnored() {
        UpdateChecker checker = new UpdateChecker(app);
        long id = checker.downloadAndInstall(
                "https://github.com/x/climbpro/releases/download/v1.2.0/app.apk", "v1.2.0");
        assertTrue(UpdateChecker.isOwnDownload(app, id));
        assertFalse(UpdateChecker.isOwnDownload(app, id + 1));

        Intent settings = checker.unknownAppsSettingsIntent();
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
        checker.canRequestPackageInstalls();

        DownloadCompleteReceiver receiver = new DownloadCompleteReceiver();
        receiver.onReceive(app, new Intent("other.action"));
        receiver.onReceive(app, new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        receiver.onReceive(app, new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id + 1));
        // Our own download: Robolectric's DownloadManager has not finished it, so no install.
        receiver.onReceive(app, new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, id));
        assertEquals(null, shadowOf((android.app.Application) app).getNextStartedActivity());
    }

    @Test
    public void versionTagsCompareNumerically() {
        assertEquals(0, UpdateChecker.compareVersions(
                UpdateChecker.parseVersion("v1.10.0"), UpdateChecker.parseVersion("1.10.0")));
        assertTrue(UpdateChecker.compareVersions(
                UpdateChecker.parseVersion("v1.10.0"), UpdateChecker.parseVersion("v1.9.3")) > 0);
    }

    @Test
    public void weekWidgetRendersWithData() throws Exception {
        UiTestData.seed(app);
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int id = shadowOf(mgr).createWidget(WeekWidgetProvider.class, R.layout.widget_week);
        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertNotNull(shadowOf(mgr).getViewFor(id));
        WeekWidgetProvider.refresh(app);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
