package nl.paree.climbpro.data.comeback;

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
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class ComebackPlanStoreTest {

    private Application app;
    private File file;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        file = new File(app.getFilesDir(), ComebackPlanStore.FILE);
        file.delete();
    }

    @Test
    public void saveOverwriteLoadAndClear() throws Exception {
        ComebackPlanStore store = new ComebackPlanStore(app);
        assertNull(store.load());
        store.save(100, 200, false);
        store.save(111, 222, true);
        ComebackPlanStore.Saved s = new ComebackPlanStore(app).load();
        assertEquals(111, s.lastRideBeforeBreakEpochSec);
        assertEquals(222, s.planStartEpochSec);
        assertTrue(s.injury);
        store.clear();
        assertNull(store.load());
    }

    @Test
    public void corruptFile_meansNoPlan() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{oops".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(new ComebackPlanStore(app).load());
    }
}
