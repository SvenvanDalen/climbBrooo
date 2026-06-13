package nl.paree.climbpro.data.strava;

import android.content.Context;
import android.util.Log;

import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.KnownClimb;
import nl.paree.climbpro.domain.climb.KnownClimbs;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher;
import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Fetches Strava activities (last 12 months on first run, incremental after),
 * matches each against every known climb, and persists matched attempts.
 * Call {@link #syncActivities()} from a background thread.
 */
public final class StravaActivitiesRepository {

    private static final String TAG       = "StravaActivitiesRepo";
    private static final String PREFS     = "strava_activities";
    private static final String PREF_LAST = "last_sync_epoch_sec";
    private static final long   ONE_YEAR_SEC = 365L * 24 * 60 * 60;
    private static final String STREAM_KEYS  = "latlng,time";

    private final StravaAuthRepository   auth;
    private final RouteRepository        routeRepo;
    private final ClimbAttemptRepository attemptRepo;
    private final StravaApiClient        api;
    private final android.content.SharedPreferences prefs;

    public StravaActivitiesRepository(Context context,
                                      StravaAuthRepository auth,
                                      RouteRepository routeRepo,
                                      ClimbAttemptRepository attemptRepo) {
        this(context, auth, routeRepo, attemptRepo,
                buildRetrofit().create(StravaApiClient.class));
    }

    /** Test-injectable variant — pass a (mock) StravaApiClient. */
    StravaActivitiesRepository(Context context,
                               StravaAuthRepository auth,
                               RouteRepository routeRepo,
                               ClimbAttemptRepository attemptRepo,
                               StravaApiClient api) {
        this.auth = auth;
        this.routeRepo = routeRepo;
        this.attemptRepo = attemptRepo;
        this.api = api;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return number of new attempts persisted. */
    public int syncActivities() throws IOException {
        String token = "Bearer " + auth.getAccessToken();

        long lastSync = prefs.getLong(PREF_LAST, 0L);
        long nowSec   = System.currentTimeMillis() / 1000L;
        long after    = lastSync > 0 ? lastSync : nowSec - ONE_YEAR_SEC;

        List<KnownClimb> climbs = enumerateKnownClimbs();
        if (climbs.isEmpty()) return 0;

        Set<Long> known = attemptRepo.knownActivityIds();

        List<StoredClimbAttempt> created = new ArrayList<>();
        boolean paginationComplete = false;
        int page = 1;
        while (true) {
            Response<List<StravaActivityDto>> resp =
                    api.listActivities(token, after, page, 50).execute();
            if (!resp.isSuccessful()) {
                Log.w(TAG, "Activity page " + page + " failed (HTTP "
                        + resp.code() + "); keeping sync cursor for retry");
                break; // aborted — do NOT mark complete
            }
            if (resp.body() == null || resp.body().isEmpty()) {
                paginationComplete = true; // reached the end cleanly
                break;
            }
            for (StravaActivityDto act : resp.body()) {
                if (known.contains(act.id)) continue;
                created.addAll(matchActivity(token, act, climbs));
            }
            page++;
        }

        if (!created.isEmpty()) attemptRepo.append(created);
        if (paginationComplete) {
            prefs.edit().putLong(PREF_LAST, nowSec).apply();
        }
        Log.i(TAG, "Activity sync: " + created.size() + " new attempt(s)"
                + (paginationComplete ? "" : " (incomplete — cursor not advanced)"));
        return created.size();
    }

    private List<StoredClimbAttempt> matchActivity(
            String token, StravaActivityDto act, List<KnownClimb> climbs) {
        List<StoredClimbAttempt> out = new ArrayList<>();
        try {
            Response<StravaStreamsDto> sresp =
                    api.getStreams(token, act.id, STREAM_KEYS).execute();
            if (!sresp.isSuccessful() || sresp.body() == null) return out;
            StravaStreamsDto s = sresp.body();
            if (s.latlng == null || s.time == null
                    || s.latlng.data == null || s.time.data == null) return out;

            List<TrackSample> track = toTrack(s);
            if (track.size() < 2) return out;

            long dateSec = parseStartDate(act.startDate);
            for (KnownClimb k : climbs) {
                int elapsed = ClimbAttemptMatcher.match(
                        track, k.startLat, k.startLon, k.endLat, k.endLon, k.lengthM);
                if (elapsed > 0) {
                    StoredClimbAttempt a = new StoredClimbAttempt();
                    a.climbId      = k.climbId;
                    a.activityId   = act.id;
                    a.dateEpochSec = dateSec;
                    a.elapsedSec   = elapsed;
                    // Persisted for future history detail; not shown in v1 UI.
                    a.avgSpeedKmh  = (k.lengthM / (double) elapsed) * 3.6;
                    out.add(a);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Stream fetch failed for activity " + act.id, e);
        }
        return out;
    }

    private List<KnownClimb> enumerateKnownClimbs() {
        Map<String, KnownClimb> byId = new HashMap<>();
        for (RouteCatalogEntry entry : routeRepo.loadCatalog()) {
            try {
                StoredRoute route = routeRepo.loadRoute(entry.routeId);
                for (KnownClimb k : KnownClimbs.fromRoute(route)) {
                    byId.put(k.climbId, k); // dedupe same climb appearing on multiple routes
                }
            } catch (IOException e) {
                Log.w(TAG, "Skipping route " + entry.routeId + " in climb enumeration", e);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static List<TrackSample> toTrack(StravaStreamsDto s) {
        int n = Math.min(s.latlng.data.size(), s.time.data.size());
        List<TrackSample> track = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<Double> ll = s.latlng.data.get(i);
            if (ll == null || ll.size() < 2) continue;
            track.add(new TrackSample(ll.get(0), ll.get(1), s.time.data.get(i)));
        }
        return track;
    }

    private static long parseStartDate(String iso) {
        if (iso == null) return 0L;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt.parse(iso).getTime() / 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Retrofit buildRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logging).build();
        return new Retrofit.Builder()
                .baseUrl(StravaApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
    }
}
