package nl.paree.climbpro.ui.social;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.social.FriendShareIdentity;
import nl.paree.climbpro.data.social.RideBuddyRepository;
import nl.paree.climbpro.domain.social.RideBuddyCode;
import nl.paree.climbpro.domain.social.RideBuddyMatcher;
import nl.paree.climbpro.domain.social.RideBuddyProfile;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ride-buddy matcher (issue #242): derives your own profile, imports other riders' profile codes
 * and ranks them against you, all off the main thread. {@link #message()}, {@link #preview()}
 * and {@link #shareText()} are one-shot, consumed by the activity so a rotation doesn't replay
 * them.
 */
public final class RideBuddyViewModel extends AndroidViewModel {

    private static final String TAG = "RideBuddyViewModel";
    static final String SHARE_INTRO = "Mijn rijdersprofiel in ClimbPro — kijk of we samen kunnen "
            + "fietsen. Open ClimbPro → Ritmaatjes → Code plakken, of deel dit bericht met "
            + "ClimbPro.\n\n";

    /** The exact code about to be shared plus its decoded contents, for the confirm dialog. */
    public static final class Preview {
        public final String code;
        public final List<String> lines;

        Preview(String code, List<String> lines) {
            this.code = code;
            this.lines = lines;
        }
    }

    private final RideBuddyRepository repo;
    /** Your own profile as last built on the executor (LiveData lags behind postValue). */
    private volatile RideBuddyProfile ownProfile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<RideBuddyProfile> own = new MutableLiveData<>();
    private final MutableLiveData<List<RideBuddyMatcher.Match>> matches = new MutableLiveData<>();
    private final MutableLiveData<String> message = new MutableLiveData<>();
    private final MutableLiveData<Preview> preview = new MutableLiveData<>();
    private final MutableLiveData<String> shareText = new MutableLiveData<>();

    public RideBuddyViewModel(@NonNull Application app) {
        super(app);
        repo = new RideBuddyRepository(app);
    }

    public LiveData<RideBuddyProfile> own() { return own; }
    public LiveData<List<RideBuddyMatcher.Match>> matches() { return matches; }
    public LiveData<String> message() { return message; }
    public LiveData<Preview> preview() { return preview; }
    public LiveData<String> shareText() { return shareText; }
    public void consumeMessage() { message.setValue(null); }
    public void consumePreview() { preview.setValue(null); }
    public void consumeShareText() { shareText.setValue(null); }

    public String shareName() { return FriendShareIdentity.name(prefs()); }

    public void load() {
        executor.execute(() -> {
            try {
                RideBuddyProfile me = buildOwn();
                ownProfile = me;
                own.postValue(me);
                matches.postValue(RideBuddyMatcher.rank(me, repo.loadAll()));
            } catch (Exception e) {
                Log.e(TAG, "Load failed", e);
                message.postValue("Profiel laden mislukt.");
            }
        });
    }

    /** Encodes your profile restricted to {@code fields} and posts it for confirmation. */
    public void prepareShare(String name, int fields) {
        FriendShareIdentity.setName(prefs(), name);
        executor.execute(() -> {
            RideBuddyProfile me = ownProfile;
            if (me == null) return;
            RideBuddyProfile p = me.restrictTo(fields);
            p.name = name;
            p.riderId = RideBuddyRepository.riderId(prefs());
            p.createdEpochSec = System.currentTimeMillis() / 1000L;
            String code = RideBuddyCode.encode(p);
            try {
                // Describe what the receiver will actually decode, not what we meant to put in.
                preview.postValue(new Preview(code, RideBuddyCode.describe(RideBuddyCode.decode(code))));
            } catch (RideBuddyCode.InvalidCodeException e) {
                message.postValue("Profielcode maken mislukt.");
            }
        });
    }

    public void confirmShare(String code) {
        shareText.setValue(SHARE_INTRO + code);
    }

    public void importCode(String text) {
        executor.execute(() -> {
            try {
                RideBuddyProfile p = RideBuddyCode.decode(text);
                if (p.riderId.equals(RideBuddyRepository.riderId(prefs()))) {
                    message.postValue("Dit is je eigen profielcode.");
                    return;
                }
                p.importedEpochSec = System.currentTimeMillis() / 1000L;
                boolean isNew = repo.upsert(p);
                message.postValue(isNew ? p.name + " toegevoegd"
                        : "Profiel van " + p.name + " bijgewerkt");
                matches.postValue(RideBuddyMatcher.rank(ownProfile, repo.loadAll()));
            } catch (RideBuddyCode.InvalidCodeException e) {
                message.postValue(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                message.postValue("Importeren mislukt.");
            }
        });
    }

    public void remove(String riderId) {
        executor.execute(() -> {
            try {
                repo.remove(riderId);
            } catch (Exception e) {
                Log.e(TAG, "Remove failed", e);
                message.postValue("Verwijderen mislukt.");
            }
            matches.postValue(RideBuddyMatcher.rank(ownProfile, repo.loadAll()));
        });
    }

    private RideBuddyProfile buildOwn() {
        return OwnRideBuddyProfile.build(getApplication());
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(getApplication());
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
