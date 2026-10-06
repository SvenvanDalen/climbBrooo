package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.collections.CollectionListActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/** Climb rows in a route's climb list. */
@RunWith(RobolectricTestRunner.class)
public class ClimbListAdapterTest {

    private static Activity host() {
        return Robolectric.buildActivity(CollectionListActivity.class).setup().get();
    }

    @Test
    public void climbListAdapterBindsRowsAndClicks() throws Exception {
        UiTestData.seed(ApplicationProvider.getApplicationContext());
        Activity a = host();
        List<StoredClimb> climbs = new RouteRepository(a).loadRoute(UiTestData.ROUTE_ID).climbs;
        ClimbListAdapter adapter = new ClimbListAdapter();
        int[] clicked = {-1};
        adapter.setListener((c, i) -> clicked[0] = i);
        adapter.setItems(climbs);
        RecyclerView parent = new RecyclerView(a);
        parent.setLayoutParams(new FrameLayout.LayoutParams(400, 400));
        parent.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(a));
        ClimbListAdapter.ViewHolder h = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(h, 0);
        h.itemView.performClick();
        assertEquals(0, clicked[0]);
        assertEquals(climbs.size(), adapter.getItemCount());
        adapter.setItems(null);
        assertEquals(0, adapter.getItemCount());
    }

}
