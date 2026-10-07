package nl.paree.climbpro.ui.records;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;

import java.util.List;

/** FTP test screen: plan with targets, export, detected result and the explicit apply. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FtpTestActivityTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.resetViewModelFactory();
        UiTestData.seed(app);
    }

    private FtpTestActivity open() {
        ActivityController<FtpTestActivity> c = Robolectric.buildActivity(FtpTestActivity.class,
                FtpTestActivity.intentFor(app)).setup();
        FtpTestActivity a = c.get();
        TextView result = a.findViewById(R.id.result);
        UiTestEnv.waitFor(() -> result.getText().length() > 0);
        return a;
    }

    /** Renames the newest seeded ride to an FTP test: 20 min at 262 W gives FTP 249. */
    private void nameRecentRideFtpTest() throws Exception {
        RideRepository repo = new RideRepository(app);
        List<StoredRide> rides = repo.loadAll();
        StoredRide newest = rides.get(0);
        for (StoredRide r : rides) if (r.startEpochSec > newest.startEpochSec) newest = r;
        newest.name = "FTP test op de rollen";
        repo.upsertAll(java.util.Collections.singletonList(newest));
    }

    @Test
    public void noResult_showsTargetsPhasesAndHint() {
        FtpTestActivity a = open();
        String target = ((TextView) a.findViewById(R.id.target)).getText().toString();
        assertTrue(target, target.contains("260"));
        LinearLayout phases = a.findViewById(R.id.phases);
        assertTrue(phases.getChildCount() >= 5);
        assertEquals(View.GONE, a.findViewById(R.id.apply).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.dismiss).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.exported_at).getVisibility());
    }

    @Test
    public void withoutFtp_showsPercentages() {
        new RiderProfileRepository(app).save(new RiderProfile(0, 74, 8.5));
        FtpTestActivity a = open();
        assertEquals(app.getString(R.string.ftp_test_no_ftp),
                ((TextView) a.findViewById(R.id.target)).getText().toString());
        assertTrue(UiTestEnv.hasText(a.findViewById(R.id.phases), "%"));
    }

    @Test
    public void namedTest_offersApplyAfterConfirmation() throws Exception {
        nameRecentRideFtpTest();
        FtpTestActivity a = open();
        TextView apply = a.findViewById(R.id.apply);
        assertTrue(UiTestEnv.waitFor(() -> apply.getVisibility() == View.VISIBLE));
        assertEquals(app.getString(R.string.ftp_test_apply, 249), apply.getText().toString());

        apply.performClick();
        UiTestEnv.settle();
        AlertDialog confirm = (AlertDialog) ActivityTestSupport.showingDialog();
        assertEquals(app.getString(R.string.ftp_test_apply_confirm_message, 260, 249),
                UiTestEnv.messageOf(confirm));
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> new RiderProfileRepository(app).load().ftpWatts == 249));
        assertEquals(app.getString(R.string.ftp_test_applied, 249), UiTestEnv.latestToast());
        // Handled: the result disappears.
        assertTrue(UiTestEnv.waitFor(() -> apply.getVisibility() == View.GONE));
    }

    @Test
    public void namedTest_dismissKeepsFtp() throws Exception {
        nameRecentRideFtpTest();
        FtpTestActivity a = open();
        View dismiss = a.findViewById(R.id.dismiss);
        assertTrue(UiTestEnv.waitFor(() -> dismiss.getVisibility() == View.VISIBLE));
        dismiss.performClick();
        assertTrue(UiTestEnv.waitFor(() -> dismiss.getVisibility() == View.GONE));
        assertEquals(260, new RiderProfileRepository(app).load().ftpWatts);
    }

    @Test
    public void export_writesZwoAndOffersShare() {
        FtpTestActivity a = open();
        a.findViewById(R.id.export).performClick();
        boolean quirk = false;
        for (int i = 0; i < 30 && !quirk && ActivityTestSupport.showingDialog() == null; i++) {
            quirk = ActivityTestSupport.settleTolerant();
        }
        if (!quirk) {
            AlertDialog d = (AlertDialog) ActivityTestSupport.showingDialog();
            assertTrue(d != null);
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            UiTestEnv.settle();
            assertEquals("application/xml", ActivityTestSupport.nextStarted(a).getType());
        }
        assertTrue(new RiderProfileRepository(app).loadFtpTestExportedAt() > 0);
        TextView exported = a.findViewById(R.id.exported_at);
        assertTrue(UiTestEnv.waitFor(() -> exported.getVisibility() == View.VISIBLE));
    }
}
