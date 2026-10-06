package nl.paree.climbpro.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.BuildConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.util.concurrent.TimeUnit;

/** checkForUpdate against a local GitHub stand-in; callbacks arrive on the main looper. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateCheckerNetworkTest {

    private final Context app = ApplicationProvider.getApplicationContext();
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        // An OkHttp retry after a dropped connection must not block on an empty queue.
        ((okhttp3.mockwebserver.QueueDispatcher) server.getDispatcher()).setFailFast(true);
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    /** Records exactly one callback. */
    private static final class Recorder implements UpdateChecker.Callback {
        String event;
        String tag;
        String url;
        Exception error;

        @Override public void onUpdateAvailable(String tagName, String apkDownloadUrl) {
            event = "update";
            tag = tagName;
            url = apkDownloadUrl;
        }

        @Override public void onUpToDate() { event = "current"; }

        @Override public void onCheckFailed(Exception e) {
            event = "failed";
            error = e;
        }
    }

    private Recorder check() throws Exception {
        Recorder r = new Recorder();
        new UpdateChecker(app, server.url("/").toString()).checkForUpdate(r);
        long deadline = System.currentTimeMillis() + 10_000;
        while (r.event == null) {
            if (System.currentTimeMillis() > deadline) fail("no callback");
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        return r;
    }

    private void respond(String json) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(json));
    }

    private static String release(String tag, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/x\","
                + "\"assets\":" + assets + ",\"draft\":false}";
    }

    @Test
    public void newerReleaseOffersItsApkAsset() throws Exception {
        assertEquals("1.0.0-dev", BuildConfig.VERSION_NAME);
        respond(release("v1.0.1", "[{\"name\":\"notes.txt\",\"browser_download_url\":\"n\"},"
                + "{\"name\":null},"
                + "{\"name\":\"climbpro.apk\",\"browser_download_url\":\"https://dl/app.apk\"}]"));
        Recorder r = check();
        assertEquals("update", r.event);
        assertEquals("v1.0.1", r.tag);
        assertEquals("https://dl/app.apk", r.url);

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("/repos/SvenvanDalen/climbBrooo/releases/latest", req.getPath());
    }

    @Test
    public void sameOrOlderReleaseIsUpToDate() throws Exception {
        respond(release("v1.0.0", "[]"));
        assertEquals("current", check().event);
        respond(release("v96", "[]")); // legacy run-number tag reads as 0.0.96
        assertEquals("current", check().event);
    }

    @Test
    public void newerReleaseWithoutApkFails() throws Exception {
        respond(release("v2.0.0", "[{\"name\":\"source.zip\",\"browser_download_url\":\"z\"}]"));
        Recorder r = check();
        assertEquals("failed", r.event);
        assertTrue(r.error.getMessage().contains("no .apk asset"));

        respond("{\"tag_name\":\"v2.0.0\"}"); // no assets array at all
        assertEquals("failed", check().event);
    }

    @Test
    public void unrecognisedTagFails() throws Exception {
        respond(release("nightly", "[]"));
        Recorder r = check();
        assertEquals("failed", r.event);
        assertTrue(r.error.getMessage().contains("nightly"));
    }

    @Test
    public void httpErrorFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("rate limited"));
        Recorder r = check();
        assertEquals("failed", r.event);
        assertTrue(r.error.getMessage().contains("403"));
    }

    @Test
    public void malformedJsonOrDroppedConnectionFails() throws Exception {
        respond("{not json");
        assertEquals("failed", check().event);
        server.enqueue(new MockResponse().setSocketPolicy(
                okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        Recorder r = check();
        assertEquals("failed", r.event);
        assertNull(r.tag);
    }
}
