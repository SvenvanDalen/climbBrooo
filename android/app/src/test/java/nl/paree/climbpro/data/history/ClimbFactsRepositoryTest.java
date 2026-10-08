package nl.paree.climbpro.data.history;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.domain.history.FamousClimb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

/** Loads the bundled famous-climb facts once per process. */
@RunWith(RobolectricTestRunner.class)
public class ClimbFactsRepositoryTest {

    private final Application app = ApplicationProvider.getApplicationContext();

    private static void resetCache() throws Exception {
        Field f = ClimbFactsRepository.class.getDeclaredField("cache");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        resetCache();
    }

    @After
    public void tearDown() throws Exception {
        resetCache();
    }

    @Test
    public void load_bundledAsset_isParsedAndCached() {
        List<FamousClimb> first = ClimbFactsRepository.load(app);
        assertFalse(first.isEmpty());
        assertSame(first, ClimbFactsRepository.load(app));
    }

    @Test
    public void load_unreadableAsset_givesEmptyUnmodifiableList() throws Exception {
        AssetManager assets = mock(AssetManager.class);
        when(assets.open(anyString())).thenThrow(new IOException("missing"));
        Context ctx = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public AssetManager getAssets() { return assets; }
        };

        List<FamousClimb> facts = ClimbFactsRepository.load(ctx);

        assertTrue(facts.isEmpty());
        try {
            facts.add(null);
            throw new AssertionError("list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }
}
