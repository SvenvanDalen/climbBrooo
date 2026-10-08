package nl.paree.climbpro.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;
import nl.paree.climbpro.ui.climbs.ClimbLogbookActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;

/** Garmin ride import by share, open-with and the logbook's file picker. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ActivityImportActivityTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    /** The seeded test route ridden north at 5 m/s, as a GPX file. */
    private Uri rideFile(String name) throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>"
                + "<gpx version=\"1.1\" creator=\"test\"><trk><trkseg>");
        long t0 = 1_760_000_000L;
        for (int m = 0; m <= 25_000; m += 25) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "<trkpt lat=\"%.7f\" lon=\"5.8\"><ele>100</ele><time>%s</time></trkpt>",
                    50.40 + m / 111_195.0, Instant.ofEpochSecond(t0 + m / 5)));
        }
        sb.append("</trkseg></trk></gpx>");
        File f = new File(app.getCacheDir(), name);
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return Uri.fromFile(f);
    }

    private Uri junkFile() throws Exception {
        File f = new File(app.getCacheDir(), "kapot.gpx");
        Files.write(f.toPath(), "geen gpx".getBytes(StandardCharsets.UTF_8));
        return Uri.fromFile(f);
    }

    private ActivityController<ActivityImportActivity> open(Intent i) {
        i.setClass(app, ActivityImportActivity.class);
        ActivityController<ActivityImportActivity> c =
                Robolectric.buildActivity(ActivityImportActivity.class, i).setup();
        UiTestEnv.settle();
        return c;
    }

    private AlertDialog awaitSummary() {
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() != null, 10_000));
        return (AlertDialog) ActivityTestSupport.showingDialog();
    }

    @Test
    public void sharedRide_importsAttemptsAndOffersLogbook() throws Exception {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.putExtra(Intent.EXTRA_STREAM, rideFile("rit.gpx"));
        ActivityController<ActivityImportActivity> c = open(send);

        AlertDialog d = awaitSummary();
        String msg = UiTestEnv.messageOf(d);
        assertTrue(msg, msg.startsWith("1 rit(ten) gelezen"));
        assertEquals("Naar logboek", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        Intent next = shadowOf(c.get()).getNextStartedActivity();
        assertEquals(ClimbLogbookActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(c.get().isFinishing());
    }

    @Test
    public void sameRideTwice_reportsKnownAndPlainOk() throws Exception {
        Intent multi = new Intent(Intent.ACTION_SEND_MULTIPLE);
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(rideFile("a.gpx"));
        uris.add(rideFile("b.gpx"));
        uris.add(junkFile());
        multi.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ActivityController<ActivityImportActivity> c = open(multi);

        String msg = UiTestEnv.messageOf(awaitSummary());
        assertTrue(msg, msg.startsWith("2 rit(ten) gelezen"));
        assertTrue(msg, msg.contains("al bekend"));
        assertTrue(msg, msg.contains("kapot.gpx: "));
        c.pause().stop().destroy();
    }

    @Test
    public void openWithBrokenFile_showsErrorAndOk() throws Exception {
        ActivityController<ActivityImportActivity> c =
                open(new Intent(Intent.ACTION_VIEW, junkFile()));
        AlertDialog d = awaitSummary();
        String msg = UiTestEnv.messageOf(d);
        assertTrue(msg, msg.contains("kapot.gpx: ")); // name is the full path for file: URIs on Windows
        assertEquals("OK", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertNull(shadowOf(c.get()).getNextStartedActivity());
    }

    @Test
    public void missingFile_reportsCannotOpen() {
        ActivityController<ActivityImportActivity> c = open(new Intent(Intent.ACTION_VIEW,
                Uri.fromFile(new File(app.getCacheDir(), "weg.fit"))));
        String msg = UiTestEnv.messageOf(awaitSummary());
        assertTrue(msg, msg.contains("weg.fit: "));
        c.pause().stop().destroy();
    }

    @Test
    public void pickFromLogbook_launchesPicker_andCancelFinishes() {
        ActivityController<ActivityImportActivity> c =
                open(ActivityImportActivity.pickIntent(app));
        ActivityImportActivity a = c.get();
        org.robolectric.shadows.ShadowActivity.IntentForResult req =
                shadowOf(a).getNextStartedActivityForResult();
        assertNotNull(req);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, req.intent.getAction());
        shadowOf(a).receiveResult(req.intent, android.app.Activity.RESULT_CANCELED, null);
        UiTestEnv.settle();
        assertTrue(a.isFinishing());
    }

    @Test
    public void pickFromLogbook_importedRideJustSaysOk() throws Exception {
        ActivityController<ActivityImportActivity> c =
                open(ActivityImportActivity.pickIntent(app));
        ActivityImportActivity a = c.get();
        org.robolectric.shadows.ShadowActivity.IntentForResult req =
                shadowOf(a).getNextStartedActivityForResult();
        Intent result = new Intent();
        result.setData(rideFile("gekozen.gpx"));
        shadowOf(a).receiveResult(req.intent, android.app.Activity.RESULT_OK, result);
        AlertDialog d = awaitSummary();
        // The logbook is already underneath: no second one is offered.
        assertEquals("OK", d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
    }

    @Test
    public void plainLaunch_finishesImmediately() {
        ActivityController<ActivityImportActivity> c = open(new Intent());
        assertTrue(c.get().isFinishing());
    }
}
