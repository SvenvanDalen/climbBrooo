package nl.paree.climbpro.ui.collections;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteCollection;

import nl.paree.climbpro.ui.UiTestEnv;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class CollectionListViewModelTest {

    /**
     * Runs trigger (an async ViewModel call that eventually posts to {@code live}) and waits
     * until the LiveData's value changes (by reference) from its pre-trigger snapshot. Waiting
     * for reference-inequality rather than "became non-null" lets this be called repeatedly on
     * the same LiveData within one test without racing a stale cached value from a prior call.
     */
    private static <T> T awaitValue(androidx.lifecycle.LiveData<T> live, Runnable trigger)
            throws InterruptedException {
        T before = live.getValue();
        trigger.run();
        UiTestEnv.waitFor(() -> !(live.getValue() == before), 2000);
        return live.getValue();
    }

    @Test
    public void loadOnEmptyStoreReturnsEmptyList() throws InterruptedException {
        Application app = ApplicationProvider.getApplicationContext();
        CollectionListViewModel vm = new CollectionListViewModel(app);
        List<RouteCollection> result = awaitValue(vm.collections(), vm::load);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void createThenLoadReturnsSortedByName() throws InterruptedException {
        Application app = ApplicationProvider.getApplicationContext();
        CollectionListViewModel vm = new CollectionListViewModel(app);

        vm.create("Zomer 2026");
        UiTestEnv.idleFor(50);
        vm.create("Alpen 2026");
        UiTestEnv.idleFor(50);

        List<RouteCollection> result = awaitValue(vm.collections(), vm::load);
        assertEquals(2, result.size());
        assertEquals("Alpen 2026", result.get(0).name);
        assertEquals("Zomer 2026", result.get(1).name);
    }

    @Test
    public void createWithBlankNameEmitsErrorAndDoesNotPersist() throws InterruptedException {
        Application app = ApplicationProvider.getApplicationContext();
        CollectionListViewModel vm = new CollectionListViewModel(app);

        String err = awaitValue(vm.error(), () -> vm.create("   "));
        assertNotNull(err);

        List<RouteCollection> result = awaitValue(vm.collections(), vm::load);
        assertTrue(result.isEmpty());
    }
}
