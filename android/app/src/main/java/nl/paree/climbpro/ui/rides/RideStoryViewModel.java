package nl.paree.climbpro.ui.rides;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.domain.ride.RideStory;
import nl.paree.climbpro.domain.ride.RideTrack;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ride story (issue #193): builds the {@link RideStory} for one archived ride and draws it.
 * The GPS track and temperature come from Strava on demand; without Strava (or offline) the
 * story is still made, just without the route shape. Nothing is stored.
 */
public final class RideStoryViewModel extends AndroidViewModel {

    private static final String TAG = "RideStoryVM";
    /** Longest side the ride photo is decoded to; the image slot is ~400 px wide. */
    private static final int PHOTO_MAX_PX = 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Bitmap> image = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private boolean started;

    public RideStoryViewModel(@NonNull Application app) {
        super(app);
    }

    public LiveData<Bitmap> image() { return image; }
    public LiveData<String> error() { return error; }

    /** Builds once per screen; a rotation keeps the image. */
    public void load(long activityId) {
        if (started) return;
        started = true;
        executor.execute(() -> {
            StoredRide ride = null;
            for (StoredRide r : new RideRepository(getApplication()).loadAll()) {
                if (r.activityId == activityId) ride = r;
            }
            if (ride == null) {
                error.postValue("Rit niet gevonden in het archief");
                return;
            }
            List<StoredClimbAttempt> attempts =
                    new ClimbAttemptRepository(getApplication()).loadAll();
            RouteRepository routes = new RouteRepository(getApplication());
            RideTrack track = fetchTrack(ride.activityId, routes);
            RideStory story = RideStory.build(ride, attempts,
                    RideArchiveViewModel.climbNames(routes),
                    track != null ? track.avgTempC : null);
            Bitmap photo = story.photoFileName != null ? decodePhoto(story.photoFileName) : null;
            image.postValue(RideStoryImageComposer.compose(story, track, photo));
        });
    }

    private RideTrack fetchTrack(long activityId, RouteRepository routes) {
        StravaAuthRepository auth = new StravaAuthRepository(getApplication());
        if (!auth.isAuthorised()) return null;
        try {
            return new StravaActivitiesRepository(getApplication(), auth, routes,
                    new ClimbAttemptRepository(getApplication())).fetchRideTrack(activityId);
        } catch (Exception e) {
            Log.w(TAG, "Track fetch failed; story without route shape", e);
            return null;
        }
    }

    private Bitmap decodePhoto(String fileName) {
        File file = AttemptPhotoStore.fileFor(getApplication(), fileName);
        if (!file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_PX) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    @Override
    protected void onCleared() { executor.shutdown(); }
}
