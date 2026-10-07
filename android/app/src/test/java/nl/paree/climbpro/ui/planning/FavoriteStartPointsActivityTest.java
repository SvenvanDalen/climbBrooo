package nl.paree.climbpro.ui.planning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.FavoriteStartPoint;
import nl.paree.climbpro.data.planning.FavoriteStartPointStore;
import nl.paree.climbpro.ui.ActivityTestSupport;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FavoriteStartPointsActivityTest {

    private Application app;
    private FavoriteStartPointsActivity activity;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        activity = Robolectric.buildActivity(FavoriteStartPointsActivity.class,
                FavoriteStartPointsActivity.intentFor(app)).setup().get();
        UiTestEnv.settle();
    }

    private List<FavoriteStartPoint> stored() {
        return new FavoriteStartPointStore(
                new File(app.getFilesDir(), FavoriteStartPointStore.FILE_NAME)).loadAll();
    }

    private AlertDialog dialog() {
        assertTrue(UiTestEnv.waitFor(() -> ActivityTestSupport.showingDialog() != null));
        return (AlertDialog) ActivityTestSupport.showingDialog();
    }

    private void addByCoordinates(String name, String coords) {
        activity.findViewById(R.id.addCoordinatesButton).performClick();
        UiTestEnv.settle();
        AlertDialog d = dialog();
        List<EditText> fields = ActivityTestSupport.editTexts(d);
        fields.get(0).setText(name);
        fields.get(1).setText(coords);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
    }

    @Test
    public void startsEmpty() {
        assertEquals(View.VISIBLE, activity.findViewById(R.id.empty).getVisibility());
    }

    @Test
    public void addByCoordinates_validatesThenSaves() {
        activity.findViewById(R.id.addCoordinatesButton).performClick();
        UiTestEnv.settle();
        AlertDialog d = dialog();
        List<EditText> fields = ActivityTestSupport.editTexts(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(app.getString(R.string.fav_start_name_required), fields.get(0).getError());
        fields.get(0).setText("Thuis");
        fields.get(1).setText("geen coördinaten");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(app.getString(R.string.fav_start_coordinates_invalid), fields.get(1).getError());
        assertTrue(d.isShowing());

        fields.get(1).setText("50.85, 5.69");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        assertTrue(UiTestEnv.waitFor(() -> stored().size() == 1));
        assertEquals("Thuis", stored().get(0).name);
        assertTrue(UiTestEnv.waitFor(
                () -> activity.findViewById(R.id.empty).getVisibility() == View.GONE));
        assertEquals(app.getString(R.string.fav_start_saved, "Thuis"), UiTestEnv.latestToast());
    }

    @Test
    public void rowActions_renameAndDelete() {
        addByCoordinates("Werk", "52.09, 5.12");
        assertTrue(UiTestEnv.waitFor(() -> stored().size() == 1));
        ListView list = activity.findViewById(R.id.list);
        assertTrue(UiTestEnv.waitFor(() -> list.getAdapter().getCount() == 1));

        list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
        UiTestEnv.settle();
        AlertDialog actions = dialog();
        actions.getListView().performItemClick(null, 0, 0);
        UiTestEnv.settle();
        AlertDialog rename = dialog();
        EditText input = ActivityTestSupport.editTexts(rename).get(0);
        input.setText(" ");
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(input.getError());
        input.setText("Kantoor");
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> "Kantoor".equals(stored().get(0).name)));
        assertTrue(UiTestEnv.waitFor(
                () -> String.valueOf(list.getAdapter().getItem(0)).contains("Kantoor")));

        list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
        UiTestEnv.settle();
        dialog().getListView().performItemClick(null, 1, 1);
        UiTestEnv.settle();
        AlertDialog confirm = dialog();
        assertEquals(app.getString(R.string.fav_start_delete_confirm, "Kantoor"),
                UiTestEnv.messageOf(confirm));
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> stored().isEmpty()));
    }

    @Test
    public void addFromLocation_withFix_prefillsCoordinates() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(51.44);
        l.setLongitude(5.47);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);

        activity.findViewById(R.id.addLocationButton).performClick();
        AlertDialog d = dialog();
        assertEquals(1, ActivityTestSupport.editTexts(d).size());
        assertTrue(UiTestEnv.messageOf(d).contains("Huidige locatie"));
        ActivityTestSupport.editTexts(d).get(0).setText("Parkeerplaats");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(UiTestEnv.waitFor(() -> stored().size() == 1));
        assertEquals(51.44, stored().get(0).lat, 1e-9);
    }

    @Test
    public void addFromLocation_permittedButNoFix_fallsBackToCoordinates() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        activity.findViewById(R.id.addLocationButton).performClick();
        AlertDialog d = dialog();
        assertEquals(app.getString(R.string.fav_start_no_location), UiTestEnv.latestToast());
        assertEquals(2, ActivityTestSupport.editTexts(d).size());
    }

    @Test
    public void addFromLocation_withoutPermission_asksForIt() {
        activity.findViewById(R.id.addLocationButton).performClick();
        UiTestEnv.settle();
        assertNotNull(shadowOf(activity).getNextStartedActivityForResult());
    }
}
