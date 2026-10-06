package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.climb.ClimbUsageType;
import nl.paree.climbpro.domain.climb.RestSplitAdvisor;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.ui.UiTestEnv;

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
public class RouteDetailAdapterTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    private static StoredFlatSegment flat(int start, int surface) {
        StoredFlatSegment f = new StoredFlatSegment();
        f.startDistance = start;
        f.endDistance = start + 1500;
        f.length = 1500;
        f.surfaceType = surface;
        return f;
    }

    private static StoredClimb climb(int start, String name, String userName) {
        StoredClimb c = new StoredClimb();
        c.startDistance = start;
        c.endDistance = start + 2000;
        c.length = 2000;
        c.elevationGain = 140;
        c.avgGradient = 0.07;
        c.name = name;
        c.userDisplayName = userName;
        return c;
    }

    private static StoredStarredSegment starred(int start, String name, int surface) {
        StoredStarredSegment s = new StoredStarredSegment();
        s.stravaId = 7;
        s.startDistance = start;
        s.endDistance = start + 800;
        s.length = 800;
        s.name = name;
        s.surfaceType = surface;
        return s;
    }

    private static StoredSurfaceSection section(int start, String name, int surface) {
        StoredSurfaceSection s = new StoredSurfaceSection();
        s.startDistance = start;
        s.endDistance = start + 2500;
        s.surfaceType = surface;
        s.name = name;
        return s;
    }

    private RecyclerView.ViewHolder bind(RouteDetailAdapter a, int pos) {
        RecyclerView.ViewHolder h = a.onCreateViewHolder(new FrameLayout(app),
                a.getItemViewType(pos));
        a.onBindViewHolder(h, pos);
        return h;
    }

    @Test
    public void bindsEveryRowType_withNamesBadgesAndClicks() {
        StoredFlatSegment f = flat(0, SurfaceType.GRAVEL);
        StoredClimb c1 = climb(2000, null, null);
        StoredSurfaceSection sec = section(5000, null, SurfaceType.COBBLESTONE);
        StoredStarredSegment st = starred(6000, null, SurfaceType.UNKNOWN);
        StoredClimb c2 = climb(9000, "Côte", "Mijn klim");
        List<Object> items = new ArrayList<>(Arrays.asList(f, c1, sec, st, c2));

        RouteDetailAdapter a = new RouteDetailAdapter();
        Object[] clicked = new Object[6];
        a.setOnFlatClickListener(x -> clicked[0] = x);
        a.setOnFlatLongClickListener(x -> clicked[1] = x);
        a.setOnClimbClickListener((x, i) -> clicked[2] = i);
        a.setOnClimbLongClickListener((x, i) -> clicked[3] = x);
        a.setOnStarredClickListener(x -> clicked[4] = x);
        a.setOnSurfaceClickListener(x -> clicked[5] = x);
        a.setItems(items);
        assertEquals(5, a.getItemCount());

        RouteDetailAdapter.FlatViewHolder fh = (RouteDetailAdapter.FlatViewHolder) bind(a, 0);
        assertTrue(fh.distanceView.getText().toString().endsWith(" vlak"));
        assertEquals(View.VISIBLE, fh.surfaceBadge.getVisibility());
        assertEquals("G", fh.surfaceBadge.getText().toString());
        fh.itemView.performClick();
        fh.itemView.performLongClick();
        assertSame(f, clicked[0]);
        assertSame(f, clicked[1]);

        RouteDetailAdapter.ClimbViewHolder ch = (RouteDetailAdapter.ClimbViewHolder) bind(a, 1);
        assertEquals("Klim 1", ch.nameView.getText().toString());
        assertTrue(ch.statsView.getText().toString().contains("7.0% gem."));
        assertEquals(View.GONE, ch.restBadge.getVisibility());

        RouteDetailAdapter.SurfaceViewHolder sh = (RouteDetailAdapter.SurfaceViewHolder) bind(a, 2);
        assertTrue(sh.nameView.getText().toString().startsWith("Ondergrond-stuk · 5.0–7.5"));
        assertEquals("K", sh.surfaceBadge.getText().toString());
        sh.itemView.performClick();
        assertSame(sec, clicked[5]);

        RouteDetailAdapter.StarredViewHolder sth = (RouteDetailAdapter.StarredViewHolder) bind(a, 3);
        assertTrue(sth.nameView.getText().toString().startsWith("Ster-segment · "));
        assertEquals(View.INVISIBLE, sth.surfaceBadge.getVisibility());
        sth.itemView.performClick();
        assertSame(st, clicked[4]);

        // The second climb is climb index 1, whatever rows sit in between.
        RouteDetailAdapter.ClimbViewHolder ch2 = (RouteDetailAdapter.ClimbViewHolder) bind(a, 4);
        assertEquals("Mijn klim", ch2.nameView.getText().toString());
        ch2.itemView.performClick();
        ch2.itemView.performLongClick();
        assertEquals(1, clicked[2]);
        assertSame(c2, clicked[3]);
    }

    @Test
    public void climbRow_showsTargetTimeUsageAndRestBadge() {
        StoredClimb c = climb(0, "Muur", null);
        RouteDetailAdapter a = new RouteDetailAdapter();
        a.setItems(new ArrayList<>(Collections.singletonList(c)));
        a.setClimbTargetSeconds(new int[]{725});
        a.setClimbUsageTypes(new ClimbUsageType[]{ClimbUsageType.TRAINING});
        a.setRestSuggestions(Collections.singletonList(new RestSplitAdvisor.Suggestion(0, 1500)));

        RouteDetailAdapter.ClimbViewHolder h = (RouteDetailAdapter.ClimbViewHolder) bind(a, 0);
        String stats = h.statsView.getText().toString();
        assertTrue(stats, stats.contains("⏱ "));
        assertEquals(View.VISIBLE, h.restBadge.getVisibility());
        h.restBadge.performClick();
        assertEquals("Zwaar voor jou — overweeg een rustpunt na 1.5 km klimmen",
                UiTestEnv.latestToast());

        // No target (-1) and no suggestions: plain stats, hidden badge.
        a.setClimbTargetSeconds(new int[]{-1});
        a.setRestSuggestions(null);
        RouteDetailAdapter.ClimbViewHolder h2 = (RouteDetailAdapter.ClimbViewHolder) bind(a, 0);
        assertFalse(h2.statsView.getText().toString().contains("⏱"));
        assertEquals(View.GONE, h2.restBadge.getVisibility());
    }

    @Test
    public void rowsWithoutListeners_clickSafely_andNullItemsEmpty() {
        RouteDetailAdapter a = new RouteDetailAdapter();
        a.setItems(new ArrayList<>(Arrays.asList(flat(0, SurfaceType.UNKNOWN), climb(10, null, null),
                starred(20, "Stelvio", SurfaceType.ASPHALT), section(30, "Grind", SurfaceType.GRAVEL))));
        for (int i = 0; i < a.getItemCount(); i++) {
            RecyclerView.ViewHolder h = bind(a, i);
            h.itemView.performClick();
            if (i < 2) h.itemView.performLongClick(); // only flats and climbs handle long-press
        }
        RouteDetailAdapter.StarredViewHolder sh = (RouteDetailAdapter.StarredViewHolder) bind(a, 2);
        assertTrue(sh.nameView.getText().toString().startsWith("Stelvio · "));
        assertEquals("A", sh.surfaceBadge.getText().toString());
        RouteDetailAdapter.FlatViewHolder fh = (RouteDetailAdapter.FlatViewHolder) bind(a, 0);
        assertEquals(View.INVISIBLE, fh.surfaceBadge.getVisibility());

        a.setItems(null);
        assertEquals(0, a.getItemCount());

    }
}
