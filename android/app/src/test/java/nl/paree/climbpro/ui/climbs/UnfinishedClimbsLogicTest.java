package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.IncompleteClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredIncompleteClimbAttempt;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.Construct;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UnfinishedClimbsLogicTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    private static StoredIncompleteClimbAttempt pass(String climbId, long activity, long date, int m) {
        StoredIncompleteClimbAttempt p = new StoredIncompleteClimbAttempt();
        p.climbId = climbId;
        p.activityId = activity;
        p.dateEpochSec = date;
        p.distanceCoveredM = m;
        return p;
    }

    @Test
    public void viewModel_listsOnlyNeverFinishedClimbs_resolvedToTheirRoute() throws Exception {
        StoredClimb r2climb = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID_2).climbs.get(0);
        String r2id = UiTestData.climbId(r2climb);
        StoredClimb finished = new RouteRepository(app).loadRoute(UiTestData.ROUTE_ID).climbs.get(0);
        List<StoredIncompleteClimbAttempt> passes = new ArrayList<>();
        passes.add(pass(r2id, 1, 1_700_000_000L, 600));
        passes.add(pass(r2id, 2, 1_700_100_000L, 900));
        passes.add(pass(UiTestData.climbId(finished), 3, 1_700_000_000L, 300));
        passes.add(pass("unknown-climb", 4, 1_600_000_000L, 200));
        new IncompleteClimbAttemptRepository(app).append(passes);

        UnfinishedClimbsViewModel vm = new UnfinishedClimbsViewModel(app);
        vm.loadUnfinished();
        List<UnfinishedClimbsViewModel.Row> rows = UiTestEnv.awaitValue(vm.rows(), l -> true);

        assertEquals(2, rows.size());
        UnfinishedClimbsViewModel.Row known = rows.get(0).routeId != null ? rows.get(0) : rows.get(1);
        UnfinishedClimbsViewModel.Row unknown = known == rows.get(0) ? rows.get(1) : rows.get(0);
        assertEquals(UiTestData.ROUTE_ID_2, known.routeId);
        assertEquals(0, known.climbIndex);
        assertEquals(2, known.attemptCount);
        assertEquals(900, known.bestDistanceCoveredM);
        assertTrue(known.lengthM > 0);
        assertEquals("Klim", unknown.displayName);
        assertNull(unknown.routeId);
        assertEquals(-1, unknown.climbIndex);
        vm.onCleared();
    }

    @Test
    public void adapter_bindsProgressAndClicks() {
        UnfinishedClimbsViewModel.Row resolved = Construct.of(UnfinishedClimbsViewModel.Row.class,
                "Kemmelberg", 1_700_000_000L, 450, 600, 3, "r1", 0);
        UnfinishedClimbsViewModel.Row overshoot = Construct.of(UnfinishedClimbsViewModel.Row.class,
                "Lang", 0L, 900, 600, 1, "r1", 1);
        UnfinishedClimbsViewModel.Row unresolved = Construct.of(UnfinishedClimbsViewModel.Row.class,
                "Klim", 1_700_000_000L, 120, 0, 1, null, -1);
        UnfinishedClimbsViewModel.Row[] clicked = {null};
        UnfinishedClimbsAdapter a = new UnfinishedClimbsAdapter(r -> clicked[0] = r);
        a.submit(Arrays.asList(resolved, overshoot, unresolved));
        assertEquals(3, a.getItemCount());
        UnfinishedClimbsAdapter.RowVH h = a.onCreateViewHolder(new FrameLayout(app), 0);

        a.onBindViewHolder(h, 0);
        assertEquals("Kemmelberg", h.name.getText().toString());
        assertTrue(h.stats.getText().toString().contains("75% van 600 m  •  3 poging(en)"));
        h.itemView.performClick();
        assertSame(resolved, clicked[0]);

        a.onBindViewHolder(h, 1);
        assertTrue(h.stats.getText().toString().startsWith("onbekende datum  •  100% van 600 m"));

        a.onBindViewHolder(h, 2);
        assertTrue(h.stats.getText().toString().contains("120 m afgelegd"));

        UnfinishedClimbsAdapter noClick = new UnfinishedClimbsAdapter(null);
        noClick.submit(Collections.singletonList(resolved));
        UnfinishedClimbsAdapter.RowVH h2 = noClick.onCreateViewHolder(new FrameLayout(app), 0);
        noClick.onBindViewHolder(h2, 0);
        h2.itemView.performClick();
        assertNotNull(h2.name.getText());
    }
}
