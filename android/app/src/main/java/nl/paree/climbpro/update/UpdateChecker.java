package nl.paree.climbpro.update;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import nl.paree.climbpro.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls GitHub Releases for a build newer than the one currently installed.
 * {@code build-android.yml} tags every release {@code v<run_number>} and sets that
 * same number as this build's versionCode, so tag vs. versionCode is a direct
 * numeric comparison.
 */
public final class UpdateChecker {

    private static final String TAG  = "UpdateChecker";
    private static final String REPO = "SvenvanDalen/climbBrooo";
    private static final String OWNER;
    private static final String REPO_NAME;
    private static final Pattern TAG_VERSION = Pattern.compile("v?(\\d+)");

    static {
        int slash = REPO.indexOf('/');
        OWNER     = REPO.substring(0, slash);
        REPO_NAME = REPO.substring(slash + 1);
    }

    private final Context           appContext;
    private final ExecutorService   executor = Executors.newSingleThreadExecutor();
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** Called on the main thread when a newer release is available. */
        void onUpdateAvailable(String tagName, String apkDownloadUrl);
        /** Called on the main thread when the installed build is already current. */
        void onUpToDate();
        /** Called on the main thread if the check itself failed (network, parsing, ...). */
        void onCheckFailed(Exception e);
    }

    public UpdateChecker(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void checkForUpdate(Callback callback) {
        executor.execute(() -> {
            try {
                Retrofit retrofit = buildRetrofit();
                GitHubApiClient api = retrofit.create(GitHubApiClient.class);
                Response<GitHubReleaseDto> resp = api.latestRelease(OWNER, REPO_NAME).execute();

                if (!resp.isSuccessful() || resp.body() == null) {
                    postFailure(callback, new IllegalStateException(
                            "GitHub releases request failed: HTTP " + resp.code()));
                    return;
                }

                GitHubReleaseDto release = resp.body();
                int remoteVersion = parseVersion(release.tagName);
                if (remoteVersion < 0) {
                    postFailure(callback, new IllegalStateException(
                            "Unrecognised release tag: " + release.tagName));
                    return;
                }

                if (remoteVersion <= BuildConfig.VERSION_CODE) {
                    mainHandler.post(callback::onUpToDate);
                    return;
                }

                String apkUrl = findApkUrl(release);
                if (apkUrl == null) {
                    postFailure(callback, new IllegalStateException(
                            "Release " + release.tagName + " has no .apk asset"));
                    return;
                }

                String tagName = release.tagName;
                mainHandler.post(() -> callback.onUpdateAvailable(tagName, apkUrl));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                postFailure(callback, e);
            }
        });
    }

    /** True if this app is already allowed to install packages from unknown sources. */
    public boolean canRequestPackageInstalls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true; // pre-O: no such gate
        return appContext.getPackageManager().canRequestPackageInstalls();
    }

    /** Sends the user to the "install unknown apps" settings screen for this app. */
    public Intent unknownAppsSettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + appContext.getPackageName()));
        return intent;
    }

    /**
     * Enqueues the APK download via {@link DownloadManager}; {@link DownloadCompleteReceiver}
     * picks up the completion broadcast and launches the install prompt.
     */
    public long downloadAndInstall(String apkDownloadUrl, String tagName) {
        DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        String fileName = "climbpro-" + tagName + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkDownloadUrl))
                .setTitle("ClimbPro update " + tagName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive");
        return dm.enqueue(request);
    }

    private void postFailure(Callback callback, Exception e) {
        mainHandler.post(() -> callback.onCheckFailed(e));
    }

    private static String findApkUrl(GitHubReleaseDto release) {
        List<GitHubReleaseDto.Asset> assets = release.assets;
        if (assets == null) return null;
        for (GitHubReleaseDto.Asset asset : assets) {
            if (asset.name != null && asset.name.endsWith(".apk")) {
                return asset.browserDownloadUrl;
            }
        }
        return null;
    }

    /** Parses "v42" / "42" -> 42; returns -1 if the tag doesn't match the expected shape. */
    static int parseVersion(String tagName) {
        if (tagName == null) return -1;
        Matcher m = TAG_VERSION.matcher(tagName.trim());
        if (!m.matches()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Retrofit buildRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();
        return new Retrofit.Builder()
                .baseUrl(GitHubApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
    }
}
