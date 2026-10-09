package nl.paree.climbpro.data.hydration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Failed write of the sweat-loss log. */
@RunWith(RobolectricTestRunner.class)
public class SweatLossStoreEdgeTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
    }

    private File file(String name) {
        return new File(app.getFilesDir(), name);
    }


    /** Turns {@code name} into a non-empty directory so replacing it must fail. */
    private void block(String name) throws IOException {
        file(name).mkdirs();
        Files.write(new File(file(name), "child").toPath(), new byte[]{1});
    }



    @Test
    public void add_failedWrite_throws() throws Exception {
        block(SweatLossStore.FILE_NAME + ".tmp");
        try {
            new SweatLossStore(file(SweatLossStore.FILE_NAME)).add(0, 100, 75.0, 74.5, 500, 60, null);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(file(SweatLossStore.FILE_NAME).exists());
    }
}
