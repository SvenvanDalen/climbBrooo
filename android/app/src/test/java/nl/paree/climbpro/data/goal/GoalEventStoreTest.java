package nl.paree.climbpro.data.goal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
public class GoalEventStoreTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        new File(app.getFilesDir(), GoalEventStore.FILE).delete();
    }

    @Test
    public void noFileMeansNoEvent() {
        assertNull(new GoalEventStore(app).load());
    }

    @Test
    public void savesLoadsAndDeletes() throws Exception {
        GoalEvent e = new GoalEvent();
        e.name = "Marmotte";
        e.date = "2027-07-04";
        e.distanceKm = 174;
        e.elevationM = 5000;
        GoalEventStore store = new GoalEventStore(app);
        store.save(e);
        store.save(e); // overwrite works

        GoalEvent back = store.load();
        assertEquals("Marmotte", back.name);
        assertEquals("2027-07-04", back.date);
        assertEquals(174, back.distanceKm);
        assertEquals(5000, back.elevationM);

        assertTrue(store.delete());
        assertNull(store.load());
    }

    @Test
    public void corruptFileLoadsAsNoEvent() throws Exception {
        try (FileOutputStream out =
                     new FileOutputStream(new File(app.getFilesDir(), GoalEventStore.FILE))) {
            out.write("{kapot".getBytes());
        }
        assertNull(new GoalEventStore(app).load());
    }
}
