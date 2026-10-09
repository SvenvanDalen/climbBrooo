package nl.paree.climbpro.ui.collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.testsupport.UiTestData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.List;

/** "Toevoegen aan collectie" for a route and a climb, and the climb list adapter. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CollectionMembershipDialogTest {

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
    }

    private static Activity host() {
        return Robolectric.buildActivity(CollectionListActivity.class).setup().get();
    }

    @Test
    public void createsFirstCollectionWhenThereIsNone() throws Exception {
        Activity a = host();
        CollectionMembershipDialog.showForRoute(a, "r9");
        settle();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        ((EditText) findEditText(d)).setText("Winter");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();

        List<RouteCollection> all = new RouteCollectionRepository(a).loadAll();
        assertEquals(1, all.size());
        assertEquals("Winter", all.get(0).name);
        assertTrue(all.get(0).routeIds.contains("r9"));
    }

    @Test
    public void togglesMembershipOfAClimb() throws Exception {
        UiTestData.seed(ApplicationProvider.getApplicationContext());
        Activity a = host();
        CollectionMembershipDialog.showForClimb(a, UiTestData.ROUTE_ID, 0);
        settle();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        d.getListView().performItemClick(d.getListView().getChildAt(0) != null
                ? d.getListView().getChildAt(0) : null, 0, 0);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();

        CollectionMembershipDialog.showForRoute(a, UiTestData.ROUTE_ID);
        settle();
        AlertDialog again = (AlertDialog) ShadowDialog.getLatestDialog();
        again.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); // "Nieuwe collectie"
        settle();
        AlertDialog create = (AlertDialog) ShadowDialog.getLatestDialog();
        create.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); // empty name: refused
        settle();
        assertEquals(1, new RouteCollectionRepository(a).loadAll().size());
    }

    private static android.view.View findEditText(AlertDialog d) {
        return find(d.getWindow().getDecorView());
    }

    private static android.view.View find(android.view.View v) {
        if (v instanceof EditText) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View f = find(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }
}
