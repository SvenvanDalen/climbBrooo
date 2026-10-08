package nl.paree.climbpro.ui.pain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.testsupport.UiTestData;
import nl.paree.climbpro.ui.UiTestEnv;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class PainLogLogicTest {

    private Application app;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        UiTestData.seed(app);
    }

    @Test
    public void viewModel_addLoadAndDelete() {
        PainLogViewModel vm = new PainLogViewModel(app);
        assertNull(vm.current());
        long now = System.currentTimeMillis() / 1000L;
        vm.add(UiTestData.RIDE_OUTDOOR, now - 100, Arrays.asList("KNEE"), 3, "Racer", "zadel +5mm",
                "na de klim");
        vm.add(0, now, Arrays.asList("BACK"), 2, null, null, null);
        assertEquals("Klacht gelogd", UiTestEnv.awaitValue(vm.message(), m -> true));
        PainLogViewModel.Snapshot s = UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 2);
        assertNotNull(s);
        assertTrue(s.entries.get(0).timestampEpochSec >= s.entries.get(1).timestampEpochSec);
        assertNotNull(s.summary);
        assertEquals(16, s.recentRides.size());
        assertSame(s, vm.current());

        vm.delete(s.entries.get(0).id);
        assertNotNull(UiTestEnv.awaitValue(vm.snapshot(), x -> x.entries.size() == 1));
        vm.load();
        vm.onCleared();
    }

    @Test
    public void adapter_bindsAreasSeverityDetailAndLongPress() {
        PainLogEntry full = new PainLogEntry();
        full.id = "a";
        full.timestampEpochSec = 1_700_000_000L;
        full.areas = Arrays.asList("KNEE", "UNKNOWN_AREA", "BACK");
        full.severity = 4;
        full.bike = "Racer";
        full.setup = "zadel hoger";
        full.note = "pijn na 60 km";
        PainLogEntry bare = new PainLogEntry();
        bare.id = "b";
        bare.severity = 1;
        bare.areas = null;

        PainLogEntry[] pressed = {null};
        PainLogAdapter a = new PainLogAdapter(e -> pressed[0] = e);
        a.submit(Arrays.asList(full, bare));
        assertEquals(2, a.getItemCount());
        FrameLayout parent = new FrameLayout(UiTestEnv.themed());

        PainLogAdapter.EntryVH h = a.onCreateViewHolder(parent, 0);
        parent.addView(h.itemView);
        a.onBindViewHolder(h, 0);
        assertEquals("Knie, Onderrug  •  4/5", h.areas.getText().toString());
        assertEquals("Racer · zadel hoger\npijn na 60 km", h.detail.getText().toString());
        assertEquals(View.VISIBLE, h.detail.getVisibility());
        assertEquals(a.formatDate(full.timestampEpochSec), h.date.getText().toString());
        h.itemView.performLongClick();
        assertSame(full, pressed[0]);

        a.onBindViewHolder(h, 1);
        assertEquals("Geen plek aangegeven  •  1/5", h.areas.getText().toString());
        assertEquals(View.GONE, h.detail.getVisibility());

        a.submit(null);
        assertEquals(0, a.getItemCount());
    }

    @Test
    public void adapter_detailWithOnlySetupOrNote() {
        PainLogEntry e = new PainLogEntry();
        e.setup = "nieuwe schoenplaatjes";
        PainLogAdapter a = new PainLogAdapter(null);
        a.submit(Collections.singletonList(e));
        FrameLayout parent = new FrameLayout(UiTestEnv.themed());
        PainLogAdapter.EntryVH h = a.onCreateViewHolder(parent, 0);
        parent.addView(h.itemView);
        a.onBindViewHolder(h, 0);
        assertEquals("nieuwe schoenplaatjes", h.detail.getText().toString());
        h.itemView.performLongClick(); // no listener

        e.setup = null;
        e.note = "alleen notitie";
        a.onBindViewHolder(h, 0);
        assertEquals("alleen notitie", h.detail.getText().toString());
        List<String> none = null;
        assertEquals("Geen plek aangegeven", PainLogAdapter.areasText(none));
    }
}
