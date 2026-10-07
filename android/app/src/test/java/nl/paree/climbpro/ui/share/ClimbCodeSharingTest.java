package nl.paree.climbpro.ui.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.SharedClimbImporter;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.share.ClimbShareCode;
import nl.paree.climbpro.domain.share.ClimbShareExtractor;
import nl.paree.climbpro.domain.share.SharedClimb;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.HostActivity;
import nl.paree.climbpro.ui.UiTestEnv;
import nl.paree.climbpro.ui.climbs.ClimbDetailActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.Collections;
import java.util.concurrent.Executor;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClimbCodeSharingTest {

    private static final Executor DIRECT = Runnable::run;

    private Application app;
    private HostActivity activity;
    private RouteRepository routes;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestEnv.initWorkManager();
        UiTestData.seed(app);
        routes = new RouteRepository(app);
        activity = Robolectric.buildActivity(HostActivity.class).setup().get();
    }

    @After
    public void tearDown() {
        UiTestEnv.resetWorkManager();
    }

    private Intent nextShared() {
        Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertNotNull("expected a started activity", chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        return send;
    }

    @Test
    public void shareClimb_sendsDecodableCode() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);

        ClimbCodeSharing.shareClimb(activity, r, 0);

        Intent send = nextShared();
        String text = send.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(send.getStringExtra(Intent.EXTRA_SUBJECT).startsWith("Klim: "));
        ClimbShareCode.Payload p = ClimbShareCode.decode(text);
        assertNull(p.collectionName);
        assertEquals(1, p.climbs.size());
    }

    @Test
    public void shareClimb_homeClimb_refusesWithToast() throws Exception {
        routes.setClimbHome(UiTestData.ROUTE_ID, 0, true);
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);

        ClimbCodeSharing.shareClimb(activity, r, 0);

        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(UiTestEnv.latestToast().contains("thuisklim"));
    }

    @Test
    public void shareClimb_withoutGeometry_refusesWithToast() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);
        r.lats = null;

        ClimbCodeSharing.shareClimb(activity, r, 0);

        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(UiTestEnv.latestToast().contains("geen route"));
    }

    @Test
    public void shareCollection_sendsCodeWithAllClimbs() throws Exception {
        RouteCollection col = new RouteCollectionRepository(app).get(UiTestData.collectionId);

        ClimbCodeSharing.shareCollection(activity, DIRECT, col);
        UiTestEnv.settle();

        Intent send = nextShared();
        assertTrue(send.getStringExtra(Intent.EXTRA_SUBJECT).contains("Favorieten"));
        ClimbShareCode.Payload p = ClimbShareCode.decode(send.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals("Favorieten", p.collectionName);
        int expected = routes.loadRoute(UiTestData.ROUTE_ID).climbs.size() + 1;
        assertEquals(expected, p.climbs.size());
    }

    @Test
    public void shareCollection_namelessCollection_usesDefaultName() throws Exception {
        RouteCollection col = new RouteCollection();
        col.routeIds.add(UiTestData.ROUTE_ID);

        ClimbCodeSharing.shareCollection(activity, DIRECT, col);
        UiTestEnv.settle();

        Intent send = nextShared();
        assertEquals("Klimcollectie: Collectie", send.getStringExtra(Intent.EXTRA_SUBJECT));
    }

    @Test
    public void shareCollection_homeClimbsSkipped_noteToast() throws Exception {
        routes.setClimbHome(UiTestData.ROUTE_ID, 0, true);
        RouteCollection col = new RouteCollection();
        col.name = "Mix";
        col.routeIds.add(UiTestData.ROUTE_ID);
        col.routeIds.add(UiTestData.ROUTE_ID_2);

        ClimbCodeSharing.shareCollection(activity, DIRECT, col);
        UiTestEnv.settle();

        assertNotNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(UiTestEnv.latestToast().contains("thuisklim(men) niet gedeeld"));
    }

    @Test
    public void shareCollection_onlyHomeClimbs_explains() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);
        for (int i = 0; i < r.climbs.size(); i++) routes.setClimbHome(UiTestData.ROUTE_ID, i, true);
        RouteCollection col = new RouteCollection();
        col.name = "Thuis";
        col.routeIds.add(UiTestData.ROUTE_ID);

        ClimbCodeSharing.shareCollection(activity, DIRECT, col);
        UiTestEnv.settle();

        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(UiTestEnv.latestToast().contains("alleen thuisklimmen"));
    }

    @Test
    public void shareCollection_emptyOrDeletedRoutes_explains() {
        RouteCollection col = new RouteCollection();
        col.name = "Leeg";
        col.routeIds = null;
        col.climbs = null;
        RouteCollection gone = new RouteCollection();
        gone.routeIds.add("does-not-exist");

        ClimbCodeSharing.shareCollection(activity, DIRECT, col);
        UiTestEnv.settle();
        assertTrue(UiTestEnv.latestToast().contains("geen klimmen"));

        ClimbCodeSharing.shareCollection(activity, DIRECT, gone);
        UiTestEnv.settle();
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    private String codeForRoute1Climb() throws Exception {
        SharedClimb c = ClimbShareExtractor.extract(routes.loadRoute(UiTestData.ROUTE_ID), 0);
        return ClimbShareCode.encode(new ClimbShareCode.Payload(null, Collections.singletonList(c)));
    }

    private AlertDialog openImportDialog(Runnable onImported) {
        ClimbCodeSharing.showImportDialog(activity, DIRECT, onImported);
        UiTestEnv.settle();
        AlertDialog d = UiTestEnv.latestAlert();
        assertNotNull(d);
        return d;
    }

    private static EditText inputOf(AlertDialog d) {
        EditText e = findEdit(d.getWindow().getDecorView());
        assertNotNull(e);
        return e;
    }

    private static EditText findEdit(android.view.View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = findEdit(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    @Test
    public void importDialog_prefillsCodeFromClipboard() throws Exception {
        String code = codeForRoute1Climb();
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("code", "kijk: " + code));

        AlertDialog d = openImportDialog(null);

        assertTrue(inputOf(d).getText().toString().contains(code));
    }

    @Test
    public void importDialog_ignoresUnrelatedClipboardText() {
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("x", "boodschappenlijst"));

        AlertDialog d = openImportDialog(null);

        assertEquals("", inputOf(d).getText().toString());
    }

    @Test
    public void importDialog_invalidCode_showsError() {
        AlertDialog d = openImportDialog(null);
        inputOf(d).setText("CPC1:rommel!!");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        assertNotNull(UiTestEnv.latestToast());
        assertTrue(UiTestEnv.latestAlert() == null || UiTestEnv.latestAlert() == d
                || !UiTestEnv.latestAlert().isShowing());
    }

    @Test
    public void importDialog_knownClimb_offersNothingToImport() throws Exception {
        String code = codeForRoute1Climb();
        AlertDialog d = openImportDialog(null);
        inputOf(d).setText(code);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        AlertDialog preview = UiTestEnv.latestAlert();
        assertNotNull(preview);
        String msg = UiTestEnv.messageOf(preview);
        assertTrue(msg, msg.contains("(heb je al)"));
        assertTrue(msg, msg.contains("Deze klim heb je al"));
        android.widget.Button pos = preview.getButton(AlertDialog.BUTTON_POSITIVE);
        assertTrue(pos == null || pos.getVisibility() != android.view.View.VISIBLE);
    }

    @Test
    public void importDialog_newClimb_importsAndOpensIt() throws Exception {
        String code = codeForRoute1Climb();
        routes.deleteRoute(UiTestData.ROUTE_ID);
        int before = routes.loadCatalog().size();
        boolean[] called = {false};

        AlertDialog d = openImportDialog(() -> called[0] = true);
        inputOf(d).setText(code);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        AlertDialog preview = UiTestEnv.latestAlert();
        assertNotNull(preview);
        assertTrue(UiTestEnv.messageOf(preview).contains("1 nieuwe klim(men)"));
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        assertTrue(called[0]);
        assertEquals(before + 1, routes.loadCatalog().size());
        assertTrue(UiTestEnv.latestToast().startsWith("1 klim(men) geïmporteerd"));
        Intent open = shadowOf(activity).getNextStartedActivity();
        assertNotNull(open);
        assertEquals(ClimbDetailActivity.class.getName(), open.getComponent().getClassName());
    }

    @Test
    public void importDialog_collectionCode_createsCollection() throws Exception {
        StoredRoute r = routes.loadRoute(UiTestData.ROUTE_ID);
        SharedClimb c = ClimbShareExtractor.extract(r, 0);
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload("Vogezen",
                Collections.singletonList(c)));
        int collectionsBefore = new RouteCollectionRepository(app).loadAll().size();

        AlertDialog d = openImportDialog(null);
        inputOf(d).setText(code);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();
        AlertDialog preview = UiTestEnv.latestAlert();
        String msg = UiTestEnv.messageOf(preview);
        assertTrue(msg, msg.contains("Er komt een collectie"));
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        assertEquals(collectionsBefore + 1, new RouteCollectionRepository(app).loadAll().size());
        assertTrue(UiTestEnv.latestToast().contains("collectie \"Vogezen\" aangemaakt"));
        assertTrue(UiTestEnv.latestToast().contains("1 al bekend"));
        // Collection imports stay on the current screen.
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void importDialog_cancel_doesNothing() {
        AlertDialog d = openImportDialog(null);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        UiTestEnv.settle();
        assertFalse(d.isShowing());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void summary_climbWithoutDetectableClimb_isSkipped() throws Exception {
        double[] la = {50.0, 50.001, 50.002};
        double[] lo = {5.0, 5.0, 5.0};
        double[] el = {100, 100, 100};
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload(null,
                Collections.singletonList(new SharedClimb("Vlak", la, lo, el))));
        SharedClimbImporter.Preview p = new SharedClimbImporter(app).preview(code);

        String s = ClimbCodeSharing.summary(p);

        assertTrue(s, s.contains("• Vlak: geen klim gevonden"));
        assertTrue(s, s.endsWith("Er valt niets te importeren."));
    }

    @Test
    public void summary_unnamedClimb_isCalledKlim() throws Exception {
        double[] la = {50.0, 50.001};
        double[] lo = {5.0, 5.0};
        double[] el = {100, 100};
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload(null,
                Collections.singletonList(new SharedClimb("", la, lo, el))));
        SharedClimbImporter.Preview p = new SharedClimbImporter(app).preview(code);

        assertTrue(ClimbCodeSharing.summary(p).startsWith("• Klim"));
    }

    @Test
    public void previewDialog_titleShowsCollectionName() throws Exception {
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload("Alpen",
                Collections.singletonList(ClimbShareExtractor.extract(
                        routes.loadRoute(UiTestData.ROUTE_ID), 0))));
        AlertDialog d = openImportDialog(null);
        inputOf(d).setText(code);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        UiTestEnv.settle();

        AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
        android.widget.TextView title = preview.findViewById(androidx.appcompat.R.id.alertTitle);
        assertEquals("Collectie \"Alpen\"", title.getText().toString());
    }
}
