package nl.paree.climbpro.ui.bike;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.bike.BikePassport;
import nl.paree.climbpro.data.bike.BikePassportPhotoStore;
import nl.paree.climbpro.data.bike.BikePassportStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Issue #190: bike theft passports. All file work runs on one background executor, which also
 * serialises access to the (not thread-safe) {@link BikePassportStore}. Phone-only.
 */
public final class BikePassportViewModel extends AndroidViewModel {

    private static final String TAG = "BikePassportVM";

    private final BikePassportStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<BikePassport>> passports = new MutableLiveData<>();
    private final MutableLiveData<String> messages = new MutableLiveData<>();

    public BikePassportViewModel(@NonNull Application app) {
        super(app);
        store = new BikePassportStore(new File(app.getFilesDir(), BikePassportStore.FILE_NAME));
        executor.execute(() -> BikePassportPhotoStore.cleanupOrphans(app, store.loadAll()));
    }

    public LiveData<List<BikePassport>> passports() { return passports; }

    /** One-shot user-facing error texts (Dutch). */
    public LiveData<String> messages() { return messages; }

    public void load() {
        executor.execute(() -> passports.postValue(store.loadAll()));
    }

    public void save(BikePassport passport) {
        executor.execute(() -> {
            try {
                store.save(passport);
            } catch (IOException | IllegalArgumentException e) {
                Log.e(TAG, "save failed", e);
                messages.postValue("Opslaan mislukt");
            }
            passports.postValue(store.loadAll());
        });
    }

    public void delete(String id) {
        executor.execute(() -> {
            try {
                BikePassport removed = store.delete(id);
                if (removed != null) {
                    for (String f : removed.allFileNames()) {
                        BikePassportPhotoStore.delete(getApplication(), f);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "delete failed", e);
                messages.postValue("Verwijderen mislukt");
            }
            passports.postValue(store.loadAll());
        });
    }

    /** Copies the picked file in and attaches it as a bike photo or as the receipt. */
    public void addPhoto(String id, Uri uri, boolean receipt) {
        executor.execute(() -> {
            BikePassport p = store.get(id);
            if (p == null) return;
            try {
                String fileName = BikePassportPhotoStore.savePickedPhoto(getApplication(), uri);
                if (receipt) {
                    String old = p.receiptFileName;
                    p.receiptFileName = fileName;
                    store.save(p);
                    BikePassportPhotoStore.delete(getApplication(), old);
                } else {
                    List<String> photos = new ArrayList<>(p.photoFileNames);
                    photos.add(fileName);
                    p.photoFileNames = photos;
                    store.save(p);
                }
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "photo failed", e);
                messages.postValue("Foto toevoegen mislukt");
            }
            passports.postValue(store.loadAll());
        });
    }

    /** Removes every bike photo (the receipt stays). */
    public void clearPhotos(String id) {
        executor.execute(() -> {
            BikePassport p = store.get(id);
            if (p == null) return;
            List<String> old = p.photoFileNames;
            p.photoFileNames = new ArrayList<>();
            try {
                store.save(p);
                for (String f : old) BikePassportPhotoStore.delete(getApplication(), f);
            } catch (IOException e) {
                Log.e(TAG, "clear photos failed", e);
                messages.postValue("Foto's wissen mislukt");
            }
            passports.postValue(store.loadAll());
        });
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
