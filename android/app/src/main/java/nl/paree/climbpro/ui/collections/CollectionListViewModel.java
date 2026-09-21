package nl.paree.climbpro.ui.collections;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.route.RouteCollection;
import nl.paree.climbpro.data.route.RouteCollectionRepository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CollectionListViewModel extends AndroidViewModel {

    private final RouteCollectionRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<RouteCollection>> collections = new MutableLiveData<>();
    private final MutableLiveData<String>                error       = new MutableLiveData<>();

    public CollectionListViewModel(@NonNull Application app) {
        super(app);
        repo = new RouteCollectionRepository(app);
        load();
    }

    public LiveData<List<RouteCollection>> collections() { return collections; }
    public LiveData<String>                error()       { return error; }

    public void load() {
        executor.execute(() -> {
            List<RouteCollection> all = repo.loadAll();
            all.sort(Comparator.comparing((RouteCollection c) -> c.name == null ? "" : c.name,
                    String.CASE_INSENSITIVE_ORDER));
            collections.postValue(all);
        });
    }

    public void create(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            error.postValue("Naam mag niet leeg zijn");
            return;
        }
        executor.execute(() -> {
            repo.create(trimmed);
            load();
        });
    }

    public void rename(String collectionId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            error.postValue("Naam mag niet leeg zijn");
            return;
        }
        executor.execute(() -> {
            repo.rename(collectionId, trimmed);
            load();
        });
    }

    public void delete(String collectionId) {
        executor.execute(() -> {
            repo.delete(collectionId);
            load();
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
