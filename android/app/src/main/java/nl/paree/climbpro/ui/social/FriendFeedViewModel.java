package nl.paree.climbpro.ui.social;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.social.FriendFeedEntry;
import nl.paree.climbpro.data.social.FriendFeedRepository;
import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.domain.climb.ClimbCatalogIndex;
import nl.paree.climbpro.domain.social.FriendShareCode;
import nl.paree.climbpro.domain.social.HomeClimbAggregator;
import nl.paree.climbpro.domain.social.OwnFeedBuilder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Friends' feed (issue #240): loads imported entries, imports codes and builds your own share
 * text, all off the main thread. {@link #message()} and {@link #shareText()} are one-shot: the
 * activity calls the matching {@code consume...()} after handling a value so a rotation does not
 * replay a toast or reopen the share sheet.
 */
public final class FriendFeedViewModel extends AndroidViewModel {

    private static final String TAG = "FriendFeedViewModel";
    static final String SHARE_INTRO = "Mijn ritten van de afgelopen 30 dagen in ClimbPro. "
            + "Open ClimbPro → Vriendenfeed → Code plakken, of deel dit bericht met ClimbPro.\n\n";

    private final FriendFeedRepository feedRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<FriendFeedEntry>> entries = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<String> shareText = new MutableLiveData<>();

    public FriendFeedViewModel(@NonNull Application app) {
        super(app);
        feedRepo = new FriendFeedRepository(app);
    }

    public LiveData<List<FriendFeedEntry>> entries() { return entries; }
    public LiveData<String> message() { return message; }
    public LiveData<String> shareText() { return shareText; }
    public void consumeMessage() { message.setValue(null); }
    public void consumeShareText() { shareText.setValue(null); }

    public String shareName() { return FriendShareIdentity.name(prefs()); }

    public void load() {
        executor.execute(() -> entries.postValue(feedRepo.loadAll()));
    }

    public void importCode(String text) {
        executor.execute(() -> {
            try {
                FriendShareCode.Payload p = FriendShareCode.decode(text);
                if (p.sharerId.equals(FriendShareIdentity.sharerId(prefs()))) {
                    message.postValue("Dit is je eigen deelcode.");
                    return;
                }
                int added = feedRepo.importPayload(p);
                message.postValue(added == 0
                        ? "Geen nieuwe items van " + p.sharerName
                        : added + " nieuw(e) item(s) van " + p.sharerName);
                entries.postValue(feedRepo.loadAll());
            } catch (FriendShareCode.InvalidCodeException e) {
                message.postValue(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                message.postValue("Importeren mislukt.");
            }
        });
    }

    /** Stores {@code name}, builds your snapshot and posts the text for the share sheet. */
    public void share(String name) {
        FriendShareIdentity.setName(prefs(), name);
        executor.execute(() -> {
            try {
                List<StoredClimbAttempt> attempts =
                        new ClimbAttemptRepository(getApplication()).loadAll();
                Set<String> ids = new HashSet<>();
                for (StoredClimbAttempt a : attempts) if (a.climbId != null) ids.add(a.climbId);
                Map<String, OwnFeedBuilder.ClimbInfo> climbs = new HashMap<>();
                for (Map.Entry<String, List<StoredClimb>> en : ClimbCatalogIndex.resolveAllCopies(
                        new RouteRepository(getApplication()), ids).entrySet()) {
                    OwnFeedBuilder.ClimbInfo info = HomeClimbAggregator.aggregate(en.getValue());
                    if (info != null) climbs.put(en.getKey(), info);
                }
                long now = System.currentTimeMillis() / 1000L;
                List<FriendFeedEntry> own = OwnFeedBuilder.build(
                        new RideRepository(getApplication()).loadAll(), attempts, climbs, now);
                if (own.isEmpty()) {
                    message.postValue("Nog geen ritten of eerste beklimmingen in de afgelopen "
                            + "30 dagen om te delen. Haal eerst je ritten op via Ritten.");
                    return;
                }
                String code = FriendShareCode.encode(
                        FriendShareIdentity.sharerId(prefs()), name, now, own);
                shareText.postValue(SHARE_INTRO + code);
            } catch (Exception e) {
                Log.e(TAG, "Share failed", e);
                message.postValue("Deelcode maken mislukt.");
            }
        });
    }

    public void removeFriend(String friendId) {
        executor.execute(() -> {
            try {
                feedRepo.removeFriend(friendId);
            } catch (Exception e) {
                Log.e(TAG, "Remove friend failed", e);
                message.postValue("Verwijderen mislukt.");
            }
            entries.postValue(feedRepo.loadAll());
        });
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(getApplication());
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
