package nl.paree.climbpro.ui.planning;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.planning.PackingList;
import nl.paree.climbpro.data.planning.PackingListStore;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #189: packing checklists per ride type. Every edit runs on one background executor,
 * which also serialises access to the (not thread-safe) {@link PackingListStore}, then posts
 * the reloaded lists. Phone-only.
 */
public final class PackingListViewModel extends AndroidViewModel {

    private static final String TAG = "PackingListVM";

    private interface Edit {
        void run() throws IOException;
    }

    private final PackingListStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<PackingList>> lists = new MutableLiveData<>();
    /** Id of the list on screen; kept here so it survives rotation. */
    private String selectedId;

    public PackingListViewModel(@NonNull Application app) {
        super(app);
        store = new PackingListStore(new File(app.getFilesDir(), PackingListStore.FILE_NAME));
    }

    public LiveData<List<PackingList>> lists() { return lists; }

    public String selectedId() { return selectedId; }

    public void select(String listId) { selectedId = listId; }

    public void load() { edit(() -> { }); }

    public void addList(String name) {
        edit(() -> selectedId = store.addList(name).id);
    }

    public void renameList(String listId, String name) { edit(() -> store.renameList(listId, name)); }

    public void deleteList(String listId) { edit(() -> store.deleteList(listId)); }

    public void addItem(String listId, String text) { edit(() -> store.addItem(listId, text)); }

    public void removeItem(String listId, String itemId) {
        edit(() -> store.removeItem(listId, itemId));
    }

    public void setChecked(String listId, String itemId, boolean checked) {
        edit(() -> store.setChecked(listId, itemId, checked));
    }

    public void resetChecks(String listId) { edit(() -> store.resetChecks(listId)); }

    private void edit(Edit e) {
        executor.execute(() -> {
            try {
                e.run();
            } catch (IOException | IllegalArgumentException ex) {
                Log.e(TAG, "packing list edit failed", ex);
            }
            lists.postValue(store.loadAll());
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
